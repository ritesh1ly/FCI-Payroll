'use client';

import { type ReactNode, useEffect, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, Empty, Problem, ServerPagination, Src, date, useToast, TextAreaField } from '@/shared/ui';
import { type NwnpDraft, nwnpBlocked, toNwnpRequest } from '../business/labourRules';
import { nwnpRateSetsPage, useNwnpRateOptions, useProposeNwnpRateSet } from '../orchestration/labourQueries';

/** The values the drawn New NWNP ASOR Rate Set form carries to the full proposal. */
export interface NwnpPrefill {
  effective: string;
  piece: string;
  sardar: string;
  mandal: string;
  handling: string;
  ancillary: string;
}

/**
 * M-05 Labour Rate Tables ▸ NWNP (plan 22 phase 22b; SRS-5-1, SRS-5-2, SRS-5-3; D22-5, D22-6): a
 * depot's ASOR or SOR rate sets, every NWNP operation's Piece of Rate held against its Unit of
 * Measure and every NWNP designation's MGW, each mandatory. The Rule Administrator proposes a set
 * dated after the depot's latest one; the Rule Approver approves it and the set before ends the day
 * before.
 */
export function NwnpRates({
  typeSelect,
  prefill = null,
}: {
  typeSelect: ReactNode;
  prefill?: NwnpPrefill | null;
}) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const today = businessToday();
  const mayPropose = can(me.data, 'M-05', 'CREATE');
  const [draft, setDraft] = useState<NwnpDraft | null>(null);
  const options = useNwnpRateOptions(true);
  const propose = useProposeNwnpRateSet();
  const [error, setError] = useState<unknown>(null);
  const o = options.data;
  const why = draft && o ? nwnpBlocked(draft, o) : null;

  function start() {
    setError(null);
    setDraft({
      depotLocationId: null,
      version: 'ASOR',
      effectiveFrom: today,
      rates: {},
      units: {},
      mgw: {},
      reason: '',
    });
  }

  async function send() {
    if (!draft || !o) return;
    setError(null);
    try {
      await propose.mutateAsync(toNwnpRequest(draft, o));
      toast(t('m05.proposed'));
      setDraft(null);
    } catch (cause) {
      setError(cause);
    }
  }

  // The drawn ASOR form: its one piece rate on every operation and its MGW by the designation's gang role;
  // the depot and the units are chosen here (SRS-5-2: they are not on the drawing).
  useEffect(() => {
    if (!prefill || !o) return;
    const byRole: Record<string, string> = {
      SARDAR: prefill.sardar,
      MANDAL: prefill.mandal,
      HANDLING: prefill.handling,
      ANCILLARY: prefill.ancillary,
    };
    setDraft({
      depotLocationId: null,
      version: 'ASOR',
      effectiveFrom: prefill.effective,
      rates: Object.fromEntries(o.operations.map((op) => [op.code, prefill.piece])),
      units: {},
      mgw: Object.fromEntries(o.designations.map((d) => [d.id, byRole[d.gangRole ?? ''] ?? ''])),
      reason: '',
    });
  }, [prefill, o]);

  return (
    <div className="wireframe-body-container">
      <Card title={t('screen.M-05')}>
        <div className="toolbar">
          {typeSelect}
          <button
            type="button"
            className="btn primary"
            disabled={!mayPropose}
            title={mayPropose ? undefined : t('reason.ruleAdminOnly')}
            onClick={start}
          >
            {t('action.m05.propose')}
          </button>
          {/* G5-53 (DoD 4): the (Basic Wage + VDA) x 26 Minimum Wage by location type (SRS-5-11, SRS-5-1)
              waits for FCI (PENDING NWNP-22b-1): shown disabled with the reason. */}
          <button type="button" className="btn" disabled title={t('m05.why.nwnpMinWage')}>
            {t('action.m05.nwnpMinWage')}
          </button>
        </div>
        <ServerPagination
          queryKey={['labour-nwnp-rate-sets']}
          fetchPage={nwnpRateSetsPage()}
          empty={<Empty text={t('m05.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('screen.M-05')}>
              <thead>
                <tr>
                  <th>
                    {t('m02.depot')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m06.period')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m05.pieceRates')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m05.mgw')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m02.status')}
                    <Src s="SYS" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td>
                      {r.depot}
                      <div className="sub2">
                        {t(`m05.version.${r.version}`)} · {t('m06.version', [r.versionNo])}
                      </div>
                    </td>
                    <td>
                      {date(r.effectiveFrom)} - {r.effectiveTo ? date(r.effectiveTo) : t('m12.open')}
                    </td>
                    <td>
                      {r.lines.map((l) => (
                        <div key={l.operationCode}>
                          {l.operationCode} - {l.operation}: ₹ {l.pieceOfRate} ({l.unitOfMeasure}
                          {l.unitLabel ? ` - ${l.unitLabel}` : ''})
                        </div>
                      ))}
                    </td>
                    <td>
                      {r.mgw.map((m) => (
                        <div key={m.designationId}>
                          {m.designation}: ₹ {m.mgw}
                        </div>
                      ))}
                    </td>
                    <td>{t(`m12.state.${r.approvalState}`)}</td>
                    <td>
                      <HistoryButton entity="nwnp_rate_set" recordKey={r.code} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </Card>
      {draft && o && (
        <Card title={t('m05.new', [t('labour.type.NWNP_LABOUR')])}>
          <div className="form-grid">
            <label>
              {t('m02.depot')}
              <Src s="ENT" />
              <select
                value={draft.depotLocationId ?? ''}
                onChange={(e) =>
                  setDraft({ ...draft, depotLocationId: e.target.value ? Number(e.target.value) : null })
                }
              >
                <option value="">—</option>
                {o.depots.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.code} - {d.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('m05.rateVersion')}
              <Src s="ENT" />
              <select value={draft.version} onChange={(e) => setDraft({ ...draft, version: e.target.value })}>
                <option value="ASOR">{t('m05.version.ASOR')}</option>
                <option value="SOR">{t('m05.version.SOR')}</option>
              </select>
            </label>
            <label>
              {t('m12.effective')}
              <Src s="ENT" />
              <input
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
              />
            </label>
            {o.operations.map((op) => (
              <div key={op.code} className="toolbar">
                <label>
                  {t('m05.pieceOfRateOf', [`${op.code} - ${op.name}`])}
                  <Src s="ENT" />
                  <input
                    maxLength={13}
                    inputMode="decimal"
                    value={draft.rates[op.code] ?? ''}
                    onChange={(e) =>
                      setDraft({ ...draft, rates: { ...draft.rates, [op.code]: e.target.value } })
                    }
                  />
                </label>
                <label>
                  {t('m05.unitOf', [op.code])}
                  <Src s="ENT" />
                  <select
                    value={draft.units[op.code] ?? ''}
                    onChange={(e) =>
                      setDraft({ ...draft, units: { ...draft.units, [op.code]: e.target.value } })
                    }
                  >
                    <option value="">—</option>
                    {o.unitsOfMeasure.map((u) => (
                      <option key={u.code} value={u.code}>
                        {u.code} - {u.label}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
            ))}
            {o.designations.map((x) => (
              <label key={x.id}>
                {t('m05.mgwOf', [`${x.code} - ${x.name}`])}
                <Src s="ENT" />
                <input
                  maxLength={13}
                  inputMode="decimal"
                  value={draft.mgw[x.id] ?? ''}
                  onChange={(e) => setDraft({ ...draft, mgw: { ...draft.mgw, [x.id]: e.target.value } })}
                />
              </label>
            ))}
            <label>
              {t('m02.reason')}
              <Src s="ENT" />
              <TextAreaField
                maxLength={500}
                value={draft.reason}
                onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
              />
            </label>
          </div>
          <div className="toolbar">
            <button
              type="button"
              className="btn primary"
              disabled={why !== null || propose.isPending}
              title={why ? t(why) : undefined}
              onClick={() => void send()}
            >
              {t('action.m05.send')}
            </button>
            <button type="button" className="btn" onClick={() => setDraft(null)}>
              {t('common.cancel')}
            </button>
          </div>
          {why && <p className="sub2">{t(why)}</p>}
        </Card>
      )}
      <Problem error={error ?? options.error} />
    </div>
  );
}
