'use client';

import { useDocumentTypes } from '@/modules/general';
import { useState } from 'react';
import { businessToday } from '@/shared/time';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import {
  amount,
  Card,
  date,
  Empty,
  ExportButton,
  Problem,
  ServerPagination,
  Src,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import type { OrderCopyInput } from '@/shared/ui/orderCopy';
import type { CaseRequest, Proposal, RunOutcome } from '../api/incrementApi';
import {
  CASE_TYPES,
  caseReady,
  entersBasic,
  OBSERVATION_SOURCES,
  selectable,
} from '../business/incrementRules';
import {
  incrementCalls,
  incrementKeys,
  incrementPages,
  incrementUrls,
  useHistory,
  useIncrementAction,
} from '../orchestration/incrementQueries';

/** The business month (YYYY-MM) in India, whatever the browser's clock zone (I-05 review 17-1). */
function thisMonth(): string {
  return businessToday().slice(0, 7);
}

/**
 * I-05 Increment &amp; Pay Fixation (plan 17; SRS-13-1 … 13-27). The month's Increment Due list with
 * each member's actual due date and the revised Basic proposed (the next stage of the scale, or
 * the band's increment rule), every member that cannot be incremented flagged with why; Establishment
 * / HR selects and submits; a case worked out in Payroll is raised with its order and previewed
 * before submission; the member's Basic history from joining. The Payroll Checker approves in
 * W-01, a correction with OTP. Nothing changes the master before the approval.
 */
export function IncrementFixation({ order = null }: { order?: OrderCopyInput | null } = {}) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const [month, setMonth] = useState(thisMonth());
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [remark, setRemark] = useState('');
  const [outcome, setOutcome] = useState<RunOutcome | null>(null);
  const [error, setError] = useState<unknown>(null);
  const action = useIncrementAction();
  const maySubmit = can(me.data, 'I-05', 'SUBMIT');

  function toggle(id: number) {
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function submit() {
    setError(null);
    try {
      const o = (await action.mutateAsync(() =>
        incrementCalls.submitRun([...selected], remark.trim()),
      )) as RunOutcome;
      setOutcome(o);
      setSelected(new Set());
      toast(t('i05.submitted', [o.approvalIds.length]));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <p className="sub2">{t('i05.note')}</p>
      <div className="toolbar">
        <label>
          {t('i05.month')}{' '}
          <input
            type="month"
            value={month}
            onChange={(e) => {
              // UI audit #12: members ticked in another month are never sent with this month's run.
              setMonth(e.target.value);
              setSelected(new Set());
            }}
          />
        </label>
        {can(me.data, 'I-05', 'EXPORT') && month !== '' && (
          <ExportButton
            href={incrementUrls.tracking(month)}
            what={t('i05.tracking')}
            label={t('i05.tracking')}
          />
        )}
      </div>
      <Card title={t('i05.due')}>
        <Problem error={error} />
        {/* UI audit #30: a cleared month asks for one instead of sending month= (400). */}
        {month === '' ? (
          <p className="fl-hint">{t('i05.why.month')}</p>
        ) : (
          <ServerPagination
            queryKey={incrementKeys.due(month)}
            fetchPage={incrementPages.due(month)}
            empty={<Empty text={t('i05.noneDue')} />}
          >
            {(rows) => (
              <table className="t" aria-label={t('i05.due')}>
                <thead>
                  <tr>
                    <th aria-label={t('i05.select')} />
                    <th>
                      {t('i05.member')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i05.payScale')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i05.incrementType')}
                      <Src s="RULE" />
                    </th>
                    <th>
                      {t('i05.dueDate')}
                      <Src s="CALC" />
                    </th>
                    <th>
                      {t('i05.nextDue')}
                      <Src s="CALC" />
                    </th>
                    <th>
                      {t('i05.oldBasic')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('i05.revisedBasic')}
                      <Src s="CALC" />
                    </th>
                    <th>
                      {t('i05.flags')}
                      <Src s="SYS" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.employeeId} data-eis={r.eis}>
                      <td>
                        <input
                          type="checkbox"
                          aria-label={t('i05.selectMember', [r.eis])}
                          checked={selected.has(r.employeeId)}
                          disabled={!maySubmit || !selectable(r)}
                          title={
                            !maySubmit
                              ? t('reason.establishmentOnly')
                              : r.pending
                                ? t('i05.why.pending')
                                : !selectable(r)
                                  ? t('i05.why.flagged')
                                  : undefined
                          }
                          onChange={() => toggle(r.employeeId)}
                        />
                      </td>
                      <td>
                        {r.eis} {r.name}
                        <div className="sub2">{r.office}</div>
                      </td>
                      <td>{r.payScaleCode ?? '—'}</td>
                      <td>{t(`i05.type.${r.incrementType}`)}</td>
                      <td>{date(r.dueDate)}</td>
                      <td>{date(r.nextDueDate)}</td>
                      <td className="num">{r.oldBasic === null ? '—' : amount(r.oldBasic)}</td>
                      <td className="num">{r.revisedBasic === null ? '—' : amount(r.revisedBasic)}</td>
                      <td>
                        {r.pending && <div>{t('i05.pending')}</div>}
                        {r.flags.map((f) => (
                          <div key={f} className="sub2">
                            {t(`i05.flag.${f}`)}
                          </div>
                        ))}
                        {r.barredBy.length > 0 && <div className="sub2">{r.barredBy.join(', ')}</div>}
                        {r.stagnation && r.stagnation !== 'ELIGIBLE' && (
                          <div className="sub2">{t(`i05.stag.${r.stagnation}`)}</div>
                        )}
                        {r.stagnationTest && <div className="sub2">{r.stagnationTest}</div>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </ServerPagination>
        )}
        <div className="toolbar">
          <label>
            {t('common.reason')}{' '}
            <TextAreaField
              maxLength={500}
              value={remark}
              disabled={!maySubmit}
              onChange={(e) => setRemark(e.target.value)}
            />
          </label>
          <button
            type="button"
            className="btn pri"
            disabled={!maySubmit || selected.size === 0 || remark.trim() === '' || action.isPending}
            title={
              !maySubmit
                ? t('reason.establishmentOnly')
                : selected.size === 0
                  ? t('i05.why.none')
                  : remark.trim() === ''
                    ? t('i05.why.remark')
                    : undefined
            }
            onClick={() => void submit()}
          >
            {t('action.i05.submit')} ({selected.size})
          </button>
        </div>
        {outcome && outcome.refused.length > 0 && (
          <ul className="fl-list" aria-label={t('i05.refused')}>
            {outcome.refused.map((r) => (
              <li key={r.eis} className="warn">
                {r.eis}: {r.reason}
              </li>
            ))}
          </ul>
        )}
      </Card>
      {maySubmit && <CaseCard order={order} />}
      <HistoryCard />
    </div>
  );
}

/** A case worked out in Payroll: previewed, then submitted with its order (SRS-13-18). */
function CaseCard({ order }: { order: OrderCopyInput | null }) {
  const { t } = useI18n();
  const toast = useToast();
  const action = useIncrementAction();
  // The order held on the drawn card, carried in as entered (SRS-13-18).
  const [draft, setDraft] = useState<Partial<CaseRequest>>(
    order
      ? { orderNumber: order.reference, orderDate: order.orderDate, issuingAuthority: order.issuingOffice }
      : {},
  );
  // Review 17-8: after a submit the Revised Basic and the copy inputs are remounted empty.
  const [round, setRound] = useState(0);
  const [proposal, setProposal] = useState<Proposal | null>(null);
  // SRS-13-18 (US-GAP-10): the increment, promotion or pay-fixation order copy.
  const [documentType, setDocumentType] = useState(order?.documentType ?? '');
  const [copy, setCopy] = useState<File | null>(order?.files[0] ?? null);
  const types = useDocumentTypes();
  const [error, setError] = useState<unknown>(null);
  const set = (patch: Partial<CaseRequest>) => {
    setProposal(null);
    setDraft((d) => ({ ...d, ...patch }));
  };
  const request = () =>
    ({
      ...draft,
      eis: (draft.eis ?? '').trim(),
      observationReference: draft.observationReference?.trim(),
      orderNumber: (draft.orderNumber ?? '').trim(),
      issuingAuthority: (draft.issuingAuthority ?? '').trim(),
      reason: (draft.reason ?? '').trim(),
    }) as CaseRequest;

  async function run(work: () => Promise<unknown>, after: (v: unknown) => void) {
    setError(null);
    try {
      after(await action.mutateAsync(work));
    } catch (cause) {
      setError(cause);
    }
  }

  const ready = caseReady(draft);
  return (
    <Card title={t('i05.case')}>
      <Problem error={error} />
      <div className="form-grid">
        <label>
          {t('m01.eis')}{' '}
          <input maxLength={20} value={draft.eis ?? ''} onChange={(e) => set({ eis: e.target.value })} />
        </label>
        <label>
          {t('i05.caseType')}{' '}
          <select
            value={draft.caseType ?? ''}
            onChange={(e) =>
              set({
                caseType: e.target.value === '' ? undefined : (e.target.value as CaseRequest['caseType']),
              })
            }
          >
            <option value="">{t('common.choose')}</option>
            {CASE_TYPES.map((c) => (
              <option key={c} value={c}>
                {t(`i05.caseType.${c}`)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('i05.effective')}{' '}
          <input
            type="date"
            value={draft.effectiveDate ?? ''}
            onChange={(e) => set({ effectiveDate: e.target.value })}
          />
        </label>
        {entersBasic(draft.caseType) && (
          <label>
            {t('i05.revisedBasic')}{' '}
            <input
              key={`basic-${round}`}
              inputMode="decimal"
              maxLength={12}
              onChange={(e) =>
                set({ basic: e.target.value.trim() === '' ? undefined : Number(e.target.value) })
              }
            />
          </label>
        )}
        {draft.caseType === 'OBSERVATION_REFIXATION' && (
          <>
            <label>
              {t('i05.observationSource')}
              <Src s="ENT" />{' '}
              <select
                value={draft.observationSource ?? ''}
                onChange={(e) =>
                  set({
                    observationSource:
                      e.target.value === ''
                        ? undefined
                        : (e.target.value as CaseRequest['observationSource']),
                  })
                }
              >
                <option value="">{t('common.choose')}</option>
                {OBSERVATION_SOURCES.map((o) => (
                  <option key={o} value={o}>
                    {t(`i05.observation.${o}`)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('i05.observationReference')}
              <Src s="ENT" />{' '}
              <input
                maxLength={100}
                value={draft.observationReference ?? ''}
                onChange={(e) => set({ observationReference: e.target.value })}
              />
            </label>
          </>
        )}
        {draft.caseType === 'REVERSE_ENTRY' && <p className="fl-hint">{t('i05.reverseNote')}</p>}
        {draft.caseType === 'DATE_CORRECTION' && (
          <label>
            {t('i05.correctedDue')}{' '}
            <input
              type="date"
              value={draft.correctedDueDate ?? ''}
              onChange={(e) => set({ correctedDueDate: e.target.value })}
            />
          </label>
        )}
        <label>
          {t('i05.orderNumber')}{' '}
          <input
            // OPEN-QUESTION: CLR-78 - SRS l.3994 gives 60, DDL v4.0 order_number 100 (SRS kept)
            maxLength={60}
            value={draft.orderNumber ?? ''}
            onChange={(e) => set({ orderNumber: e.target.value })}
          />
        </label>
        <label>
          {t('i05.orderDate')}{' '}
          <input
            type="date"
            value={draft.orderDate ?? ''}
            onChange={(e) => set({ orderDate: e.target.value })}
          />
        </label>
        <label>
          {t('i05.authority')}{' '}
          <input
            // OPEN-QUESTION: CLR-78 - SRS l.3994 gives 100, DDL v4.0 issuing_authority 150 (SRS kept)
            maxLength={100}
            value={draft.issuingAuthority ?? ''}
            onChange={(e) => set({ issuingAuthority: e.target.value })}
          />
        </label>
        <label>
          {t('common.reason')}{' '}
          <TextAreaField
            maxLength={500}
            value={draft.reason ?? ''}
            onChange={(e) => set({ reason: e.target.value })}
          />
        </label>
        <label>
          {t('ord.type')}{' '}
          <select value={documentType} onChange={(e) => setDocumentType(e.target.value)}>
            <option value="">—</option>
            {(types.data ?? []).map((d) => (
              <option key={d.code} value={d.code}>
                {d.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('i05.orderCopy')}{' '}
          <input
            key={`copy-${round}`}
            type="file"
            accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png"
            onChange={(e) => setCopy(e.target.files?.[0] ?? null)}
          />
        </label>
      </div>
      {proposal && (
        <div className="kv" role="region" aria-label={t('i05.proposal')}>
          <span className="k">{t('i05.oldBasic')}</span>
          <span className="num">{proposal.oldBasic === null ? '—' : amount(proposal.oldBasic)}</span>
          <span className="k">{t('i05.revisedBasic')}</span>
          <span className="num">{proposal.revisedBasic === null ? '—' : amount(proposal.revisedBasic)}</span>
          {proposal.correctedDueDate && (
            <>
              <span className="k">{t('i05.correctedDue')}</span>
              <span>{date(proposal.correctedDueDate)}</span>
            </>
          )}
          {proposal.test && (
            <>
              <span className="k">{t('i05.test')}</span>
              <span>{proposal.test}</span>
            </>
          )}
        </div>
      )}
      {/* SRS-13-26: before save, the proposed Basic is shown with the member's Basic Pay history. */}
      {proposal && (
        <section aria-label={t('i05.memberHistory')}>
          <h4>{t('i05.memberHistory')}</h4>
          <HistoryTable eis={request().eis} />
        </section>
      )}
      <div className="toolbar">
        <button
          type="button"
          className="btn"
          disabled={!ready || action.isPending}
          title={!ready ? t('i05.why.case') : undefined}
          onClick={() =>
            void run(
              () => incrementCalls.preview(request()),
              (p) => setProposal(p as Proposal),
            )
          }
        >
          {t('action.i05.preview')}
        </button>
        <button
          type="button"
          className="btn pri"
          disabled={!ready || proposal === null || copy === null || documentType === '' || action.isPending}
          title={
            !ready
              ? t('i05.why.case')
              : proposal === null
                ? t('i05.why.preview')
                : copy === null || documentType === ''
                  ? t('i05.why.copy')
                  : undefined
          }
          onClick={() =>
            void run(
              () =>
                copy
                  ? incrementCalls.submitCaseWithOrder(request(), {
                      eis: request().eis,
                      documentType,
                      reference: request().orderNumber,
                      orderDate: request().orderDate,
                      issuingAuthority: request().issuingAuthority,
                      file: copy,
                    })
                  : incrementCalls.submitCase(request()),
              () => {
                toast(t('i05.caseSubmitted'));
                setDraft({});
                setRound((n) => n + 1);
                setProposal(null);
                setCopy(null);
                setDocumentType('');
              },
            )
          }
        >
          {t('common.submitForApproval')}
        </button>
      </div>
    </Card>
  );
}

/** SRS-13-26: the member's Basic history from joining, newest first, with the report. */
function HistoryCard() {
  const { t } = useI18n();
  const me = useMe();
  const [draft, setDraft] = useState('');
  const [eis, setEis] = useState('');
  return (
    <Card title={t('i05.history')}>
      <div className="toolbar">
        <label>
          {t('m01.eis')}{' '}
          <input maxLength={20} value={draft} onChange={(e) => setDraft(e.target.value.trim())} />
        </label>
        <button type="button" className="btn" disabled={draft === ''} onClick={() => setEis(draft)}>
          {t('i05.showHistory')}
        </button>
        {eis !== '' && can(me.data, 'I-05', 'EXPORT') && (
          <ExportButton
            href={incrementUrls.history(eis)}
            what={t('i05.history')}
            label={t('i05.historyReport')}
          />
        )}
      </div>
      {eis !== '' && <HistoryTable eis={eis} />}
    </Card>
  );
}

/** SRS-13-26: one member's Basic history, newest first, with the reason for each revision. */
function HistoryTable({ eis }: { eis: string }) {
  const { t } = useI18n();
  const history = useHistory(eis);
  return (
    <>
      <Problem error={history.error} />
      {history.data && (
        <table className="t" aria-label={t('i05.history')}>
          <thead>
            <tr>
              <th>
                {t('i05.effective')}
                <Src s="SYS" />
              </th>
              <th>
                {t('i05.event')}
                <Src s="SYS" />
              </th>
              <th>
                {t('i05.oldBasic')}
                <Src s="SYS" />
              </th>
              <th>
                {t('i05.basic')}
                <Src s="SYS" />
              </th>
              <th>
                {t('common.reason')}
                <Src s="ENT" />
              </th>
              <th>
                {t('i05.makerChecker')}
                <Src s="SYS" />
              </th>
            </tr>
          </thead>
          <tbody>
            {history.data.map((h) => (
              <tr key={`${h.effectiveFrom}-${h.sequence}`}>
                <td>
                  {date(h.effectiveFrom)}
                  {h.effectiveTo && <div className="sub2">{t('i05.until', [date(h.effectiveTo)])}</div>}
                </td>
                <td>
                  {t(`i05.event.${h.event}`)}
                  {h.corrected && <div className="sub2">{t('i05.corrected')}</div>}
                </td>
                <td className="num">{h.oldBasic === null ? '—' : amount(h.oldBasic)}</td>
                <td className="num">{amount(h.basic)}</td>
                <td>
                  {h.reason}
                  {h.order && <div className="sub2">{h.order}</div>}
                </td>
                <td>
                  {h.maker ?? '—'}
                  <div className="sub2">{h.by}</div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
