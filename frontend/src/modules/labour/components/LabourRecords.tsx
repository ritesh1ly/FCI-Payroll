'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { businessToday } from '@/shared/time';
import { Card, Problem, Src, useToast, TextAreaField } from '@/shared/ui';
import type { LabourRow, NamedPerson, ReinstatementEntry } from '../api/labourApi';
import { nominationBlocked, reinstatementBlocked } from '../business/labourRules';
import { useNominate, useNominations, useReinstate } from '../orchestration/labourQueries';

/**
 * M-02 reinstatement (plan 22 phase 22c; NWNP Reinstate Details, D22-7): Establishment / HR
 * records a separated member's reinstatement with the order attached; the Payroll Checker approves,
 * the earlier records stay and the gang is joined on M-09.
 */
export function ReinstatementCard({ row, onClose }: { row: LabourRow; onClose: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const today = businessToday();
  const reinstate = useReinstate();
  const [entry, setEntry] = useState<ReinstatementEntry>({
    date: today,
    orderReference: '',
    orderDate: '',
    issuingAuthority: '',
    reason: '',
    document: null,
  });
  const [error, setError] = useState<unknown>(null);
  const why = reinstatementBlocked(entry, today);

  async function send() {
    setError(null);
    try {
      await reinstate.mutateAsync({ employeeId: row.employeeId, entry });
      toast(t('m02.reinstateSent'));
      onClose();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('m02.reinstatementOf', [row.labourCode])}>
      <div className="form-grid">
        <label>
          {t('m02.reinstateDate')}
          <Src s="ENT" />
          <input
            type="date"
            value={entry.date}
            onChange={(e) => setEntry({ ...entry, date: e.target.value })}
          />
        </label>
        <label>
          {t('m02.orderReference')}
          <Src s="ENT" />
          <input
            maxLength={60}
            value={entry.orderReference}
            onChange={(e) => setEntry({ ...entry, orderReference: e.target.value })}
          />
        </label>
        <label>
          {t('m02.orderDate')}
          <Src s="ENT" />
          <input
            type="date"
            max={today}
            value={entry.orderDate}
            onChange={(e) => setEntry({ ...entry, orderDate: e.target.value })}
          />
        </label>
        <label>
          {t('m02.issuingAuthority')}
          <Src s="ENT" />
          <input
            maxLength={150}
            value={entry.issuingAuthority}
            onChange={(e) => setEntry({ ...entry, issuingAuthority: e.target.value })}
          />
        </label>
        <label>
          {t('m02.reinstateOrder')}
          <Src s="ENT" />
          <input
            type="file"
            accept="application/pdf"
            onChange={(e) => setEntry({ ...entry, document: e.target.files?.[0] ?? null })}
          />
        </label>
        <label>
          {t('m09.changeReason')}
          <Src s="ENT" />
          <TextAreaField
            maxLength={500}
            value={entry.reason}
            onChange={(e) => setEntry({ ...entry, reason: e.target.value })}
          />
        </label>
      </div>
      <div className="toolbar">
        <button
          type="button"
          className="btn primary"
          disabled={why !== null || reinstate.isPending}
          title={why ? t(why) : undefined}
          onClick={() => void send()}
        >
          {t('action.m02.send')}
        </button>
        <button type="button" className="btn" onClick={onClose}>
          {t('common.cancel')}
        </button>
      </div>
      <Problem error={error} />
    </Card>
  );
}

interface PersonDraft {
  name: string;
  relationship: string;
  birthDate: string;
  share: string;
  nominee: boolean;
}

const EMPTY: PersonDraft = { name: '', relationship: '', birthDate: '', share: '', nominee: false };

function toPerson(p: PersonDraft, nominee: boolean): NamedPerson {
  return {
    name: p.name.trim(),
    relationship: p.relationship,
    birthDate: p.birthDate === '' ? null : p.birthDate,
    share: nominee ? (p.share.trim() === '' ? null : Number(p.share)) : null,
    nominee: nominee || p.nominee,
  };
}

/**
 * M-02 nominees and dependents (plan 22 phase 22c; Nominee &amp; Dependent Details, BR-FNF-014,
 * D22-8): the ones in force, and a new set recorded by Establishment / HR with the nominees' shares
 * adding to 100; the Payroll Checker approves and the earlier ones end, kept.
 */
export function NominationsCard({
  row,
  mayEdit,
  onClose,
}: {
  row: LabourRow;
  mayEdit: boolean;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const ref = useReference();
  const today = businessToday();
  const current = useNominations(row.employeeId);
  const nominate = useNominate();
  const [nominees, setNominees] = useState<PersonDraft[] | null>(null);
  const [dependents, setDependents] = useState<PersonDraft[]>([]);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const relationships = ref.options('NOMINEE_RELATIONSHIP');
  const editing = nominees !== null;
  const why = editing
    ? nominationBlocked(
        nominees.map((p) => toPerson(p, true)),
        dependents.map((p) => toPerson(p, false)),
        reason,
        today,
      )
    : null;

  function start() {
    setError(null);
    setNominees([{ ...EMPTY }]);
    setDependents([]);
    setReason('');
  }

  async function send() {
    if (!nominees) return;
    setError(null);
    try {
      await nominate.mutateAsync({
        employeeId: row.employeeId,
        body: {
          nominees: nominees.map((p) => toPerson(p, true)),
          dependents: dependents.map((p) => toPerson(p, false)),
          reason: reason.trim(),
        },
      });
      toast(t('m02.nominationSent'));
      setNominees(null);
    } catch (cause) {
      setError(cause);
    }
  }

  function personRow(
    p: PersonDraft,
    i: number,
    kind: 'nominee' | 'dependent',
    set: (i: number, p: PersonDraft) => void,
    remove: (i: number) => void,
  ) {
    const n = i + 1;
    return (
      <div key={`${kind}-${i}`} className="toolbar">
        <label>
          {t(`m02.${kind}Name`, [n])}
          <input maxLength={150} value={p.name} onChange={(e) => set(i, { ...p, name: e.target.value })} />
        </label>
        <label>
          {t(`m02.${kind}Relationship`, [n])}
          <select value={p.relationship} onChange={(e) => set(i, { ...p, relationship: e.target.value })}>
            <option value="">—</option>
            {relationships.map((r) => (
              <option key={r.value} value={r.value}>
                {r.value} - {r.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t(`m02.${kind}Birth`, [n])}
          <input
            type="date"
            max={today}
            value={p.birthDate}
            onChange={(e) => set(i, { ...p, birthDate: e.target.value })}
          />
        </label>
        {kind === 'nominee' && (
          <label>
            {t('m02.nomineeShare', [n])}
            <input
              maxLength={6}
              inputMode="decimal"
              value={p.share}
              onChange={(e) => set(i, { ...p, share: e.target.value })}
            />
          </label>
        )}
        <button type="button" className="btn" onClick={() => remove(i)}>
          {t('action.m02.removePerson')}
        </button>
      </div>
    );
  }

  const data = current.data;
  return (
    <Card title={t('m02.nominationsOf', [row.labourCode])}>
      {data && (
        <table className="t" aria-label={t('m02.nominationsOf', [row.labourCode])}>
          <thead>
            <tr>
              <th>
                {t('m02.personName')}
                <Src s="ENT" />
              </th>
              <th>
                {t('m02.relationship')}
                <Src s="ENT" />
              </th>
              <th>
                {t('m02.share')}
                <Src s="ENT" />
              </th>
            </tr>
          </thead>
          <tbody>
            {[...data.nominees, ...data.dependents].map((p, i) => (
              <tr key={`${p.name}-${i}`}>
                <td>
                  {p.name}
                  {!p.share && (
                    <div className="sub2">{t(p.nominee ? 'm02.dependentNominee' : 'm02.dependent')}</div>
                  )}
                </td>
                <td>
                  {p.relationship} - {ref.label('NOMINEE_RELATIONSHIP', p.relationship)}
                </td>
                <td>{p.share === null ? '—' : `${p.share}%`}</td>
              </tr>
            ))}
            {data.nominees.length + data.dependents.length === 0 && (
              <tr>
                <td colSpan={3}>{t('m02.noNominees')}</td>
              </tr>
            )}
          </tbody>
        </table>
      )}
      {data?.pending && <p className="sub2">{t('m02.nominationPending')}</p>}
      {!editing && (
        <div className="toolbar">
          <button
            type="button"
            className="btn primary"
            disabled={!mayEdit || data?.pending === true}
            title={!mayEdit ? t('reason.hrOnly') : data?.pending ? t('m02.nominationPending') : undefined}
            onClick={start}
          >
            {t('action.m02.nominees')}
          </button>
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
      )}
      {editing && (
        <>
          <h4>{t('m02.nominees')}</h4>
          {nominees.map((p, i) =>
            personRow(
              p,
              i,
              'nominee',
              (j, q) => setNominees(nominees.map((x, k) => (k === j ? q : x))),
              (j) => setNominees(nominees.filter((_, k) => k !== j)),
            ),
          )}
          <button type="button" className="btn" onClick={() => setNominees([...nominees, { ...EMPTY }])}>
            {t('action.m02.addNominee')}
          </button>
          <h4>{t('m02.dependents')}</h4>
          {dependents.map((p, i) =>
            personRow(
              p,
              i,
              'dependent',
              (j, q) => setDependents(dependents.map((x, k) => (k === j ? q : x))),
              (j) => setDependents(dependents.filter((_, k) => k !== j)),
            ),
          )}
          <button type="button" className="btn" onClick={() => setDependents([...dependents, { ...EMPTY }])}>
            {t('action.m02.addDependent')}
          </button>
          <label>
            {t('m09.changeReason')}
            <Src s="ENT" />
            <TextAreaField maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
          </label>
          <div className="toolbar">
            <button
              type="button"
              className="btn primary"
              disabled={why !== null || nominate.isPending}
              title={why ? t(why) : undefined}
              onClick={() => void send()}
            >
              {t('action.m02.send')}
            </button>
            <button type="button" className="btn" onClick={() => setNominees(null)}>
              {t('common.cancel')}
            </button>
          </div>
          {why && <p className="sub2">{t(why)}</p>}
        </>
      )}
      <Problem error={error ?? current.error} />
    </Card>
  );
}
