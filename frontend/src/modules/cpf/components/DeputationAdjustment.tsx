'use client';

import { useState } from 'react';
import {
  EMPTY_ORDER,
  OrderFields,
  orderComplete,
  todayIso,
  useDocumentTypes,
  type OrderDraft,
} from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import {
  amount,
  Card,
  date,
  Empty,
  Problem,
  ServerPagination,
  Src,
  useConfirmCancel,
  useToast,
  TextAreaField,
  MonthField,
} from '@/shared/ui';
import type { DeputationEntry, DeputationEntryType, DeputedMember, NewDeputationEntry } from '../api/cpfApi';
import { emptyEntry, ENTRY_TYPES, entryProblems, MAX_EVIDENCE_FILES } from '../business/deputationRules';
import {
  cpfKeys,
  cpfPages,
  deputationEvidenceUrl,
  useAddBorrowingOrganisation,
  useAddDeputationEvidence,
  useBorrowingOrganisations,
  useCancelDeputationEvidence,
  useDeputedMember,
  useRecordDeputation,
} from '../orchestration/cpfQueries';

const EIS = /^[A-Za-z0-9]{1,20}$/;

const STATUS_PILL: Record<DeputationEntry['status'], string> = {
  MAKER_SAVED: 'p-warn',
  PENDING_CHECKER: 'p-info',
  APPROVED: 'p-ok',
  REJECTED: 'p-bad',
};

/**
 * I-09 CPF Adjustment / Recovery Adjustment for deputed members (plan 07 phase 07g; SRS-6-27 … 6-30,
 * BR-CPF-021 … 023). CPF stays with FCI during a deputation: the Manager (CPF) at a Zonal Office or
 * Headquarters records what the borrowing organisation remits (an advance instalment, a monthly
 * contribution with its three parts, or an excess refund item) with the remittance evidence; the AGM
 * (CPF) approves it in the Approvals Inbox. The deputation itself is HRMS's and read-only. An advance
 * falls only on approval and never below zero; a month without a receipt is listed for the CPF
 * Control Register. Below, the register of the user's scope.
 */
export function DeputationAdjustment() {
  const { t } = useI18n();
  const me = useMe();
  const mayCreate = can(me.data, 'I-09', 'CREATE');
  const [lookup, setLookup] = useState('');
  const [eis, setEis] = useState('');
  const member = useDeputedMember(eis);
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sub2">{t('i09.hint')}</span>
      </div>
      <Card title={t('screen.I-09')}>
        <div className="bd rf-grid">
          <div className="rf-field">
            <label htmlFor="i09-eis">{t('i09.eis')}</label>
            <input id="i09-eis" maxLength={20} value={lookup} onChange={(e) => setLookup(e.target.value)} />
          </div>
          <div className="rf-field">
            <button
              type="button"
              className="btn"
              disabled={!EIS.test(lookup.trim())}
              onClick={() => setEis(lookup.trim())}
            >
              {t('i09.find')}
            </button>
          </div>
        </div>
        {eis === '' && <Empty text={t('i09.chooseMember')} />}
        <Problem error={member.error} />
        {member.data && <Member m={member.data} />}
      </Card>
      {member.data && mayCreate && <NewEntry key={member.data.eis} m={member.data} />}
      {mayCreate && <NewOrganisation />}
      <Card title={t('i09.register')}>
        <div className="wrap">
          <ServerPagination
            queryKey={cpfKeys.deputationEntries}
            fetchPage={cpfPages.deputationEntries}
            empty={<Empty text={t('i09.none')} />}
          >
            {(entries) => <Entries entries={entries} />}
          </ServerPagination>
        </div>
      </Card>
    </div>
  );
}

function Member({ m }: { m: DeputedMember }) {
  const { t } = useI18n();
  return (
    <>
      <dl className="bd kv" aria-label={t('i09.deputationHead')}>
        <div>
          <dt>
            {t('i09.eis')}
            <Src s="HRMS" />
          </dt>
          <dd>
            {m.name} ({m.eis})
          </dd>
        </div>
        <div>
          <dt>
            {t('i09.deputationHead')}
            <Src s="HRMS" />
          </dt>
          <dd>
            {t('i09.deputation', [
              date(m.deputationFrom),
              m.deputationTo ? date(m.deputationTo) : t('i09.open'),
            ])}
          </dd>
        </div>
        <div>
          <dt>
            {t('i09.missing')}
            <Src s="CALC" />
          </dt>
          <dd>{m.monthsWithoutReceipt.length === 0 ? t('i09.none') : m.monthsWithoutReceipt.join(', ')}</dd>
        </div>
      </dl>
      <table className="t" aria-label={t('i09.advances')}>
        <thead>
          <tr>
            <th>
              {t('i09.sanctionNumber')}
              <Src s="SYS" />
            </th>
            <th>
              {t('i09.sanctioned')}
              <Src s="SYS" />
            </th>
            <th>
              {t('i09.outstanding')}
              <Src s="CALC" />
            </th>
          </tr>
        </thead>
        <tbody>
          {m.advances.map((a) => (
            <tr key={a.sanctionId}>
              <td>{a.sanctionNumber ?? a.sanctionId}</td>
              <td>{amount(a.sanctioned)}</td>
              <td>{amount(a.outstanding)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {m.advances.length === 0 && <p className="sub2">{t('i09.none')}</p>}
      <h3>{t('i09.entries')}</h3>
      {m.entries.length === 0 ? <Empty text={t('i09.none')} /> : <Entries entries={m.entries} />}
    </>
  );
}

function NewEntry({ m }: { m: DeputedMember }) {
  const { t } = useI18n();
  const toast = useToast();
  const orgs = useBorrowingOrganisations();
  const types = useDocumentTypes(true);
  const record = useRecordDeputation();
  const [entry, setEntry] = useState<NewDeputationEntry>(emptyEntry(m.eis, 'CONTRIBUTION_RECEIPT'));
  const [order, setOrder] = useState<OrderDraft>(EMPTY_ORDER);
  const [error, setError] = useState<unknown>(null);
  const today = todayIso();
  const problems = entryProblems(entry, m, orderComplete(order, types.data ?? [], today));
  const set = (patch: Partial<NewDeputationEntry>) => setEntry({ ...entry, ...patch });

  async function submit() {
    if (order.file === null) return;
    setError(null);
    try {
      await record.mutateAsync({
        body: entry,
        evidence: {
          documentType: order.documentType,
          reference: order.reference.trim(),
          orderDate: order.orderDate,
          issuingAuthority: order.issuingAuthority.trim(),
          file: order.file,
        },
      });
      toast(t('i09.submitted'));
      setEntry(emptyEntry(m.eis, entry.type));
      setOrder(EMPTY_ORDER);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('i09.newEntry')}>
      <Problem error={error} />
      <div className="bd rf-grid">
        <div className="rf-field">
          <label htmlFor="i09-type">{t('i09.type')}</label>
          <select
            id="i09-type"
            value={entry.type}
            onChange={(e) => setEntry(emptyEntry(m.eis, e.target.value as DeputationEntryType))}
          >
            {ENTRY_TYPES.map((type) => (
              <option key={type} value={type}>
                {t(`i09.type.${type}`)}
              </option>
            ))}
          </select>
        </div>
        <div className="rf-field">
          <label htmlFor="i09-org">{t('i09.organisation')}</label>
          <select
            id="i09-org"
            value={entry.borrowingOrgId}
            onChange={(e) => set({ borrowingOrgId: e.target.value })}
          >
            <option value="">—</option>
            {(orgs.data ?? [])
              .filter((o) => o.active)
              .map((o) => (
                <option key={o.id} value={String(o.id)}>
                  {o.name} ({o.code})
                </option>
              ))}
          </select>
        </div>
        <div className="rf-field">
          <label htmlFor="i09-month">{t('i09.month')}</label>
          <MonthField id="i09-month" value={entry.payrollYymm} onChange={(v) => set({ payrollYymm: v })} />
        </div>
        {entry.type === 'RECOVERY_INSTALMENT' && (
          <div className="rf-field">
            <label htmlFor="i09-advance">{t('i09.advance')}</label>
            <select
              id="i09-advance"
              value={entry.sanctionId}
              onChange={(e) => set({ sanctionId: e.target.value })}
            >
              <option value="">—</option>
              {m.advances.map((a) => (
                <option key={a.sanctionId} value={String(a.sanctionId)}>
                  {a.sanctionNumber ?? a.sanctionId} · {amount(a.outstanding)}
                </option>
              ))}
            </select>
          </div>
        )}
        {entry.type === 'CONTRIBUTION_RECEIPT' ? (
          (['employee', 'employer', 'vpf'] as const).map((part) => (
            <div className="rf-field" key={part}>
              <label htmlFor={`i09-${part}`}>{t(`i09.${part}`)}</label>
              <input
                id={`i09-${part}`}
                inputMode="decimal"
                maxLength={16}
                value={entry[part]}
                onChange={(e) => set({ [part]: e.target.value })}
              />
            </div>
          ))
        ) : (
          <div className="rf-field">
            <label htmlFor="i09-instalment">{t('i09.instalment')}</label>
            <input
              id="i09-instalment"
              inputMode="decimal"
              maxLength={16}
              value={entry.instalment}
              onChange={(e) => set({ instalment: e.target.value })}
            />
          </div>
        )}
        <OrderFields
          idPrefix="i09"
          value={order}
          types={types.data ?? []}
          today={today}
          onChange={setOrder}
        />
      </div>
      {problems.length > 0 && (
        <ul className="fl-list" aria-label={t('i09.newEntry')}>
          {problems.map((p) => (
            <li key={p} className="pill p-warn">
              {t(p)}
            </li>
          ))}
        </ul>
      )}
      <div className="rf-actbar">
        <button
          type="button"
          className="btn pri"
          disabled={problems.length > 0 || record.isPending}
          title={problems.length > 0 ? t(problems[0] ?? '') : undefined}
          onClick={() => void submit()}
        >
          {t('i09.submit')}
        </button>
      </div>
    </Card>
  );
}

function NewOrganisation() {
  const { t } = useI18n();
  const toast = useToast();
  const add = useAddBorrowingOrganisation();
  const [org, setOrg] = useState({ code: '', name: '', contact: '' });
  const [error, setError] = useState<unknown>(null);
  const ok = /^[A-Z0-9_-]{1,30}$/.test(org.code.trim()) && org.name.trim() !== '';

  async function submit() {
    setError(null);
    try {
      await add.mutateAsync({
        code: org.code.trim(),
        name: org.name.trim(),
        contact: org.contact.trim() === '' ? null : org.contact.trim(),
      });
      toast(t('i09.orgAdded'));
      setOrg({ code: '', name: '', contact: '' });
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('i09.newOrg')}>
      <Problem error={error} />
      <div className="bd rf-grid">
        <div className="rf-field">
          <label htmlFor="i09-org-code">{t('i09.orgCode')}</label>
          <input
            id="i09-org-code"
            maxLength={30}
            value={org.code}
            onChange={(e) => setOrg({ ...org, code: e.target.value.toUpperCase() })}
          />
        </div>
        <div className="rf-field">
          <label htmlFor="i09-org-name">{t('i09.orgName')}</label>
          <input
            id="i09-org-name"
            maxLength={250}
            value={org.name}
            onChange={(e) => setOrg({ ...org, name: e.target.value })}
          />
        </div>
        <div className="rf-field">
          <label htmlFor="i09-org-contact">{t('i09.orgContact')}</label>
          <TextAreaField
            id="i09-org-contact"
            maxLength={500}
            value={org.contact}
            onChange={(e) => setOrg({ ...org, contact: e.target.value })}
          />
        </div>
        <div className="rf-field">
          <button type="button" className="btn" disabled={!ok || add.isPending} onClick={() => void submit()}>
            {t('i09.newOrg')}
          </button>
        </div>
      </div>
    </Card>
  );
}

/**
 * I-09 Remittance Evidence of one entry (SRS-G-31): every active file, a wrong one cancelled with the
 * reason (never deleted; the last file stays, the evidence is mandatory) and more added after submission,
 * up to five, by the Manager (CPF).
 */
function EvidencePanel({ entry, onClose }: { entry: DeputationEntry; onClose: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const types = useDocumentTypes(true);
  const add = useAddDeputationEvidence();
  const cancel = useCancelDeputationEvidence();
  const confirmCancel = useConfirmCancel();
  const [order, setOrder] = useState<OrderDraft>(EMPTY_ORDER);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const today = todayIso();
  const maker = can(me.data, 'I-09', 'CREATE');
  const files = entry.evidence;
  const full = files.length >= MAX_EVIDENCE_FILES;
  const addReady = !full && orderComplete(order, types.data ?? [], today) && order.file !== null;

  async function addFile() {
    if (order.file === null) return;
    setError(null);
    try {
      await add.mutateAsync({
        id: entry.id,
        evidence: {
          documentType: order.documentType,
          reference: order.reference.trim(),
          orderDate: order.orderDate,
          issuingAuthority: order.issuingAuthority.trim(),
          file: order.file,
        },
      });
      toast(t('i09.evidenceAdded'));
      setOrder(EMPTY_ORDER);
    } catch (cause) {
      setError(cause);
    }
  }

  async function cancelFile(fileId: number) {
    setError(null);
    try {
      await cancel.mutateAsync({ id: entry.id, fileId, reason: reason.trim() });
      toast(t('i09.evidenceCancelled'));
      setReason('');
    } catch (cause) {
      setError(cause);
    }
  }

  const cancelBlock =
    files.length <= 1 ? t('i09.evidenceLast') : reason.trim() === '' ? t('i09.evidenceReasonNeeded') : null;
  return (
    <Card title={t('i09.evidenceOf', [entry.payrollYymm, t(`i09.type.${entry.type}`)])}>
      <Problem error={error} />
      {confirmCancel.dialog}
      <table className="t" aria-label={t('i09.evidence')}>
        <thead>
          <tr>
            <th>
              {t('i09.evidenceFile')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i09.evidenceBy')}
              <Src s="SYS" />
            </th>
            <th>
              {t('i09.evidenceAt')}
              <Src s="SYS" />
            </th>
            <th>
              {t('common.actions')}
              <Src s="SYS" />
            </th>
          </tr>
        </thead>
        <tbody>
          {files.map((f) => (
            <tr key={f.id}>
              <td>
                <a href={deputationEvidenceUrl(entry.id, f.id)}>{f.fileName}</a>
              </td>
              <td>{f.uploadedBy}</td>
              <td>{date(f.uploadedAt)}</td>
              <td>
                {maker && (
                  <button
                    type="button"
                    className="btn"
                    disabled={cancelBlock !== null || cancel.isPending}
                    title={cancelBlock ?? undefined}
                    onClick={() => confirmCancel.ask(f.fileName, () => void cancelFile(f.id))}
                  >
                    {t('i09.evidenceCancel', [f.fileName])}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {maker && (
        <>
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="i09-ev-reason">{t('i09.evidenceReason')}</label>
              <TextAreaField
                id="i09-ev-reason"
                maxLength={500}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
            <OrderFields
              idPrefix="i09-ev"
              value={order}
              types={types.data ?? []}
              today={today}
              onChange={setOrder}
            />
          </div>
          <div className="rf-actbar">
            <button
              type="button"
              className="btn pri"
              disabled={!addReady || add.isPending}
              title={full ? t('i09.evidenceFull', [MAX_EVIDENCE_FILES]) : undefined}
              onClick={() => void addFile()}
            >
              {t('i09.evidenceAdd')}
            </button>
            <button type="button" className="btn" onClick={onClose}>
              {t('common.close')}
            </button>
          </div>
        </>
      )}
      {!maker && (
        <div className="rf-actbar">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
      )}
    </Card>
  );
}

function Entries({ entries }: { entries: DeputationEntry[] }) {
  const { t } = useI18n();
  const [open, setOpen] = useState<number | null>(null);
  const opened = entries.find((e) => e.id === open) ?? null;
  return (
    <>
      <table className="t" aria-label={t('i09.entries')}>
        <thead>
          <tr>
            <th>
              {t('i09.eis')}
              <Src s="HRMS" />
            </th>
            <th>
              {t('i09.month')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i09.type')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i09.organisation')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i09.advanceRef')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i09.instalment')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i09.employee')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i09.employer')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i09.vpf')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i09.after')}
              <Src s="CALC" />
            </th>
            <th>
              {t('i09.status')}
              <Src s="SYS" />
            </th>
          </tr>
        </thead>
        <tbody>
          {entries.map((e) => (
            <tr key={e.id} data-reference={`dep-${e.id}`}>
              <td>{e.eis}</td>
              <td>{e.payrollYymm}</td>
              <td>{t(`i09.type.${e.type}`)}</td>
              <td>{e.organisation}</td>
              <td>{e.type === 'RECOVERY_INSTALMENT' ? (e.sanctionNumber ?? e.sanctionId ?? '—') : '—'}</td>
              <td>{e.type === 'CONTRIBUTION_RECEIPT' ? '—' : amount(e.instalment)}</td>
              <td>{e.type === 'CONTRIBUTION_RECEIPT' ? amount(e.employee) : '—'}</td>
              <td>{e.type === 'CONTRIBUTION_RECEIPT' ? amount(e.employer) : '—'}</td>
              <td>{e.type === 'CONTRIBUTION_RECEIPT' ? amount(e.vpf) : '—'}</td>
              <td>{e.balanceAfter === null ? '—' : amount(e.balanceAfter)}</td>
              <td>
                <span className={`pill ${STATUS_PILL[e.status]}`}>{t(`i09.status.${e.status}`)}</span>
                {e.decisionReason && (
                  <div className="sub2">{t('i09.decisionReason', [e.decisionReason])}</div>
                )}
                {e.evidence.length > 0 && (
                  <>
                    {' '}
                    <button
                      type="button"
                      className="btn"
                      onClick={() => setOpen(open === e.id ? null : e.id)}
                    >
                      {t('i09.evidenceCount', [e.evidence.length])}
                    </button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {opened && <EvidencePanel key={opened.id} entry={opened} onClose={() => setOpen(null)} />}
    </>
  );
}
