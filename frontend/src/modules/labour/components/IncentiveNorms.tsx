'use client';

import { useEffect, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, Empty, Problem, ServerPagination, Src, date, useToast, TextAreaField } from '@/shared/ui';
import type { NormProposal, NormSet, Slab } from '../api/labourApi';
import type { NormEntry } from '../business/labourScreenRules';
import { addSlab, normBlocked, removeSlab, srsSlabs } from '../business/labourRules';
import {
  normSetsPage,
  useMouCirculars,
  useNormDepots,
  useProposeNorms,
} from '../orchestration/labourQueries';

/**
 * M-06 Incentive Norms - Handling / Height / Lead (plan 20 phase 20d; SRS-3-6, SRS-3-12): the
 * depots' norm sets with their above-norm slab bands. The Rule Administrator at Headquarters
 * proposes a depot's set from its union MoU (bands contiguous, effective after the depot's latest
 * set); the Rule Approver approves it in the Approvals Inbox, and the depot's previous set ends the
 * day before. Depots, the Payroll Maker and Checker and Finance view.
 */
export function IncentiveNorms({
  entry = null,
  depots: drawnDepots,
}: {
  /** The values of the drawn New Depot Norm Set form: the proposal opens with them. */
  entry?: NormEntry | null;
  depots?: { id: number; code: string; name: string }[];
} = {}) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const today = businessToday();
  const depots = useNormDepots();
  const propose = useProposeNorms();
  const [depot, setDepot] = useState<number | null>(null);
  const [selected, setSelected] = useState<NormSet | null>(null);
  const [form, setForm] = useState<NormProposal | null>(null);
  // The MLSW as typed: a decimal being typed ("8.") is not yet a number.
  const [mlsw, setMlsw] = useState('');
  // The rates as typed, by band (`TABLE-order`): a band added or removed keeps the others' text.
  const [rates, setRates] = useState<Record<string, string>>({});
  const [error, setError] = useState<unknown>(null);
  const circulars = useMouCirculars(form !== null);
  const mayPropose = can(me.data, 'M-06', 'CREATE');
  const why = form ? normBlocked(form, today) : null;

  function start() {
    setMlsw('');
    setRates({});
    setError(null);
    setForm({
      depotLocationId: depot,
      effectiveFrom: today,
      handling: null,
      height: null,
      lead: null,
      minLabourNumber: null,
      minSlabWeight: null,
      mouReference: '',
      slabs: srsSlabs(),
      reason: '',
      mouCircularId: null,
    });
  }

  useEffect(() => {
    if (!entry) return;
    const wanted = entry.depot.trim().toUpperCase();
    const depotId =
      (drawnDepots ?? []).find(
        (d) => wanted !== '' && (d.code.toUpperCase() === wanted || d.name.toUpperCase().includes(wanted)),
      )?.id ?? null;
    start();
    setMlsw(entry.mlsw);
    setForm(
      (f) =>
        f && {
          ...f,
          depotLocationId: depotId,
          effectiveFrom: entry.effective || f.effectiveFrom,
          handling: num(entry.mln),
          height: num(entry.height),
          lead: num(entry.lead),
          minLabourNumber: num(entry.mln),
          minSlabWeight: num(entry.mlsw),
          mouReference: entry.mou,
        },
    );
    // Once, when the drawn form mounts the register.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function num(value: string): number | null {
    return value.trim() === '' || Number.isNaN(Number(value)) ? null : Number(value);
  }

  function setSlab(index: number, slab: Partial<Slab>) {
    if (!form) return;
    setForm({ ...form, slabs: form.slabs.map((s, i) => (i === index ? { ...s, ...slab } : s)) });
  }

  async function send(form: NormProposal) {
    setError(null);
    try {
      await propose.mutateAsync(form);
      toast(t('m06.proposed'));
      setForm(null);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <Card title={t('screen.M-06')}>
        <div className="toolbar">
          <label>
            {t('m02.depot')}{' '}
            <select
              value={depot ?? ''}
              onChange={(e) => setDepot(e.target.value ? Number(e.target.value) : null)}
            >
              <option value="">{t('m09.allDepots')}</option>
              {(depots.data ?? []).map((d) => (
                <option key={d.id} value={d.id}>
                  {d.code} - {d.name}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="btn primary"
            disabled={!mayPropose}
            title={mayPropose ? undefined : t('reason.ruleAdminOnly')}
            onClick={start}
          >
            {t('action.m06.propose')}
          </button>
        </div>
        <ServerPagination
          queryKey={['labour-norm-sets', depot]}
          fetchPage={normSetsPage(depot)}
          empty={<Empty text={t('m06.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('screen.M-06')}>
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
                    {t('m06.norms')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m06.mlnMlsw')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m02.status')}
                    <Src s="SYS" />
                  </th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((n) => (
                  <tr key={n.id}>
                    <td>
                      {n.depot}
                      <div className="sub2">{t('m06.version', [n.versionNo])}</div>
                    </td>
                    <td>
                      {date(n.effectiveFrom)} - {n.effectiveTo ? date(n.effectiveTo) : t('m12.open')}
                    </td>
                    <td>{t('m06.normValues', [n.handling, n.height, n.lead])}</td>
                    <td>
                      {n.minLabourNumber} / {n.minSlabWeight}
                    </td>
                    <td>
                      {t(`m12.state.${n.approvalState}`)}
                      {n.mouCircular && <div className="sub2">{t('m06.mouOf', [n.mouCircular])}</div>}
                    </td>
                    <td>
                      <button type="button" className="btn" onClick={() => setSelected(n)}>
                        {t('m06.slabs')}
                      </button>
                      <HistoryButton entity="labour_norm" recordKey={n.depotCode} label={n.depot} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </Card>
      {selected && (
        <Card title={t('m06.slabsOf', [selected.depot])}>
          <p className="sub2" aria-label={t('m06.boundary')}>
            {t('m06.boundary')}
            <Src s="RULE" />: {t('m06.boundaryText')}
          </p>
          <table className="t" aria-label={t('m06.slabsOf', [selected.depot])}>
            <thead>
              <tr>
                <th>
                  {t('m06.table')}
                  <Src s="RULE" />
                </th>
                <th>
                  {t('m06.bags')}
                  <Src s="RULE" />
                </th>
                <th>
                  {t('m06.rate')}
                  <Src s="RULE" />
                </th>
              </tr>
            </thead>
            <tbody>
              {selected.slabs.map((s) => (
                <tr key={`${s.table}-${s.order}`}>
                  <td>{t(`m06.band.${s.table}`)}</td>
                  <td>{s.to === null ? t('m06.bagsFrom', [s.from]) : `${s.from} - ${s.to}`}</td>
                  <td>{s.ratePercent}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
      {form && (
        <Card title={t('m06.new')}>
          <div className="form-grid">
            <label>
              {t('m02.depot')}
              <Src s="ENT" />
              <select
                value={form.depotLocationId ?? ''}
                onChange={(e) =>
                  setForm({ ...form, depotLocationId: e.target.value ? Number(e.target.value) : null })
                }
              >
                <option value="">—</option>
                {(depots.data ?? []).map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.code} - {d.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('m12.effective')}
              <Src s="ENT" />
              <input
                type="date"
                value={form.effectiveFrom}
                min={today}
                onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })}
              />
            </label>
            {(['handling', 'height', 'lead'] as const).map((k) => (
              <label key={k}>
                {t(`m06.${k}`)}
                <Src s="ENT" />
                <input
                  inputMode="numeric"
                  maxLength={6}
                  value={form[k] ?? ''}
                  onChange={(e) => setForm({ ...form, [k]: num(e.target.value) })}
                />
              </label>
            ))}
            <label>
              {t('m06.mln')}
              <Src s="ENT" />
              <input
                inputMode="numeric"
                maxLength={6}
                value={form.minLabourNumber ?? ''}
                onChange={(e) => setForm({ ...form, minLabourNumber: num(e.target.value) })}
              />
            </label>
            <label>
              {t('m06.mlsw')}
              <Src s="ENT" />
              <input
                inputMode="decimal"
                maxLength={9}
                value={mlsw}
                onChange={(e) => {
                  setMlsw(e.target.value);
                  setForm({ ...form, minSlabWeight: num(e.target.value) });
                }}
              />
            </label>
            <label>
              {t('m06.mouCircular')}
              <Src s="ENT" />
              <select
                value={form.mouCircularId ?? ''}
                onChange={(e) =>
                  setForm({ ...form, mouCircularId: e.target.value === '' ? null : Number(e.target.value) })
                }
              >
                <option value="">{t('common.choose')}</option>
                {(circulars.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.number}
                    {c.subject ? ` - ${c.subject}` : ''}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('m06.mou')}
              <Src s="ENT" />
              <input
                maxLength={100}
                value={form.mouReference}
                onChange={(e) => setForm({ ...form, mouReference: e.target.value })}
              />
            </label>
            <label>
              {t('m02.reason')}
              <Src s="ENT" />
              <TextAreaField
                maxLength={500}
                value={form.reason}
                onChange={(e) => setForm({ ...form, reason: e.target.value })}
              />
            </label>
          </div>
          {/* SRS-3-6 / M-06 l.4276: the band boundary is read-only here (Band 2 from 66 kg). */}
          <p className="sub2" aria-label={t('m06.boundary')}>
            {t('m06.boundary')}
            <Src s="RULE" />: {t('m06.boundaryText')}
          </p>
          <table className="t" aria-label={t('m06.slabs')}>
            <thead>
              <tr>
                <th>
                  {t('m06.table')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m06.from')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m06.to')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m06.rate')}
                  <Src s="ENT" />
                </th>
              </tr>
            </thead>
            <tbody>
              {form.slabs.map((s, i) => (
                <tr key={`${s.table}-${s.order}`}>
                  <td>{t(`m06.band.${s.table}`)}</td>
                  <td>
                    <input
                      aria-label={t('m06.fromOf', [t(`m06.band.${s.table}`), s.order])}
                      inputMode="numeric"
                      value={s.from}
                      onChange={(e) => setSlab(i, { from: num(e.target.value) ?? 0 })}
                    />
                  </td>
                  <td>
                    <input
                      aria-label={t('m06.toOf', [t(`m06.band.${s.table}`), s.order])}
                      inputMode="numeric"
                      value={s.to ?? ''}
                      onChange={(e) => setSlab(i, { to: num(e.target.value) })}
                    />
                  </td>
                  <td>
                    <input
                      maxLength={6}
                      aria-label={t('m06.rateOf', [t(`m06.band.${s.table}`), s.order])}
                      inputMode="decimal"
                      value={
                        rates[`${s.table}-${s.order}`] ?? (s.ratePercent < 0 ? '' : String(s.ratePercent))
                      }
                      onChange={(e) => {
                        setRates({ ...rates, [`${s.table}-${s.order}`]: e.target.value });
                        setSlab(i, { ratePercent: num(e.target.value) ?? -1 });
                      }}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {/* M-06 l.4276 (US-20-22 AC3): bands are rows; a band is added or removed at a table's end. */}
          <div className="toolbar">
            {(['BAND_1', 'BAND_2'] as const).map((table) => {
              const fewer = removeSlab(form.slabs, table);
              return (
                <span key={table}>
                  <button
                    type="button"
                    className="btn"
                    onClick={() => setForm({ ...form, slabs: addSlab(form.slabs, table) })}
                  >
                    {t('action.m06.addBand', [t(`m06.band.${table}`)])}
                  </button>{' '}
                  <button
                    type="button"
                    className="btn"
                    disabled={fewer === null}
                    title={fewer === null ? t('m06.why.oneBand') : undefined}
                    onClick={() => {
                      if (fewer) setForm({ ...form, slabs: fewer });
                    }}
                  >
                    {t('action.m06.removeBand', [t(`m06.band.${table}`)])}
                  </button>
                </span>
              );
            })}
          </div>
          <div className="toolbar">
            <button
              type="button"
              className="btn primary"
              disabled={why !== null || propose.isPending}
              title={why ? t(why) : undefined}
              onClick={() => void send(form)}
            >
              {t('action.m06.send')}
            </button>
            <button type="button" className="btn" onClick={() => setForm(null)}>
              {t('common.cancel')}
            </button>
          </div>
          {why && <p className="sub2">{t(why)}</p>}
        </Card>
      )}
      <Problem error={error} />
    </div>
  );
}
