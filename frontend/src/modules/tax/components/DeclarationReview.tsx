'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  Card,
  dateTime,
  Empty,
  Problem,
  ServerPagination,
  Src,
  useToast,
  TextAreaField,
  FinancialYearSelect,
} from '@/shared/ui';
import type { DeclarationRound, RoundStatus } from '../api/taxApi';
import { verificationProblems } from '../business/declarationRules';
import {
  declarationPages,
  taxKeys,
  useDeclaration,
  useDeclarationActions,
  proofUrl,
} from '../orchestration/taxQueries';

const STATUSES: readonly RoundStatus[] = ['SUBMITTED', 'VERIFIED', 'APPROVED', 'RETURNED', 'SUPERSEDED'];

function rupees(n: number): string {
  return `₹${n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/** The financial year of a day, April to March: 2026-27 (as the server writes fin_year). */
export function finYearOf(day: string): string {
  const [y, m] = day.split('-').map(Number) as [number, number];
  const start = m >= 4 ? y : y - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, '0')}`;
}

/**
 * I-07 Income-Tax Declarations & Regime (SRS-7-1, SRS-7-24; BR-TAX-001, BR-TAX-014; FRS V0.5 §14.3):
 * the rounds members of the user's offices submitted, a page at a time. The Payroll Maker verifies
 * each line against its proof (up to its allowed amount) and sends the round to the Payroll Checker,
 * or returns it with the reason; a frozen regime is reset only through the Checker. The Checker
 * decides in the Approvals Inbox (W-01).
 */
export function DeclarationReview() {
  const { t } = useI18n();
  const me = useMe();
  const [finYear, setFinYear] = useState(finYearOf(businessToday()));
  const [status, setStatus] = useState<string>('SUBMITTED');
  const [chosen, setChosen] = useState<number | null>(null);
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <label>
          {t('i07.finYear')}{' '}
          <FinancialYearSelect
            value={finYear}
            onChange={(y) => {
              setFinYear(y);
              setChosen(null);
            }}
          />
        </label>
        <label>
          {t('i07.status')}{' '}
          <select
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setChosen(null);
            }}
          >
            <option value="">{t('i07.allStatuses')}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`e01t.status.${s}`)}
              </option>
            ))}
          </select>
        </label>
        <span className="sub2">{t('i07.hint')}</span>
      </div>
      <Card title={t('screen.I-07')}>
        {/^\d{4}-\d{2}$/.test(finYear) ? (
          <ServerPagination
            queryKey={taxKeys.declarations(finYear, status)}
            fetchPage={declarationPages.list(finYear, status)}
            empty={<Empty text={t('i07.none')} />}
          >
            {(rows) => (
              <table className="t" aria-label={t('screen.I-07')}>
                <thead>
                  <tr>
                    <th>
                      {t('m01.eis')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i07.member')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('e01t.regime')}
                      <Src s="ESS" />
                    </th>
                    <th>
                      {t('i07.round')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('i07.status')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('i07.submitted')}
                      <Src s="SYS" />
                    </th>
                    <th aria-label={t('common.actions')} />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id} data-reference={`${r.eis}|${r.number}`}>
                      <td>{r.eis}</td>
                      <td>{r.name}</td>
                      <td>
                        {r.regime ? t(`e01t.regime.${r.regime}`) : '—'}
                        {r.regimeFrozen && <span className="pill">{t('e01t.frozen')}</span>}
                      </td>
                      <td>{r.number}</td>
                      <td>{t(`e01t.status.${r.status}`)}</td>
                      <td>{dateTime(r.submittedAt)}</td>
                      <td>
                        <button
                          type="button"
                          className="ia"
                          aria-label={t('i07.openOf', [r.eis ?? '', r.number])}
                          onClick={() => setChosen(r.id)}
                        >
                          {t('common.view')}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </ServerPagination>
        ) : (
          <p className="fl-hint">{t('i07.badYear')}</p>
        )}
      </Card>
      {chosen !== null && (
        <RoundReview
          key={chosen}
          id={chosen}
          mayVerify={can(me.data, 'I-07', 'SUBMIT')}
          mayReset={can(me.data, 'I-07', 'EDIT')}
          onDone={() => setChosen(null)}
        />
      )}
    </div>
  );
}

/** Why the round cannot be returned now, or nothing. */
function returnBlocked(
  submitted: boolean,
  reasonOk: boolean,
  t: (key: string) => string,
): string | undefined {
  if (!submitted) return t('i07.notSubmitted');
  if (!reasonOk) return t('i07.reasonFirst');
  return undefined;
}

/** One round: its lines with proofs, the amounts the Maker verifies, and the Maker's actions. */
function RoundReview({
  id,
  mayVerify,
  mayReset,
  onDone,
}: {
  id: number;
  mayVerify: boolean;
  mayReset: boolean;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const round = useDeclaration(id);
  const actions = useDeclarationActions();
  const [amounts, setAmounts] = useState<Record<number, string> | null>(null);
  const [reason, setReason] = useState('');
  // SRS-7-1 / SRS-G-2 (UI audit S14): the audited regime reset has its own reason, never the
  // return reason typed for the member.
  const [resetReason, setResetReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const r: DeclarationRound | undefined = round.data;
  if (round.isPending) return <p className="fl-hint">{t('common.loading')}</p>;
  if (!r) return <Problem error={round.error} />;
  const open = r.lines.filter((l) => !l.frozen);
  const shown = amounts ?? Object.fromEntries(open.map((l) => [l.id, String(l.allowed)]));
  const problems = verificationProblems(r.lines, shown);
  const submitted = r.status === 'SUBMITTED';
  const reasonOk = reason.trim() !== '';
  const resetReasonOk = resetReason.trim() !== '';
  const eis = r.eis ?? '';

  async function run(action: () => Promise<unknown>, done: string) {
    setError(null);
    try {
      await action();
      toast(t(done, [eis]));
      onDone();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('i07.roundOf', [eis, r.name ?? '', r.number])}>
      <Problem error={error} />
      {r.returnReason && <p className="pill p-warn">{t('e01t.returnedBecause', [r.returnReason])}</p>}
      <table className="t" aria-label={t('e01t.lines')}>
        <thead>
          <tr>
            <th>
              {t('e01t.kind')}
              <Src s="ESS" />
            </th>
            <th>
              {t('m18s.section')}
              <Src s="ESS" />
            </th>
            <th>
              {t('e01t.declared')}
              <Src s="ESS" />
            </th>
            <th>
              {t('e01t.allowed')}
              <Src s="CALC" />
            </th>
            <th>
              {t('e01t.proof')}
              <Src s="ESS" />
            </th>
            <th>
              {t('i07.verified')}
              <Src s="ENT" />
            </th>
          </tr>
        </thead>
        <tbody>
          {r.lines.map((l) => (
            <tr key={l.id}>
              <td>{t(`e01t.kind.${l.kind}`)}</td>
              <td>{l.sectionCode}</td>
              <td>{rupees(l.declared)}</td>
              <td>
                {rupees(l.allowed)}
                {l.capped && <div className="pill p-warn">{t('i07.capped')}</div>}
              </td>
              <td>
                {l.proofId === null ? (
                  <span className="sub2">{t('i07.noProof')}</span>
                ) : (
                  <a href={proofUrl(r.id, l.proofId)}>{t('e01t.proof')}</a>
                )}
              </td>
              <td>
                {l.frozen || !submitted || !mayVerify ? (
                  <>
                    {l.verified === null ? '—' : rupees(l.verified)}
                    {l.frozen && <span className="pill">{t('e01t.frozen')}</span>}
                  </>
                ) : (
                  <input
                    aria-label={t('i07.verifiedOf', [l.sectionCode])}
                    inputMode="decimal"
                    maxLength={15}
                    value={shown[l.id] ?? ''}
                    onChange={(e) => setAmounts({ ...shown, [l.id]: e.target.value })}
                  />
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {mayVerify && submitted && (
        <>
          {problems.length > 0 && (
            <ul className="bd fl-list" aria-label={t('m18s.problems')}>
              {problems.map((p) => (
                <li key={p}>{t(p)}</li>
              ))}
            </ul>
          )}
          <div className="rf-field span2">
            <label htmlFor="i07-reason">{t('i07.returnReason')}</label>
            <TextAreaField
              id="i07-reason"
              maxLength={1000}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </div>
        </>
      )}
      {mayReset && r.regimeFrozen && (
        <div className="rf-field span2">
          <label htmlFor="i07-reset-reason">{t('i07.resetReason')}</label>
          <TextAreaField
            id="i07-reset-reason"
            maxLength={1000}
            value={resetReason}
            onChange={(e) => setResetReason(e.target.value)}
          />
        </div>
      )}
      <div className="rf-actbar">
        <button type="button" className="btn" onClick={onDone}>
          {t('common.close')}
        </button>
        {mayVerify && (
          <>
            <button
              type="button"
              className="btn"
              disabled={!submitted || !reasonOk || actions.returnToMember.isPending}
              title={returnBlocked(submitted, reasonOk, t)}
              onClick={() =>
                void run(
                  () => actions.returnToMember.mutateAsync({ id: r.id, reason: reason.trim() }),
                  'i07.returned',
                )
              }
            >
              {t('action.returnToMember')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={!submitted || problems.length > 0 || actions.verify.isPending}
              title={submitted ? undefined : t('i07.notSubmitted')}
              onClick={() =>
                void run(
                  () =>
                    actions.verify.mutateAsync({
                      id: r.id,
                      lines: open.map((l) => ({ lineId: l.id, verified: (shown[l.id] ?? '').trim() })),
                    }),
                  'i07.verifiedSent',
                )
              }
            >
              {t('action.verifyDeclaration')}
            </button>
          </>
        )}
        {mayReset && r.regimeFrozen && (
          <button
            type="button"
            className="btn"
            disabled={!resetReasonOk || actions.reset.isPending}
            title={resetReasonOk ? undefined : t('i07.reasonFirst')}
            onClick={() =>
              void run(
                () =>
                  actions.reset.mutateAsync({
                    employeeId: r.employeeId,
                    finYear: r.finYear,
                    reason: resetReason.trim(),
                  }),
                'i07.resetSent',
              )
            }
          >
            {t('action.resetRegime')}
          </button>
        )}
      </div>
    </Card>
  );
}
