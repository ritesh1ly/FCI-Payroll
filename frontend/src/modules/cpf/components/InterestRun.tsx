'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  Card,
  date,
  dateTime,
  Empty,
  Problem,
  ReasonDialog,
  ServerPagination,
  Src,
  Tile,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import type { NewRun } from '../api/cpfApi';
import { finYearOf, finYearRange } from '../business/interestRateRules';
import { FREEZE_PILL, freezeBlock, runBlock, runProblems } from '../business/interestRunRules';
import {
  useInterestRuns,
  cpfKeys,
  cpfPages,
  useRequestFreeze,
  useRunInterest,
} from '../orchestration/cpfQueries';

/**
 * C-12 CPF Interest Run - Calculate / Re-Run / Freeze (SRS-6-45, SRS-6-46, SRS C-12 field table) for
 * the zone of the user's office. Manager (CPF) runs and re-runs a financial year's interest for all
 * members or one, each run audited with its reason; the separate report lists the members not yet
 * calculated and why. Manager (CPF) requests the year-end freeze; AGM (CPF) decides it in the
 * Approvals Inbox without OTP; after it nothing runs for the year. The interest amount itself waits
 * for FCI's method (Q-072): until then every member is listed as not calculated.
 */
export function InterestRun({ start = null }: { start?: 'RUN' | 'FREEZE' | null } = {}) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const mayRun = can(me.data, 'C-12', 'CREATE');
  const mayFreeze = can(me.data, 'C-12', 'SUBMIT');
  const [year, setYear] = useState(() => finYearOf(businessToday()));
  const validYear = finYearRange(year) !== null;
  const summary = useInterestRuns(year, validYear);
  const [showReport, setShowReport] = useState(false);
  const runInterest = useRunInterest();
  const requestFreeze = useRequestFreeze();
  // A drawn button of the screen opens this section at the form it names.
  const [draft, setDraft] = useState<NewRun | null>(
    start === 'RUN' ? { finYear: year, scope: 'ALL_MEMBERS', eis: null, reason: '' } : null,
  );
  const [freezing, setFreezing] = useState(start === 'FREEZE');
  const [freezeError, setFreezeError] = useState<unknown>(null);
  const [error, setError] = useState<unknown>(null);
  const data = summary.data;
  const blockRun = runBlock(data);
  const blockFreeze = freezeBlock(data);
  const problems = draft ? runProblems(draft) : [];

  async function submitRun(form: NewRun) {
    setError(null);
    try {
      const run = await runInterest.mutateAsync({
        ...form,
        eis: form.scope === 'INDIVIDUAL' ? (form.eis?.trim() ?? null) : null,
        reason: form.reason.trim(),
      });
      toast(t('c12.ran', [run.run, run.members]));
      setDraft(null);
    } catch (cause) {
      setError(cause);
    }
  }

  async function submitFreeze(reason: string) {
    setFreezeError(null);
    try {
      await requestFreeze.mutateAsync({ finYear: year, reason });
      setFreezing(false);
      toast(t('c12.freezeRequested', [year]));
    } catch (cause) {
      // UI audit S46: the dialog stays open with the refusal; the typed reason is kept.
      setFreezeError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <div className="rf-field">
          <label htmlFor="c12-year">{t('c12.finYear')}</label>
          <input
            id="c12-year"
            maxLength={7}
            placeholder="2026-27"
            value={year}
            onChange={(e) => {
              setDraft(null);
              setShowReport(false);
              setYear(e.target.value.trim());
            }}
          />
        </div>
        <span className="sub2">{t('c12.hint')}</span>
        {mayRun && (
          <button
            type="button"
            className="btn pri"
            disabled={!validYear || blockRun !== null || draft !== null}
            title={blockRun ? t(blockRun) : undefined}
            onClick={() => {
              setError(null);
              setDraft({ finYear: year, scope: 'ALL_MEMBERS', eis: null, reason: '' });
            }}
          >
            {/* SRS l.2358: Calculate Interest, or Re-Run Interest once the year has a run. */}
            {t(data && data.runs.length > 0 ? 'action.reRunInterest' : 'action.runInterest')}
          </button>
        )}
        {mayFreeze && (
          <button
            type="button"
            className="btn"
            disabled={!validYear || blockFreeze !== null || requestFreeze.isPending}
            title={blockFreeze ? t(blockFreeze) : undefined}
            onClick={() => setFreezing(true)}
          >
            {t('action.freezeInterest')}
          </button>
        )}
      </div>
      {!validYear && <p className="fl-hint">{t('error.CPF-422-FIN-YEAR')}</p>}
      <Problem error={summary.error ?? (draft ? null : error)} />
      {data && (
        <div className="tiles">
          <Tile
            icon="🧮"
            label={t('c12.tile.calculated')}
            value={t('c12.ofMembers', [data.calculated, data.members])}
            sub={t('c12.zone', [data.zone])}
          />
          <Tile
            icon="⏳"
            label={t('c12.tile.notYet')}
            value={data.notYetCalculated}
            sub={t('c12.tile.notYetSub')}
            tone={data.notYetCalculated > 0 ? 'warn' : 'ok'}
          />
          <Tile icon="🔁" label={t('c12.tile.reRuns')} value={data.reRuns} sub={t('c12.tile.reRunsSub')} />
          <Tile
            icon="🔒"
            label={t('c12.tile.freeze')}
            value={
              <span className={`pill ${FREEZE_PILL[data.freezeStatus]}`}>
                {t(`c12.freeze.${data.freezeStatus}`)}
              </span>
            }
            sub={
              data.frozenBy && data.frozenAt
                ? t('c12.frozenBy', [data.frozenBy, dateTime(data.frozenAt)])
                : t('c12.tile.freezeSub')
            }
          />
        </div>
      )}
      <p className="fl-hint">{t('c12.methodOpen')}</p>
      {draft && (
        <Card title={t('c12.newRun', [draft.finYear])}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <fieldset className="rf-field">
              <legend>
                {t('c12.scope')}
                <Src s="ENT" />
              </legend>
              {(['ALL_MEMBERS', 'INDIVIDUAL'] as const).map((s) => (
                <label key={s}>
                  <input
                    type="radio"
                    name="c12-scope"
                    value={s}
                    checked={draft.scope === s}
                    onChange={() =>
                      setDraft({ ...draft, scope: s, eis: s === 'INDIVIDUAL' ? (draft.eis ?? '') : null })
                    }
                  />{' '}
                  {t(`c12.scope.${s}`)}
                </label>
              ))}
            </fieldset>
            {draft.scope === 'INDIVIDUAL' && (
              <div className="rf-field">
                <label htmlFor="c12-eis">{t('c12.eis')}</label>
                <input
                  id="c12-eis"
                  maxLength={20}
                  value={draft.eis ?? ''}
                  onChange={(e) => setDraft({ ...draft, eis: e.target.value })}
                />
              </div>
            )}
            <div className="rf-field span2">
              <label htmlFor="c12-reason">{t('c12.reason')}</label>
              <TextAreaField
                id="c12-reason"
                maxLength={500}
                value={draft.reason}
                onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
              />
            </div>
          </div>
          {problems.length > 0 && (
            <ul className="bd fl-list" aria-label={t('c12.problems')}>
              {problems.map((p) => (
                <li key={p}>{t(p)}</li>
              ))}
            </ul>
          )}
          <div className="rf-actbar">
            <button
              type="button"
              className="btn"
              onClick={() => {
                setDraft(null);
                setError(null);
              }}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={problems.length > 0 || runInterest.isPending}
              onClick={() => void submitRun(draft)}
            >
              {t('c12.runNow')}
            </button>
          </div>
        </Card>
      )}
      <Card title={t('c12.audit')}>
        {data && data.runs.length === 0 ? (
          <Empty text={t('c12.noRuns')} />
        ) : (
          <div className="wrap">
            <table className="t">
              <thead>
                <tr>
                  <th>
                    {t('c12.run')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c12.scope')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('c12.initiatedBy')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c12.when')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c12.members')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('c12.reason')}
                    <Src s="ENT" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {(data?.runs ?? []).map((r) => (
                  <tr key={r.run} data-reference={`${data?.finYear}|${r.run}`}>
                    <td>
                      <b>{r.run}</b>
                    </td>
                    <td>
                      {t(`c12.scope.${r.scope}`)}
                      {r.eis && <div className="sub2">{r.eis}</div>}
                      {r.scope === 'INDIVIDUAL' && !r.eis && (
                        <div className="sub2">{t('c12.memberHeld')}</div>
                      )}
                    </td>
                    <td>{r.initiatedBy}</td>
                    <td>{dateTime(r.initiatedAt)}</td>
                    <td>
                      {r.members}
                      <div className="sub2">
                        {t('c12.runCounts', [r.calculated, r.notCalculated, r.stopped])}
                      </div>
                    </td>
                    <td>{r.reason ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <Card title={t('c12.report')}>
        <p className="sub2">{t('c12.reportScope')}</p>
        {!showReport ? (
          <button type="button" className="btn" disabled={!validYear} onClick={() => setShowReport(true)}>
            {t('c12.showReport')}
          </button>
        ) : (
          <>
            <div className="wrap">
              <ServerPagination
                queryKey={cpfKeys.notCalculated(year)}
                fetchPage={cpfPages.notCalculated(year)}
                empty={<Empty text={t('c12.allCalculated')} />}
              >
                {(members) => (
                  <table className="t" aria-label={t('c12.report')}>
                    <thead>
                      <tr>
                        <th>
                          {t('c12.member')}
                          <Src s="HRMS" />
                        </th>
                        <th>
                          {t('c12.memberStatus')}
                          <Src s="SYS" />
                        </th>
                        <th>
                          {t('c12.interestEnd')}
                          <Src s="RULE" />
                        </th>
                        <th>
                          {t('c12.notCalculatedReason')}
                          <Src s="SYS" />
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {members.map((m) => (
                        <tr key={`${m.eis}|${m.memberStatus}`}>
                          <td>
                            {m.name} ({m.eis})
                          </td>
                          <td>{t(`c12.memberStatus.${m.memberStatus}`)}</td>
                          <td>
                            {m.interestEndDate ? date(m.interestEndDate) : '—'}
                            {m.interestStopped && <div className="sub2">{t('c12.stopped')}</div>}
                          </td>
                          <td>{t(`c12.reason.${m.reason}`)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </ServerPagination>
            </div>
          </>
        )}
      </Card>
      {freezing && (
        <ReasonDialog
          title={t('c12.freezeTitle', [year])}
          confirmLabel={t('action.freezeInterest')}
          onCancel={() => {
            setFreezing(false);
            setFreezeError(null);
          }}
          onConfirm={(reason) => void submitFreeze(reason)}
          error={freezeError}
          busy={requestFreeze.isPending}
        />
      )}
    </div>
  );
}
