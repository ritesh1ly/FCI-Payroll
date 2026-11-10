'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
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
  Th,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import type { Proposed, VersionRequest } from '../api/incrementApi';
import {
  badStageRow,
  newScaleReady,
  SCALE_TYPES,
  stagesOfRows,
  versionReady,
} from '../business/incrementRules';
import {
  incrementCalls,
  incrementKeys,
  incrementPages,
  useIncrementAction,
  useOrders,
  useScaleVersions,
  useStages,
  useWageRevisions,
} from '../orchestration/incrementQueries';

/**
 * M-03 Pay Scales &amp; Grades (plan 17 phase 17b; SRS M-03, SRS-13-23). Each scale with the version
 * in force - minimum, maximum, stagnation amount, the number of increment stages - and its stages
 * grid. The Rule Administrator at Headquarters proposes a new version on an Active revision circular
 * (M-25); the Payroll Checker at Headquarters approves in W-01; until then the version is not in
 * force. The members above a proposed maximum are listed for review. A new scale (SC-17-0030: a
 * unique code, a name and its type) is defined with its first version and is active once that
 * version is approved.
 */
export function PayScales({ seed = null }: { seed?: ScaleSeed | null } = {}) {
  const { t } = useI18n();
  const me = useMe();
  const [selected, setSelected] = useState<string | null>(null);
  const [defining, setDefining] = useState(seed !== null);
  const mayPropose = can(me.data, 'M-03', 'CREATE');
  return (
    <div className="wireframe-body-container">
      <p className="sub2">{t('m03.note')}</p>
      <Card
        title={t('screen.M-03')}
        actions={
          mayPropose ? (
            <button
              type="button"
              className="btn sm pri"
              onClick={() => {
                setSelected(null);
                setDefining(true);
              }}
            >
              {t('m03.newScale')}
            </button>
          ) : undefined
        }
      >
        <ServerPagination
          queryKey={incrementKeys.scales}
          fetchPage={incrementPages.scales}
          empty={<Empty text={t('m03.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('screen.M-03')}>
              <thead>
                <tr>
                  <th>
                    {t('m03.scale')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m03.min')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m03.max')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m03.stagnation')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m03.from')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m03.stages')}
                    <Src s="ENT" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.bandId} data-code={r.code}>
                    <td>
                      <b>{r.code}</b> - {r.name}
                      {r.scaleType && <div className="sub2">{r.scaleType}</div>}
                      {r.pending && <div className="sub2">{t('m03.pending')}</div>}
                      {!r.active && <div className="sub2">{t('m03.inactive')}</div>}
                    </td>
                    <td className="num">{r.min === null ? '—' : amount(r.min)}</td>
                    <td className="num">{r.max === null ? '—' : amount(r.max)}</td>
                    <td className="num">{r.stagnationAmount === null ? '—' : amount(r.stagnationAmount)}</td>
                    <td>{r.effectiveFrom ? date(r.effectiveFrom) : '—'}</td>
                    <td className="num">{r.stages}</td>
                    <td>
                      <button
                        type="button"
                        className="ia"
                        onClick={() => {
                          setDefining(false);
                          setSelected(r.code);
                        }}
                      >
                        {t('common.view')}
                      </button>
                      <HistoryButton
                        entity="pay_scale_band_version"
                        recordKey={r.code}
                        label={`${r.code} - ${r.name}`}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </Card>
      {selected && (
        <ScaleCard key={selected} code={selected} mayPropose={mayPropose} onClose={() => setSelected(null)} />
      )}
      {defining && mayPropose && (
        <ScaleCard code={null} mayPropose seed={seed} onClose={() => setDefining(false)} />
      )}
    </div>
  );
}

/** What the drawn form of M-03 passes to the full form (WF-1300: the SRS fields it omits are added there). */
export interface ScaleSeed {
  code: string;
  scaleType: string;
  version: Partial<VersionRequest>;
}

/** The version form of a scale; with no code, the definition of a new scale (SC-17-0030). */
function ScaleCard({
  code,
  mayPropose,
  seed = null,
  onClose,
}: {
  code: string | null;
  mayPropose: boolean;
  /** What the drawn New Pay Scale Version form carries here; the SRS fields it omits are added here. */
  seed?: ScaleSeed | null;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const stages = useStages(code);
  const versions = useScaleVersions(code);
  const [header, setHeader] = useState({
    code: seed?.code ?? '',
    name: '',
    scaleType: seed?.scaleType ?? '',
  });
  const orders = useOrders(mayPropose);
  const revisions = useWageRevisions(mayPropose);
  const action = useIncrementAction();
  const [draft, setDraft] = useState<Partial<VersionRequest>>(seed?.version ?? {});
  // Review 17-15: a proposal made clears the form (the bound inputs are remounted empty).
  const [round, setRound] = useState(0);
  const [stageRows, setStageRows] = useState<string[]>(['']);
  const [proposed, setProposed] = useState<Proposed | null>(null);
  const [error, setError] = useState<unknown>(null);
  const set = (patch: Partial<VersionRequest>) => setDraft((d) => ({ ...d, ...patch }));
  const num = (v: string) => (v.trim() === '' ? undefined : Number(v));
  const request = { ...draft, stages: stagesOfRows(stageRows) };
  const badRow = badStageRow(stageRows);
  const headerReady = code !== null || newScaleReady(header.code, header.name, header.scaleType);
  const ready = versionReady(request) && headerReady && badRow === null;

  async function propose() {
    setError(null);
    const version = { ...(request as VersionRequest), reason: (draft.reason ?? '').trim() };
    try {
      const p = (await action.mutateAsync(() =>
        code === null
          ? incrementCalls.define({
              code: header.code.trim(),
              name: header.name.trim(),
              scaleType: header.scaleType as 'IDA' | 'CDA',
              version,
            })
          : incrementCalls.propose(code, version),
      )) as Proposed;
      setProposed(p);
      setDraft({});
      setStageRows(['']);
      setRound((n) => n + 1);
      toast(t(code === null ? 'm03.defined' : 'm03.proposed'));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card
      title={code === null ? t('m03.newScaleTitle') : t('m03.scaleTitle', [code])}
      actions={
        <button type="button" className="btn sm" onClick={onClose}>
          {t('common.close')}
        </button>
      }
    >
      <Problem error={stages.error ?? error} />
      {code === null && (
        <div className="form-grid">
          <label>
            {t('m03.code')}
            <Src s="ENT" />{' '}
            <input
              maxLength={10}
              value={header.code}
              onChange={(e) => setHeader((h) => ({ ...h, code: e.target.value }))}
            />
          </label>
          <label>
            {t('m03.name')}
            <Src s="ENT" />{' '}
            <input
              maxLength={100}
              value={header.name}
              onChange={(e) => setHeader((h) => ({ ...h, name: e.target.value }))}
            />
          </label>
          <label>
            {t('m03.type')}
            <Src s="ENT" />{' '}
            <select
              value={header.scaleType}
              onChange={(e) => setHeader((h) => ({ ...h, scaleType: e.target.value }))}
            >
              <option value="">{t('common.choose')}</option>
              {SCALE_TYPES.map((type) => (
                <option key={type} value={type}>
                  {type}
                </option>
              ))}
            </select>
          </label>
        </div>
      )}
      {versions.data && versions.data.length > 0 && (
        <table className="t" aria-label={t('m03.versions')}>
          <thead>
            <tr>
              <Th label={t('m03.versionNo')} s="SYS" />
              <Th label={t('m03.min')} s="ENT" num />
              <Th label={t('m03.max')} s="ENT" num />
              <Th label={t('m03.from')} s="ENT" />
              <Th label={t('m03.effectiveTo')} s="SYS" />
            </tr>
          </thead>
          <tbody>
            {versions.data.map((v) => (
              <tr key={`${v.versionNo}-${v.effectiveFrom}`}>
                <td>{v.versionNo}</td>
                <td className="num">{amount(v.min)}</td>
                <td className="num">{amount(v.max)}</td>
                <td>{date(v.effectiveFrom)}</td>
                <td>{v.effectiveTo ? date(v.effectiveTo) : t('m03.inForce')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {code !== null && <h4>{t('m03.stageGrid')}</h4>}
      {stages.data && stages.data.length === 0 && <p className="sub2">{t('m03.noStages')}</p>}
      {stages.data && stages.data.length > 0 && (
        <ol className="fl-list" aria-label={t('m03.stageGrid')}>
          {stages.data.map((s) => (
            <li key={s}>{amount(s)}</li>
          ))}
        </ol>
      )}
      {mayPropose && (
        <>
          {code !== null && <h4>{t('action.newVersion')}</h4>}
          <div className="form-grid">
            <label>
              {t('m03.min')}{' '}
              <input
                key={`min-${round}`}
                inputMode="decimal"
                maxLength={12}
                onChange={(e) => set({ min: num(e.target.value) })}
              />
            </label>
            <label>
              {t('m03.max')}{' '}
              <input
                key={`max-${round}`}
                inputMode="decimal"
                maxLength={12}
                onChange={(e) => set({ max: num(e.target.value) })}
              />
            </label>
            <label>
              {t('m03.stagnation')}{' '}
              <input
                key={`stagnation-${round}`}
                inputMode="decimal"
                maxLength={12}
                onChange={(e) => set({ stagnationAmount: num(e.target.value) })}
              />
            </label>
            <label>
              {t('m03.from')}{' '}
              <input
                type="date"
                value={draft.effectiveFrom ?? ''}
                onChange={(e) => set({ effectiveFrom: e.target.value })}
              />
            </label>
            <label>
              {t('m03.circular')}{' '}
              <select
                value={draft.circularId ?? ''}
                onChange={(e) =>
                  set({ circularId: e.target.value === '' ? undefined : Number(e.target.value) })
                }
              >
                <option value="">{t('common.choose')}</option>
                {(orders.data ?? []).map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.number}
                    {o.subject ? ` - ${o.subject}` : ''}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('m03.wageRevision')}{' '}
              <select
                value={draft.wageRevision ?? ''}
                onChange={(e) => set({ wageRevision: e.target.value })}
              >
                <option value="">{t('common.choose')}</option>
                {(revisions.data ?? []).map((r) => (
                  <option key={r.code} value={r.code}>
                    {r.code} - {r.name}
                  </option>
                ))}
              </select>
            </label>
            <label>{t('m03.stagesInput')}</label>
            {/* SRS l.3949: the stages as a grid, one row each, every row checked on its own. */}
            <div role="group" aria-label={t('m03.stagesInput')} className="form-grid">
              {stageRows.map((value, i) => (
                <span key={i} className="toolbar">
                  <input
                    inputMode="decimal"
                    maxLength={12}
                    aria-label={t('m03.stageRow', [i + 1])}
                    aria-invalid={badRow === i + 1 ? true : undefined}
                    value={value}
                    onChange={(e) => setStageRows(stageRows.map((r, j) => (j === i ? e.target.value : r)))}
                  />
                  <button
                    type="button"
                    className="btn sm"
                    disabled={stageRows.length === 1}
                    aria-label={t('m03.removeStage', [i + 1])}
                    onClick={() => setStageRows(stageRows.filter((_, j) => j !== i))}
                  >
                    {t('common.remove')}
                  </button>
                </span>
              ))}
              <button type="button" className="btn sm" onClick={() => setStageRows([...stageRows, ''])}>
                {t('m03.addStage')}
              </button>
              {badRow !== null && <p className="fl-hint">{t('m03.why.stageRow', [badRow])}</p>}
            </div>
            <label>
              {t('common.reason')}{' '}
              <TextAreaField
                maxLength={500}
                value={draft.reason ?? ''}
                onChange={(e) => set({ reason: e.target.value })}
              />
            </label>
          </div>
          <div className="toolbar">
            <button
              type="button"
              className="btn pri"
              disabled={!ready || action.isPending}
              title={!headerReady ? t('m03.why.newScale') : !ready ? t('m03.why.propose') : undefined}
              onClick={() => void propose()}
            >
              {code === null ? t('m03.newScale') : t('action.newVersion')}
            </button>
          </div>
          {proposed && proposed.aboveMaximum.length > 0 && (
            <p className="fl-hint">{t('m03.aboveMaximum', [proposed.aboveMaximum.join(', ')])}</p>
          )}
        </>
      )}
    </Card>
  );
}
