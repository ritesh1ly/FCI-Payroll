'use client';

import { useState } from 'react';
import { useDocumentTypes, todayIso } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { amount, date, Problem, ServerPagination, withBase } from '@/shared/ui';
import { RegisterSection, SimulateAct, useApproveRow } from '@/shared/ui/masterParts';
import { OrderCopyCard, type OrderCopyInput } from '@/shared/ui/orderCopy';
import { Act, KvCard, Locked, Panel, RowActs, Tbl, Tiles, ViewDialog, WfTile } from '@/shared/ui/wireframe';
import type { WorklistRow } from '../api/incrementApi';
import {
  incrementKeys,
  incrementPages,
  incrementUrls,
  useFirstWorklist,
  useCaseOrders,
  useWorklistCounts,
} from '../orchestration/incrementQueries';
import { IncrementFixation } from './IncrementFixation';

const STATUS_PILL: Record<string, string> = {
  CHECKER: 'p-warn',
  RETRO: 'p-warn',
  APPROVED: 'p-ok',
  DUPLICATE: 'p-bad',
  BARRED: 'p-bad',
  FLAGGED: 'p-bad',
  HRMS_TASK: 'p-info',
  DUE: 'p-info',
};

/** The drawn Type: a run line reads its increment type, a case its case type, a task its field. */
function typeOf(r: WorklistRow, t: (k: string, a?: readonly unknown[]) => string): string {
  if (r.kind === 'HRMS_TASK') return t('i05w.type.task', [r.orderReference ?? '']);
  return t(`i05w.type.${r.type ?? r.kind}`);
}

function money(n: number | null): string {
  return n === null ? '—' : amount(n);
}

function monthsBetween(from: string, to: string): number {
  return (
    (Number(to.slice(0, 4)) - Number(from.slice(0, 4))) * 12 +
    Number(to.slice(5, 7)) -
    Number(from.slice(5, 7))
  );
}

/**
 * I-05 Increment &amp; Pay Fixation as Wireframes v6.1 draws it (OD-121; SRS-13-1 ... 13-27, screen I-05). The tiles,
 * the worklist of the session month, the rules and the fixation detail; the Increment Due run, the cases worked out in
 * Payroll and the Basic history stay whole in the closed section that the drawn buttons open, so nothing is lost and no
 * read fires until it opens.
 *
 * <p>The pack draws entry for the Payroll Maker; SRS-13-20 gives a case worked out in Payroll to Establishment / HR
 * only and sends what HRMS received to the Maker on I-21. So 🧮 Compute Fixation and ✎ Correct Increment Date take the
 * Maker to I-21, and HR to the case form; no right is added (WF-1305). Personal Pay (ALT-5, SRS-13-22) and the
 * run-time stage block (RUN-1) wait for the owner: the panel states them, nothing here computes them.
 */
export function IncrementScreen() {
  const { t } = useI18n();
  const me = useMe();
  const approve = useApproveRow();
  const month = businessToday().slice(0, 7);
  const counts = useWorklistCounts(month);
  const types = useDocumentTypes(true);
  const [register, setRegister] = useState(false);
  const [viewing, setViewing] = useState<WorklistRow | null>(null);
  const [selected, setSelected] = useState<WorklistRow | null>(null);
  const [held, setHeld] = useState<OrderCopyInput | null>(null);
  const maker = me.data?.roles?.includes('PAYROLL_MAKER') === true;
  const hr = can(me.data, 'I-05', 'SUBMIT');
  const checker = can(me.data, 'I-05', 'APPROVE');
  const first = useFirstWorklist(month);
  const detail = selected ?? first.data?.items.find((r) => r.kind !== 'DUE' && r.kind !== 'APPLIED') ?? null;
  const stored = useCaseOrders(detail?.kind === 'CASE' ? detail.approvalId : null);
  const c = counts.data;
  const toI21 = withBase('/payroll/?s=I-21');
  const today = businessToday();
  const retro = detail !== null && detail.effective.slice(0, 7) < month;

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('i05w.dueList')} onClick={() => setRegister(true)} />
        {maker ? (
          <Act label={t('i05w.compute')} kind="pri" href={toI21} />
        ) : (
          <Locked label={t('i05w.compute')} why={t('i05w.why.compute')} />
        )}
      </div>
      <Problem error={counts.error} />
      <Tiles>
        <WfTile
          icon="📈"
          label={t('i05w.tile.due')}
          src="CALC"
          big={c?.due ?? '—'}
          sub={c ? t('i05w.tile.due.sub', [c.annual, c.stagnation]) : ''}
        />
        <WfTile
          icon="⬆️"
          tone="pur"
          label={t('i05w.tile.fix')}
          src="HRMS"
          big={c?.fixations ?? '—'}
          sub={t('i05w.tile.fix.sub')}
        />
        <WfTile
          icon="↩️"
          tone="warn"
          label={t('i05w.tile.retro')}
          src="CALC"
          big={c?.retrospective ?? '—'}
          sub={
            c?.latestArrearEvent ? t('i05w.tile.retro.sub', [c.latestArrearEvent]) : t('i05w.tile.retro.none')
          }
        />
        <WfTile
          icon="🔝"
          label={t('i05w.tile.max')}
          src="CALC"
          big={c?.atMaximum ?? '—'}
          sub={t('i05w.tile.max.sub')}
        />
      </Tiles>
      <div className="oq fl-mb">
        <b>{t('i05w.note.lead')}</b> {t('i05w.note')}
      </div>
      <Tbl
        title={t('i05w.table')}
        acts={<SimulateAct label={t('i05w.simulate')} />}
        cols={[
          { t: t('i05w.col.member'), s: 'HRMS' },
          { t: t('i05w.col.scale'), s: 'ENT' },
          { t: t('i05w.col.type'), s: 'CALC' },
          { t: t('i05w.col.present'), s: 'ENT', num: true },
          { t: t('i05w.col.pct'), s: 'RULE', num: true },
          { t: t('i05w.col.revised'), s: 'CALC', num: true },
          { t: t('i05w.col.effective'), s: 'ENT' },
          { t: t('i05w.col.atMax'), s: 'CALC' },
          { t: t('i05w.col.arrear'), s: 'CALC' },
          { t: t('i05w.col.status'), s: 'SYS' },
          { t: t('m04.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={incrementKeys.worklist(month)}
          fetchPage={incrementPages.worklist(month)}
          empty={<p className="fl-hint">{t('i05w.none')}</p>}
        >
          {(rows) =>
            rows.map((r) => (
              <tr key={`${r.kind}-${r.key}`}>
                <td>
                  {r.eis} {r.employee}
                </td>
                <td>{r.scale ?? '—'}</td>
                <td>{typeOf(r, t)}</td>
                <td className="num">{money(r.presentBasic)}</td>
                <td className="num">{r.incrementPct === null ? '—' : `${r.incrementPct}%`}</td>
                <td className="num">{money(r.revisedBasic)}</td>
                <td>{date(r.effective)}</td>
                <td>{r.atMaximum ? <b>{t('common.yes')}</b> : t('common.no')}</td>
                <td>
                  {r.arrearEvent === null ? (
                    '—'
                  ) : (
                    <span className="pill p-info">{t('i05w.arrear', [r.arrearEvent])}</span>
                  )}
                </td>
                <td>
                  <span className={`pill ${STATUS_PILL[r.status] ?? 'p-info'}`}>
                    {t(`i05w.status.${r.status}`)}
                  </span>
                </td>
                <td>
                  <RowActs
                    viewName={`${t('common.view')} ${r.eis}`}
                    editName={`${t('common.edit')} ${r.eis}`}
                    onView={() => {
                      setSelected(r);
                      setViewing(r);
                    }}
                    onEdit={maker && r.kind === 'HRMS_TASK' ? () => window.location.assign(toI21) : undefined}
                    editWhy={t(maker ? 'i05w.why.editHr' : 'i05w.why.edit')}
                    onApprove={checker ? () => approve(r.status === 'CHECKER') : undefined}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Panel
          title={t('i05w.rules')}
          items={[
            {
              icon: '🔝',
              tone: 'info',
              title: t('i05w.rules.max'),
              src: 'RULE',
              text: t('i05w.rules.max.m'),
              right: <span className="pill p-ok">{t('common.on')}</span>,
            },
            {
              icon: '📅',
              tone: 'info',
              title: t('i05w.rules.date'),
              src: 'ENT',
              text: t('i05w.rules.date.m'),
              right: <span className="pill p-ok">{t('common.on')}</span>,
            },
            {
              icon: '🚫',
              tone: 'bad',
              title: t('i05w.rules.dup'),
              src: 'SYS',
              text: t('i05w.rules.dup.m'),
              right: <b>{c?.duplicates ?? '—'}</b>,
            },
            {
              icon: '↩️',
              tone: 'warn',
              title: t('i05w.rules.retro'),
              src: 'CALC',
              text: t('i05w.rules.retro.m'),
              right: <b>{c?.retrospective ?? '—'}</b>,
            },
            {
              icon: '🗂',
              tone: 'gray',
              title: t('i05w.rules.old'),
              src: 'SYS',
              text: t('i05w.rules.old.m'),
              right: <span className="pill p-ok">{t('i05w.auto')}</span>,
            },
          ]}
        />
        <KvCard
          title={t('i05w.detail', [detail ? `${detail.eis} ${detail.employee}` : '—'])}
          rows={[
            {
              k: t('i05w.d.present'),
              v: detail ? `${detail.scale ?? '—'} · ₹ ${money(detail.presentBasic)}` : '—',
              s: 'ENT',
            },
            { k: t('i05w.d.promotion'), v: detail?.scale ?? '—', s: 'HRMS' },
            {
              k: t('i05w.d.order'),
              v: detail ? `${detail.orderReference ?? '—'} · ${date(detail.effective)}` : '—',
              s: 'ENT',
            },
            { k: t('i05w.d.rule'), v: t('i05w.d.rule.v'), s: 'RULE' },
            { k: t('i05w.d.revised'), v: detail ? `₹ ${money(detail.revisedBasic)}` : '—', s: 'CALC' },
            {
              k: t('i05w.d.span'),
              v:
                detail && retro
                  ? t('i05w.d.span.v', [
                      date(detail.effective),
                      date(today),
                      monthsBetween(detail.effective, today),
                    ])
                  : '—',
              s: 'CALC',
            },
            {
              k: t('i05w.d.event'),
              v: detail?.arrearEvent ? t('i05w.arrear', [detail.arrearEvent]) : '—',
              s: 'SYS',
            },
            {
              k: t('i05w.d.estimated'),
              v: detail?.arrearAmount ? `₹ ${money(detail.arrearAmount)}` : '—',
              s: 'CALC',
            },
          ]}
        />
      </div>
      <div className="g2 fl-mt">
        <Tbl
          title={t('i05w.stag')}
          cols={[
            { t: t('i05w.stag.rule'), s: 'RULE' },
            { t: t('i05w.stag.statement'), s: 'RULE' },
          ]}
        >
          {(['004', '005', '007', '008', '009'] as const).map((id) => (
            <tr key={id}>
              <td>{t(`i05w.stag.r${id}`)}</td>
              <td>
                {t(`i05w.stag.s${id}`)}
                {id === '005' && <span className="pill p-info"> {t('i05w.setting')}</span>}
              </td>
            </tr>
          ))}
        </Tbl>
        <Panel
          title={t('i05w.incr')}
          items={[
            { icon: '📅', tone: 'info', title: t('i05w.incr.first'), src: 'RULE', text: 'BR-EMPR-003' },
            { icon: '🗓', tone: 'info', title: t('i05w.incr.jan'), src: 'RULE', text: t('i05w.incr.jan.m') },
            {
              icon: '🔁',
              tone: 'info',
              title: t('i05w.incr.hrms'),
              src: 'HRMS',
              text: t('i05w.incr.hrms.m'),
            },
            {
              icon: '⚙️',
              tone: 'warn',
              title: t('i05w.incr.sias'),
              src: 'RULE',
              text: t('i05w.incr.sias.m'),
            },
          ]}
        />
      </div>
      <div className="g2 fl-mt">
        <KvCard
          title={t('i05w.corr')}
          rows={[
            { k: t('i05w.corr.by'), v: t('i05w.corr.by.v'), s: 'SYS' },
            { k: t('i05w.corr.order'), v: t('i05w.corr.order.v'), s: 'ENT' },
            { k: t('i05w.corr.checker'), v: t('i05w.corr.checker.v'), s: 'SYS' },
            { k: t('i05w.corr.approval'), v: t('i05w.corr.approval.v'), s: 'CALC' },
            { k: t('i05w.corr.labour'), v: t('i05w.corr.labour.v'), s: 'RULE' },
          ]}
          after={
            <div className="bd">
              {maker ? (
                <Act label={t('i05w.correct')} kind="pri" href={toI21} />
              ) : hr ? (
                <Act label={t('i05w.correct')} kind="pri" onClick={() => setRegister(true)} />
              ) : (
                <Locked label={t('i05w.correct')} why={t('i05w.why.correct')} />
              )}
            </div>
          }
        />
        <Panel
          title={t('i05w.ctrl')}
          act={<Act label={t('i05w.history')} kind="sm" onClick={() => setRegister(true)} />}
          items={[
            {
              icon: '📅',
              tone: 'info',
              title: t('i05w.ctrl.date'),
              src: 'RULE',
              text: t('i05w.ctrl.date.m'),
            },
            { icon: '➕', tone: 'info', title: t('i05w.ctrl.pp'), src: 'RULE', text: t('i05w.ctrl.pp.m') },
            { icon: '✔', tone: 'warn', title: t('i05w.ctrl.code'), src: 'RULE', text: t('i05w.ctrl.code.m') },
            {
              icon: '📄',
              tone: 'gray',
              title: t('i05w.ctrl.report'),
              src: 'SYS',
              text: t('i05w.ctrl.report.m'),
            },
          ]}
        />
      </div>
      {/* SRS-13-18, SRS-G-31: the order travels with its case: what HR enters here is held and carried into the case
          form of the closed section, stored on submission. A Maker holds no I-05 SUBMIT (SRS-13-20): the fields are
          drawn editable, picking a file says why and nothing is sent. WF-1306. */}
      <OrderCopyCard
        hint={t('i05w.order.hint')}
        canAttach={hr}
        readOnly={!hr && !maker}
        holds
        officeRequired
        stored={stored.data}
        storedError={stored.error ?? types.error}
        fileHref={(fileId) => incrementUrls.caseOrder(detail?.approvalId ?? 0, fileId)}
        today={todayIso()}
        attachmentRequired
        referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
        onAttach={async (order) => {
          setHeld(order);
          setRegister(true);
        }}
      />
      <RegisterSection label={t('i05w.register')} open={register} onToggle={setRegister} id="i05-register">
        {register && <IncrementFixation key={held?.files[0]?.name ?? 'none'} order={held} />}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.I-05')}
          rows={[
            { k: t('i05w.col.member'), v: `${viewing.eis} ${viewing.employee}`, s: 'HRMS' },
            { k: t('i05w.col.scale'), v: viewing.scale ?? '—', s: 'ENT' },
            { k: t('i05w.col.type'), v: typeOf(viewing, t), s: 'CALC' },
            { k: t('i05w.col.present'), v: money(viewing.presentBasic), s: 'ENT' },
            { k: t('i05w.col.revised'), v: money(viewing.revisedBasic), s: 'CALC' },
            { k: t('i05w.col.effective'), v: date(viewing.effective), s: 'ENT' },
            { k: t('i05w.col.status'), v: t(`i05w.status.${viewing.status}`), s: 'SYS' },
            { k: t('i05w.flags'), v: viewing.flags.join(', ') || '—', s: 'SYS' },
            { k: t('i05w.barredBy'), v: viewing.barredBy.join(', ') || '—', s: 'SYS' },
          ]}
          onClose={() => setViewing(null)}
        />
      )}
    </div>
  );
}
