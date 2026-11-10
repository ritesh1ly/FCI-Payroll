'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { amount, date, ServerPagination, useToast } from '@/shared/ui';
import { RegisterSection, SimulateAct, useApproveRow, useMasterRights } from '@/shared/ui/masterParts';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { Act, Locked, Panel, RowActs, Tbl, ViewDialog } from '@/shared/ui/wireframe';
import { todayIso, useDocumentTypes } from '@/modules/general';
import { orderForm } from '../business/incrementRules';
import type { ScaleRow } from '../api/incrementApi';
import {
  incrementCalls,
  incrementKeys,
  incrementPages,
  incrementUrls,
  useFirstScales,
  useIncrementAction,
  useScaleOrders,
} from '../orchestration/incrementQueries';
import { PayScales, type ScaleSeed } from './PayScales';

/** The row's Status as drawn; the SRS keeps no status list for a scale (WF-1302). */
function scaleStatus(r: ScaleRow): { key: string; pill: string } {
  if (r.pending) return { key: 'm03w.status.pending', pill: 'p-warn' };
  return r.active
    ? { key: 'm03w.status.active', pill: 'p-ok' }
    : { key: 'm03w.status.inactive', pill: 'p-info' };
}

/**
 * M-03 Pay Scales &amp; Grades as Wireframes v6.1 draws it (OD-121; SRS M-03, SRS-13-23, SRS-20-29, A-41). The
 * scale list, the HRMS grade mapping with its headcount, the drawn entry card and the order card; every version,
 * stage grid and circular link stays in the closed register, so no function of the screen is lost. The drawn entry
 * carries what it draws into the full form, which adds the fields the SRS requires and the drawing omits (name,
 * stages, circular, wage revision, reason: WF-1300). A version's order stays its Active M-25 circular (SRS l.3950);
 * the drawn order card keeps its own copies on the scale last viewed, or the first of the list, and stores one for
 * the holder of M-03 CREATE at Headquarters; the Administrator holds no such right (OD-135), so the fields are drawn
 * editable and picking a file says why (WF-1303). The Cadre field and column wait for the owner (ALT-3): no column
 * holds a cadre. Rights are never widened to match the drawing (WF-208).
 */
export function PayScalesScreen() {
  const { t } = useI18n();
  const toast = useToast();
  const rights = useMasterRights('M-03');
  const approve = useApproveRow();
  const types = useDocumentTypes(true);
  const [register, setRegister] = useState(false);
  const [picked, setPicked] = useState<string | null>(null);
  const [viewing, setViewing] = useState<ScaleRow | null>(null);
  const [seed, setSeed] = useState<ScaleSeed | null>(null);
  const [form, setForm] = useState({ code: '', pattern: '', min: '', max: '', stagnation: '', from: '' });
  const set = (patch: Partial<typeof form>) => setForm({ ...form, ...patch });
  const action = useIncrementAction();
  const first = useFirstScales();
  const orderScale = picked ?? first.data?.items[0]?.code ?? null;
  const stored = useScaleOrders(orderScale);

  function save() {
    if (!rights.mayCreate) {
      toast(rights.entryWhy ?? t('m03w.why.admin'));
      return;
    }
    setSeed({
      code: form.code.trim(),
      scaleType: form.pattern,
      version: {
        min: form.min === '' ? undefined : Number(form.min),
        max: form.max === '' ? undefined : Number(form.max),
        stagnationAmount: form.stagnation === '' ? undefined : Number(form.stagnation),
        effectiveFrom: form.from,
      },
    });
    setRegister(true);
  }

  const cols = [
    { t: t('m03w.col.scale'), s: 'ENT' as const },
    { t: t('m03w.col.pattern'), s: 'ENT' as const },
    { t: t('m03w.col.min'), s: 'ENT' as const, num: true },
    { t: t('m03w.col.max'), s: 'ENT' as const, num: true },
    { t: t('m03w.col.increment'), s: 'RULE' as const },
    { t: t('m03w.col.stagnation'), s: 'RULE' as const },
    { t: t('m03w.col.from'), s: 'ENT' as const },
    { t: t('m03w.col.status'), s: 'SYS' as const },
    { t: t('m04.col.actions'), s: 'SYS' as const },
  ];

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('wf.historyAct')} onClick={() => setRegister(true)} />
        <Act label={t('m03w.versions')} onClick={() => setRegister(true)} />
        {rights.drawn ? (
          <Act
            label={t('m03w.newVersion')}
            kind="pri"
            onClick={() => document.getElementById('payScaleForm')?.scrollIntoView()}
          />
        ) : (
          <Locked label={t('m03w.newVersion')} why={t('m03w.why.admin')} />
        )}
      </div>
      <div className="card fl-mb" id="payScaleForm">
        <div className="hd">
          <h3>{t('m03w.formTitle')}</h3>
        </div>
        <div className="bd">
          {!rights.drawn ? (
            <p className="sub2">{t('m03w.adminOnly')}</p>
          ) : (
            <>
              <div className="rf-grid">
                <div className="rf-field">
                  <label htmlFor="ps_scale">
                    {t('m03w.f.code')} <span className="req">*</span>
                  </label>
                  <input
                    id="ps_scale"
                    maxLength={10}
                    value={form.code}
                    onChange={(e) => set({ code: e.target.value })}
                  />
                </div>
                <div className="rf-field">
                  <label htmlFor="ps_pattern">
                    {t('m03w.f.pattern')} <span className="req">*</span>
                  </label>
                  <select
                    id="ps_pattern"
                    value={form.pattern}
                    onChange={(e) => set({ pattern: e.target.value })}
                  >
                    <option value="">{t('common.select')}</option>
                    <ReferenceOptions list="PAY_SCALE_TYPE" />
                  </select>
                </div>
                <div className="rf-field">
                  <label htmlFor="ps_min">
                    {t('m03w.f.min')} <span className="req">*</span>
                  </label>
                  <input
                    id="ps_min"
                    type="number"
                    min={0}
                    step={100}
                    value={form.min}
                    onChange={(e) => set({ min: e.target.value })}
                  />
                  <span className="hint">{t('m03w.hint.min')}</span>
                </div>
                <div className="rf-field">
                  <label htmlFor="ps_max">
                    {t('m03w.f.max')} <span className="req">*</span>
                  </label>
                  <input
                    id="ps_max"
                    type="number"
                    min={0}
                    step={100}
                    value={form.max}
                    onChange={(e) => set({ max: e.target.value })}
                  />
                  <span className="hint">{t('m03w.hint.max')}</span>
                </div>
                <div className="rf-field">
                  <label htmlFor="ps_incr">
                    {t('m03w.f.increment')} <span className="req">*</span>
                  </label>
                  {/* WF-1301: the increment is the band's rule-engine key, not free text (R-RULE). */}
                  <input
                    id="ps_incr"
                    disabled
                    title={t('m03w.why.increment')}
                    data-why={t('m03w.why.increment')}
                  />
                </div>
                <div className="rf-field">
                  <label htmlFor="ps_stag">
                    {t('m03w.f.stagnation')} <span className="req">*</span>
                  </label>
                  <input
                    id="ps_stag"
                    inputMode="decimal"
                    value={form.stagnation}
                    onChange={(e) => set({ stagnation: e.target.value })}
                  />
                </div>
                <div className="rf-field">
                  <label htmlFor="ps_eff">
                    {t('m03w.f.from')} <span className="req">*</span>
                  </label>
                  <input
                    id="ps_eff"
                    type="date"
                    value={form.from}
                    onChange={(e) => set({ from: e.target.value })}
                  />
                </div>
              </div>
              <div className="rf-actbar">
                <button type="button" className="btn pri" onClick={save}>
                  {t('m03w.save')}
                </button>
              </div>
            </>
          )}
        </div>
      </div>
      <Tbl title={t('m03w.table')} acts={<SimulateAct label={t('m03w.simulate')} />} cols={cols}>
        <ServerPagination
          queryKey={incrementKeys.scales}
          fetchPage={incrementPages.scales}
          personal={false}
          empty={<p className="fl-hint">{t('m03.none')}</p>}
        >
          {(rows) =>
            rows.map((r) => {
              const status = scaleStatus(r);
              return (
                <tr key={r.bandId}>
                  <td>
                    <b>{r.code}</b>
                    <div className="sub2">{r.name}</div>
                  </td>
                  <td>{r.scaleType ?? '—'}</td>
                  <td className="num">{r.min === null ? '—' : amount(r.min)}</td>
                  <td className="num">{r.max === null ? '—' : amount(r.max)}</td>
                  <td>{r.incrementRuleKey ?? '—'}</td>
                  <td>{r.stagnationAmount === null ? '—' : amount(r.stagnationAmount)}</td>
                  <td>{r.effectiveFrom ? date(r.effectiveFrom) : '—'}</td>
                  <td>
                    <span className={`pill ${status.pill}`}>{t(status.key)}</span>
                  </td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${r.code}`}
                      editName={`${t('common.edit')} ${r.code}`}
                      onView={() => {
                        setPicked(r.code);
                        setViewing(r);
                      }}
                      onEdit={rights.drawn ? () => setRegister(true) : undefined}
                      editWhy={t('m03w.why.edit')}
                      onApprove={rights.checker ? () => approve(r.pending) : undefined}
                    />
                  </td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Tbl
          title={t('m03w.mapping')}
          cols={[
            { t: t('m03w.map.grade'), s: 'HRMS' },
            { t: t('m03w.map.designation'), s: 'HRMS' },
            { t: t('m03w.map.scale'), s: 'ENT' },
            { t: t('m03w.map.headcount'), s: 'CALC', num: true },
            { t: t('m03w.col.status'), s: 'SYS' },
          ]}
        >
          <ServerPagination
            queryKey={incrementKeys.mapping}
            fetchPage={incrementPages.mapping}
            personal={false}
            empty={<p className="fl-hint">{t('m03w.map.none')}</p>}
          >
            {(rows) =>
              rows.map((m) => (
                <tr key={m.designationCode}>
                  <td>{m.gradeCode ?? '—'}</td>
                  <td>{m.designationName}</td>
                  <td>{m.scaleCode ?? '—'}</td>
                  <td className="num">{m.headcount}</td>
                  <td>
                    <span className={`pill ${m.scaleCode ? 'p-ok' : 'p-bad'}`}>
                      {t(m.scaleCode ? 'm03w.map.mapped' : 'm03w.map.unmapped')}
                    </span>
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
        <Panel
          title={t('m03w.used')}
          items={[
            { icon: '👥', tone: 'info', title: t('m03w.used.c02'), src: 'RULE', text: t('m03w.used.c02.m') },
            { icon: '📈', tone: 'info', title: t('m03w.used.i05'), src: 'RULE', text: t('m03w.used.i05.m') },
            { icon: '🏦', tone: 'info', title: t('m03w.used.c07'), src: 'RULE', text: t('m03w.used.c07.m') },
            { icon: '📤', tone: 'info', title: t('m03w.used.o05'), src: 'RULE', text: t('m03w.used.o05.m') },
          ]}
        />
      </div>
      <OrderCopyCard
        key={orderScale ?? ''}
        hint={t('m03w.order.hint')}
        canAttach={orderScale !== null && rights.entryWhy === null}
        readOnly={!rights.drawn}
        stored={stored.data}
        storedError={stored.error ?? types.error}
        fileHref={(fileId) => incrementUrls.scaleOrder(orderScale ?? '', fileId)}
        today={todayIso()}
        attachmentRequired
        officeRequired
        referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
        onAttach={async (order) => {
          await Promise.all(
            orderForm(order).map((form) =>
              action.mutateAsync(() => incrementCalls.attachScaleOrder(orderScale ?? '', form)),
            ),
          );
        }}
      />
      <RegisterSection label={t('m03w.register')} open={register} onToggle={setRegister} id="m03-register">
        {register && <PayScales key={seed?.code ?? 'list'} seed={seed} />}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-03')}
          rows={[
            { k: t('m03w.col.scale'), v: `${viewing.code} - ${viewing.name}`, s: 'ENT' },
            { k: t('m03w.col.pattern'), v: viewing.scaleType ?? '—', s: 'ENT' },
            { k: t('m03w.col.min'), v: viewing.min === null ? '—' : amount(viewing.min), s: 'ENT' },
            { k: t('m03w.col.max'), v: viewing.max === null ? '—' : amount(viewing.max), s: 'ENT' },
            { k: t('m03w.col.increment'), v: viewing.incrementRuleKey ?? '—', s: 'RULE' },
            {
              k: t('m03w.col.stagnation'),
              v: viewing.stagnationAmount === null ? '—' : amount(viewing.stagnationAmount),
              s: 'RULE',
            },
            { k: t('m03.stages'), v: String(viewing.stages), s: 'ENT' },
            {
              k: t('m03w.col.from'),
              v: viewing.effectiveFrom ? date(viewing.effectiveFrom) : '—',
              s: 'ENT',
            },
            { k: t('m03w.col.status'), v: t(scaleStatus(viewing).key), s: 'SYS' },
          ]}
          onClose={() => setViewing(null)}
        />
      )}
    </div>
  );
}
