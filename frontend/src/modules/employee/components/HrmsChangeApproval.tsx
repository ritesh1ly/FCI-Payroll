'use client';

import { type FormEvent, useCallback, useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { type Decision, useDecision } from '@/modules/workflow';
import {
  Card,
  date,
  dateTime,
  ExportButton,
  Problem,
  ServerPagination,
  type Source,
  Src,
  useDialogFocus,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import {
  Act,
  Explained,
  KvCard,
  type KvRow,
  Locked,
  Panel,
  type PanelItem,
  RowActs,
  Tbl,
  Tiles,
  WfTile,
} from '@/shared/ui/wireframe';
import type { HrmsTask, HrmsTaskDetail, HrmsTaskFilter } from '../api/hrmsChangeApi';
import type { BulkOrder } from '../api/registerApi';
import { taskPill } from '../business/eventRules';
import {
  copyPill,
  copyState,
  dobRule,
  orderable,
  orderComplete,
  pending,
  submittable,
} from '../business/hrmsChangeRules';
import {
  hrmsChangeKeys,
  hrmsChangePage,
  hrmsRegisterExportUrl,
  useAttachTaskOrder,
  useCreateBulkOrder,
  useHrmsTaskDetail,
  useRegisterCounts,
  useSubmitHrmsTask,
  taskDocumentUrl,
  useTaskDocuments,
} from '../orchestration/hrmsChangeQueries';
import {
  registerKeys,
  registerPages,
  useFieldGroups,
  useFirstDobTask,
  useLatestOrder,
  useTaskSummary,
} from '../orchestration/registerQueries';
import { todayIso, useDocumentTypes } from '@/modules/general';
import { HrmsUpdateRequests } from './HrmsUpdateRequests';

const PLAIN_TEXT = /^[\x20-\x7E]*$/;
/** Text of a text area: printable ASCII with tab and line breaks (R-ASCII, as @PrintableAscii). */
const TEXT_LINES = /^[\x20-\x7E\t\r\n]*$/;
const STATUSES = [
  'PENDING_ORDER_DETAILS',
  'PENDING_CHECKER',
  'PENDING_HQ_ESTABLISHMENT',
  'RETURNED_TO_MAKER',
  'APPROVED',
  'REJECTED_RETURNED_TO_HRMS',
  'AUTO_APPLIED',
] as const;

/** The tabs of the task area, as drawn. */
const TABS = ['tasks', 'dob', 'bulk', 'register', 'fields'] as const;
type Tab = (typeof TABS)[number];

/**
 * I-21 HRMS Change Approval as Wireframes v6.1 draws it (OD-121; plan 16 phases 16a-c; SRS-1-19, SRS-1-21 to 24, SRS-1-28,
 * SRS-1-29, SRS-1-61): the HRMS Change Register export, Raise HRMS Update Request and Approve bulk order over the screen;
 * five tiles; the tabs Task list, DOB change task, One order - many staff, HRMS Change Register and Fields with pay effect
 * with the search; the task's detail with Approve and Reject; how a task runs; the order card. The value comes from HRMS
 * and is never editable here: the Payroll Maker records the order behind it and sends the task, the Payroll Checker decides
 * here or on the Approvals Inbox (OTP only for an increment-date or Basic Pay change; a DOB whose new retirement date is
 * past also goes to HQ Establishment). A change without pay effect was applied at once and is listed for the register.
 * SRS-1-67: one order for many staff; SRS-1-68: the days pending; SRS-1-71, SRS-1-30: the registers with counts and export.
 * Status and Name / DOB filters, the counts, the ticking of tasks for one order and the update-request list stay in a
 * closed section (WF-231). Tasks arrive through employee.api.HrmsChanges from the HRMS intake; Raise fetches through
 * HrmsGateway (BASIC_PAY only, PENDING API-16-7).
 */
export function HrmsChangeApproval() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const [tab, setTab] = useState<Tab>('tasks');
  const [search, setSearch] = useState('');
  const [openId, setOpenId] = useState<number | null>(null);
  const [picked, setPicked] = useState<HrmsTask | null>(null);
  const [closedOpen, setClosedOpen] = useState(false);
  const [deciding, setDeciding] = useState<{ approvalId: number; title: string } | null>(null);
  const summary = useTaskSummary();
  const latest = useLatestOrder().data?.items?.[0] ?? null;
  const mayExport = can(me.data, 'I-21', 'EXPORT');
  const mayRaise = can(me.data, 'I-21', 'REQUEST');
  const mayMake =
    can(me.data, 'I-21', 'SUBMIT') || can(me.data, 'I-21', 'SUBMIT_OTP') || can(me.data, 'I-21', 'SUBMIT_HQ');
  // Establishment / HR's APPROVE is the third level of a past-retirement DOB only (V040_2): its row is on the DOB tab.
  const mayDecide = can(me.data, 'I-21', 'APPROVE') && !mayMake && !can(me.data, 'M-01', 'FLAG');
  const filter: HrmsTaskFilter = { q: search.trim() || undefined };
  const onRows = useCallback(
    (rows: HrmsTask[]) => setPicked((now) => rows.find((r) => r.id === now?.id) ?? rows[0] ?? null),
    [],
  );
  const s = summary.data;

  function approveRow(row: HrmsTask) {
    if (row.status === 'PENDING_CHECKER' && row.approvalId !== null) {
      setDeciding({ approvalId: row.approvalId, title: row.taskNo });
    } else {
      toast(t('i21.w.why.notWithChecker', [row.taskNo]));
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="spacer" />
        {mayExport ? (
          <ExportButton
            href={hrmsRegisterExportUrl({})}
            what={t('i21.register.hrms')}
            label={t('i21.w.registerExport')}
          />
        ) : (
          <Explained label={t('i21.w.registerExport')} why={t('i21.w.why.export')} />
        )}
        {mayRaise ? (
          <Act label={t('i21.w.raise')} kind="pri" onClick={() => setClosedOpen(true)} />
        ) : (
          <Locked label={t('i21.w.raise')} why={t('i21.w.why.raise')} />
        )}
        {mayDecide ? (
          latest?.approvalId ? (
            <Act
              label={t('i21.w.approveBulk')}
              kind="pri"
              onClick={() => setDeciding({ approvalId: latest.approvalId ?? 0, title: latest.orderNo })}
            />
          ) : (
            <Explained label={t('i21.w.approveBulk')} why={t('i21.w.why.noOrder')} kind="pri" />
          )
        ) : (
          <Locked label={t('i21.w.approveBulk')} why={t('i21.w.why.checker')} />
        )}
      </div>
      <Tiles>
        <WfTile
          icon="🗂"
          label={t('i21.w.t.pending')}
          src="SYS"
          big={s?.pending ?? '—'}
          sub={t('i21.w.t.pendingSub', [s?.beforeFreeze ?? 0, s?.afterFreeze ?? 0])}
        />
        <WfTile
          icon="✍️"
          label={t('i21.w.t.checker')}
          src="SYS"
          big={s?.withChecker ?? '—'}
          sub={t('i21.w.t.checkerSub')}
          tone="warn"
        />
        <WfTile
          icon="⏰"
          label={t('i21.w.t.ageing')}
          src="RULE"
          big={s?.ageing ?? '—'}
          sub={t('i21.w.t.ageingSub')}
          tone="bad"
          bad
        />
        <WfTile
          icon="🔒"
          label={t('i21.w.t.blocking')}
          src="SYS"
          big={s?.blocking ?? '—'}
          sub={t('i21.w.t.blockingSub')}
          tone="bad"
          bad
        />
        <WfTile
          icon="✔"
          label={t('i21.w.t.auto')}
          src="HRMS"
          big={s?.autoApplied ?? '—'}
          sub={t('i21.w.t.autoSub')}
          tone="ok"
        />
      </Tiles>
      <Problem error={summary.error} />
      <div className="toolbar">
        {TABS.map((k) => (
          <button
            key={k}
            type="button"
            className={`btn${tab === k ? ' pri' : ''}`}
            aria-pressed={tab === k}
            onClick={() => setTab(k)}
          >
            {t(`i21.w.tab.${k}`)}
          </button>
        ))}
        <div className="lsearch">
          🔎{' '}
          <input
            placeholder={t('i21.w.search')}
            aria-label={t('i21.w.search')}
            maxLength={60}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>
      {tab === 'tasks' && (
        <>
          <Tbl
            title={t('i21.w.tasksTitle')}
            acts={
              mayExport ? (
                <ExportButton
                  href={hrmsRegisterExportUrl({})}
                  what={t('i21.w.tasksTitle')}
                  label={t('m01.w.exportShort')}
                  className="btn sm"
                />
              ) : (
                <Explained label={t('m01.w.exportShort')} why={t('i21.w.why.export')} kind="sm" />
              )
            }
            cols={[
              { t: t('i21.task'), s: 'SYS' },
              { t: t('i21.employee'), s: 'HRMS' },
              { t: t('i21.w.col.field'), s: 'HRMS' },
              { t: t('i21.w.col.current'), s: 'SYS' },
              { t: t('i21.w.col.new'), s: 'HRMS' },
              { t: t('i21.w.col.request'), s: 'INTF' },
              { t: t('i21.w.col.effective'), s: 'HRMS' },
              { t: t('i21.w.col.source'), s: 'SYS' },
              { t: t('i21.w.col.order'), s: 'ENT' },
              { t: t('i21.w.col.copy'), s: 'ENT' },
              { t: t('i21.w.col.age'), s: 'CALC' },
              { t: t('i21.status'), s: 'SYS' },
              { t: t('i21.w.col.actions'), s: 'SYS' },
            ]}
          >
            <ServerPagination
              queryKey={hrmsChangeKeys.list(filter)}
              fetchPage={hrmsChangePage(filter)}
              personal={false}
              empty={<p className="fl-hint">{t('i21.none')}</p>}
              onRows={onRows}
            >
              {(rows) =>
                rows.map((row) => (
                  <tr key={row.id} className="fl-pick" onClick={() => setPicked(row)}>
                    <td>
                      <b>{row.taskNo}</b>
                    </td>
                    <td>
                      {row.eis} {row.employee}
                    </td>
                    <td>
                      {row.fieldLabel} ({t(`i21.group.${row.fieldGroup}`)})
                    </td>
                    <td>{row.currentValue ?? '—'}</td>
                    <td>{row.newValue ?? '—'}</td>
                    <td>{row.hrmsRequestId ?? '—'}</td>
                    <td>{date(row.effectiveDate)}</td>
                    <td>{t(`i21.source.${row.source}`)}</td>
                    <td>{orderText(row, t('i21.orderInHrms'))}</td>
                    <td>
                      <span className={`pill ${copyPill(row)}`}>{t(`i21.w.copy.${copyState(row)}`)}</span>
                    </td>
                    <td>{pending(row.status) ? t('i21.w.age', [row.daysPending]) : t('i21.w.noAge')}</td>
                    <td>
                      <span className={`pill ${row.overdue ? 'p-bad' : taskPill(row.status)}`}>
                        {row.overdue ? t('i21.w.overdue') : t(`i21.status.${row.status}`)}
                      </span>
                    </td>
                    <td>
                      <RowActs
                        viewName={t('i21.w.viewOf', [row.taskNo])}
                        editName={t('i21.w.editOf', [row.taskNo])}
                        onView={() => setOpenId(row.id)}
                        onEdit={mayMake && submittable(row.status) ? () => setOpenId(row.id) : undefined}
                        editWhy={t(mayMake ? 'i21.why.state' : 'i21.w.why.viewOnly', [
                          t(`i21.status.${row.status}`),
                        ])}
                        onApprove={mayDecide ? () => approveRow(row) : undefined}
                      />
                    </td>
                  </tr>
                ))
              }
            </ServerPagination>
          </Tbl>
          <div className="g2 fl-mt">
            <TaskDetail
              id={picked?.id ?? null}
              mayDecide={mayDecide}
              onDecide={(task) =>
                task.status === 'PENDING_CHECKER' && task.approvalId !== null
                  ? setDeciding({ approvalId: task.approvalId, title: task.taskNo })
                  : toast(t('i21.w.why.notWithChecker', [task.taskNo]))
              }
            />
            <Panel title={t('i21.w.how.title')} items={howItems(t)} />
          </div>
        </>
      )}
      {tab === 'dob' && <DobTab mayDecide={mayDecide} onDecide={setDeciding} />}
      {tab === 'bulk' && (
        <BulkTab
          order={latest}
          mayDecide={mayDecide}
          onDecide={(order) => setDeciding({ approvalId: order.approvalId ?? 0, title: order.orderNo })}
        />
      )}
      {tab === 'register' && <RegisterTab search={search.trim()} mayExport={mayExport} />}
      {tab === 'fields' && <FieldsTab />}
      <TaskOrders task={picked} mayMake={mayMake} />
      <details className="fl-mt" open={closedOpen} onToggle={(e) => setClosedOpen(e.currentTarget.open)}>
        <summary>{t('i21.w.closed')}</summary>
        <TaskFiltersAndUpdates mayExport={mayExport} onOpen={setOpenId} />
      </details>
      {openId !== null && (
        <div className="fl-modal-backdrop" role="presentation">
          <div className="fl-modal fl-modal-wide" role="dialog" aria-modal="true" aria-label={t('i21.task')}>
            <TaskPanel key={openId} id={openId} onClose={() => setOpenId(null)} />
          </div>
        </div>
      )}
      {deciding && (
        <DecisionDialog
          item={deciding}
          onClose={(done) => {
            setDeciding(null);
            if (done) toast(t('i21.w.decided', [deciding.title]));
          }}
        />
      )}
    </div>
  );
}

function howItems(t: (key: string, args?: (string | number)[]) => string): PanelItem[] {
  const drawn: [string, Source, PanelItem['tone']][] = [
    ['1', 'HRMS', 'info'],
    ['2', 'INTF', 'info'],
    ['3', 'SYS', 'info'],
    ['4', 'ENT', 'warn'],
    ['5', 'SYS', 'warn'],
    ['6', 'CALC', 'ok'],
    ['7', 'SYS', 'bad'],
    ['8', 'HRMS', 'gray'],
  ];
  return drawn.map(([icon, src, tone]) => ({
    icon,
    src,
    tone,
    title: t(`i21.w.how.${icon}`),
    text: t(`i21.w.how.${icon}m`),
  }));
}

/** Order no. · date · authority of a task, or the reference HRMS holds. */
function orderText(row: HrmsTask, held: string): string {
  if (row.orderHeldInHrms) return held;
  if (!row.orderNo) return row.orderRef ?? '—';
  return [row.orderNo, row.orderDate ? date(row.orderDate) : null, row.issuingAuthority, row.orderRef]
    .filter(Boolean)
    .join(' · ');
}

/** Task detail as drawn, with the Checker's Approve and Reject (decided with the OTP the route asks). */
function TaskDetail({
  id,
  mayDecide,
  onDecide,
}: {
  id: number | null;
  mayDecide: boolean;
  onDecide: (task: HrmsTask) => void;
}) {
  const { t } = useI18n();
  const detail = useHrmsTaskDetail(id);
  const data = detail.data;
  const task = data?.task;
  const impact = data?.impact;
  const rows: KvRow[] = task
    ? [
        { k: t('i21.field'), v: t('i21.w.d.locked', [task.fieldLabel]), s: 'SYS' },
        { k: t('i21.w.d.current'), v: task.currentValue ?? '—', s: 'SYS' },
        {
          k: t('i21.w.d.new'),
          v: (
            <>
              <b>{task.newValue ?? '—'}</b> {t('i21.w.d.readOnly')}
            </>
          ),
          s: 'HRMS',
        },
        {
          k: t('i21.w.d.request'),
          v: `${task.hrmsRequestId ?? '—'} · ${date(task.effectiveDate)}`,
          s: 'INTF',
        },
        { k: t('i21.orderNo'), v: task.orderNo ?? '—', s: 'ENT' },
        { k: t('i21.orderDate'), v: task.orderDate ? date(task.orderDate) : '—', s: 'ENT' },
        { k: t('i21.authority'), v: task.issuingAuthority ?? '—', s: 'ENT' },
        { k: t('i21.copy'), v: t(`i21.w.copy.${copyState(task)}`), s: 'ENT' },
        {
          k: t('i21.w.d.impact'),
          v: impact
            ? impact.arrearFrom && impact.arrearTo
              ? t('i21.impact.arrear', [impact.arrearFrom, impact.arrearTo])
              : t('i21.impact.applies', [impact.appliesFrom, impact.paidFrom])
            : '—',
          s: 'CALC',
        },
        {
          k: t('i21.w.d.freeze'),
          v: task.receivedBeforeFreeze
            ? t('i21.beforeFreeze', [task.targetPeriod])
            : t('i21.afterFreeze', [task.targetPeriod]),
          s: 'SYS',
        },
        { k: t('i21.otp'), v: t(task.otpRequired ? 'i21.w.d.otpYes' : 'i21.w.d.otpNo'), s: 'RULE' },
        {
          k: t('i21.w.d.people'),
          v: `${task.makerUser ?? '—'} → ${task.checkerUser ?? '—'}`,
          s: 'SYS',
        },
      ]
    : [];
  return (
    <KvCard
      title={task ? t('i21.w.d.title', [task.taskNo, task.employee, task.fieldLabel]) : t('i21.w.d.none')}
      rows={rows}
      after={
        <div className="rf-actbar">
          <Problem error={detail.error} />
          {mayDecide && task ? (
            <>
              <Act label={t('i21.w.approve')} kind="pri" onClick={() => onDecide(task)} />
              <Act label={t('i21.w.reject')} onClick={() => onDecide(task)} />
            </>
          ) : (
            <>
              <Locked label={t('i21.w.approve')} why={t('i21.w.why.checkerOnly')} />
              <Locked label={t('i21.w.reject')} why={t('i21.w.why.checker')} />
            </>
          )}
        </div>
      }
    />
  );
}

/** SRS-1-21 to 1-23 / UC-04: the DOB task's effects and the tasks needing extra attention. */
function DobTab({
  mayDecide,
  onDecide,
}: {
  mayDecide: boolean;
  onDecide: (item: { approvalId: number; title: string }) => void;
}) {
  const { t } = useI18n();
  const me = useMe();
  const first = useFirstDobTask(true).data?.items?.[0] ?? null;
  const detail = useHrmsTaskDetail(first?.id ?? null).data;
  const dob = detail?.dob;
  const task = detail?.task;
  const mayHq = can(me.data, 'I-21', 'APPROVE') && can(me.data, 'M-01', 'FLAG');
  const day = (v?: string | null) => (v ? date(v) : '—');
  return (
    <div className="g2">
      <KvCard
        title={first ? t('i21.w.dob.title', [first.taskNo, first.employee]) : t('i21.w.dob.none')}
        rows={[
          { k: t('i21.w.dob.current'), v: first?.currentDob ?? '—', s: 'SYS' },
          { k: t('i21.w.dob.new'), v: <b>{first?.newDob ?? '—'}</b>, s: 'HRMS' },
          { k: t('i21.hrmsRequest'), v: task?.hrmsRequestId ?? '—', s: 'INTF' },
          {
            k: t('i21.w.dob.retirement'),
            v: `${day(dob?.currentRetirement)} → ${day(dob?.newRetirement)}`,
            s: 'CALC',
          },
          { k: t('i21.w.dob.eps'), v: day(dob?.epsCutoff), s: 'CALC' },
          { k: t('i21.w.dob.window'), v: t('i21.w.dob.windowText'), s: 'CALC' },
          { k: t('i21.w.dob.arrears'), v: t('i21.w.dob.arrearsText'), s: 'CALC' },
          { k: t('i21.w.dob.order'), v: task?.orderNo ?? t('i21.w.dob.orderPending'), s: 'ENT' },
          { k: t('i21.copy'), v: t('i21.w.dob.mandatory'), s: 'ENT' },
          {
            k: t('i21.w.dob.hq'),
            v: t(dob?.retirementPast ? 'i21.w.dob.hqYes' : 'i21.w.dob.hqNo'),
            s: 'RULE',
          },
          {
            k: t('i21.w.dob.alert'),
            v: t(dob?.retiresSoon ? 'i21.w.dob.alertYes' : 'i21.w.dob.alertNo'),
            s: 'RULE',
          },
          { k: t('i21.otp'), v: t('i21.w.dob.otp'), s: 'RULE' },
        ]}
      />
      <Tbl
        title={t('i21.w.dob.attention')}
        cols={[
          { t: t('i21.task'), s: 'SYS' },
          { t: t('i21.employee'), s: 'HRMS' },
          { t: t('i21.w.dob.col.dob'), s: 'HRMS' },
          { t: t('i21.w.dob.col.retirement'), s: 'CALC' },
          { t: t('i21.w.dob.col.rule'), s: 'RULE' },
          { t: t('i21.status'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={registerKeys.dobTasks}
          fetchPage={registerPages.dobTasks}
          personal={false}
          empty={<p className="fl-hint">{t('i21.none')}</p>}
        >
          {(rows) =>
            rows.map((r) => (
              <tr key={r.id}>
                <td>{r.taskNo}</td>
                <td>
                  {r.eis} {r.employee}
                </td>
                <td>
                  {r.currentDob ?? '—'} → {r.newDob ?? '—'}
                </td>
                <td>
                  {day(r.newRetirement)}
                  {r.retirementPast ? ` ${t('i21.dob.past')}` : ''}
                </td>
                <td>{t(`i21.w.dob.rule.${dobRule(r)}`, [r.rejectionReason ?? ''])}</td>
                <td>
                  {r.status === 'PENDING_HQ_ESTABLISHMENT' && mayHq && r.approvalId !== null ? (
                    <Act
                      label={t('i21.w.dob.approveHq')}
                      kind="sm"
                      onClick={() => onDecide({ approvalId: r.approvalId ?? 0, title: r.taskNo })}
                    />
                  ) : r.status === 'PENDING_CHECKER' && mayDecide && r.approvalId !== null ? (
                    <Act
                      label={t('i21.w.approve')}
                      kind="sm"
                      onClick={() => onDecide({ approvalId: r.approvalId ?? 0, title: r.taskNo })}
                    />
                  ) : (
                    <span className={`pill ${taskPill(r.status)}`}>{t(`i21.status.${r.status}`)}</span>
                  )}
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
    </div>
  );
}

/** SRS-1-67: the latest order, entered once and linked to every task, and the tasks under it. */
function BulkTab({
  order,
  mayDecide,
  onDecide,
}: {
  order: BulkOrder | null;
  mayDecide: boolean;
  onDecide: (order: BulkOrder) => void;
}) {
  const { t } = useI18n();
  return (
    <div className="g2">
      <KvCard
        title={order ? t('i21.w.bulk.title', [order.orderNo]) : t('i21.w.bulk.none')}
        rows={[
          {
            k: t('i21.w.bulk.number'),
            v: order ? `${order.orderNo} · ${date(order.orderDate)}` : '—',
            s: 'ENT',
          },
          { k: t('i21.authority'), v: order?.authority ?? '—', s: 'ENT' },
          { k: t('i21.copy'), v: t('i21.w.bulk.copy'), s: 'ENT' },
          { k: t('i21.w.bulk.linked'), v: order?.taskCount ?? 0, s: 'SYS' },
          { k: t('i21.w.bulk.values'), v: t('i21.w.bulk.valuesText'), s: 'HRMS' },
          {
            k: t('i21.w.bulk.ready'),
            v: t('i21.w.bulk.readyText', [order?.ready ?? 0, order?.held ?? 0]),
            s: 'SYS',
          },
          { k: t('i21.w.bulk.cpf'), v: t('i21.w.bulk.cpfText'), s: 'RULE' },
        ]}
        after={
          <div className="rf-actbar">
            {mayDecide && order?.approvalId ? (
              <Act label={t('i21.w.approveBulk')} kind="pri" onClick={() => onDecide(order)} />
            ) : (
              <Locked label={t('i21.w.approveBulk')} why={t('i21.w.why.bulkAll')} />
            )}
          </div>
        }
      />
      {order && (
        <Tbl
          title={t('i21.w.bulk.sample')}
          cols={[
            { t: t('i21.task'), s: 'SYS' },
            { t: t('i21.employee'), s: 'HRMS' },
            { t: t('i21.w.bulk.fromTo'), s: 'HRMS' },
            { t: t('i21.w.bulk.check'), s: 'SYS' },
          ]}
        >
          <ServerPagination
            queryKey={registerKeys.orderTasks(order.id)}
            fetchPage={registerPages.orderTasks(order.id)}
            personal={false}
          >
            {(rows) =>
              rows.map((r) => (
                <tr key={r.id}>
                  <td>{r.taskNo}</td>
                  <td>
                    {r.eis} {r.employee}
                  </td>
                  <td>
                    {r.fromValue ?? '—'} → {r.toValue ?? '—'}
                  </td>
                  <td>
                    <span className={`pill ${r.status === 'PENDING_CHECKER' ? 'p-ok' : 'p-warn'}`}>
                      {t(r.status === 'PENDING_CHECKER' ? 'i21.w.bulk.isReady' : `i21.status.${r.status}`)}
                    </span>
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
      )}
    </div>
  );
}

/** SRS-1-71, SRS-17-15: every change received and every HRMS Update Request. */
function RegisterTab({ search, mayExport }: { search: string; mayExport: boolean }) {
  const { t } = useI18n();
  return (
    <Tbl
      title={t('i21.w.reg.title')}
      acts={
        mayExport ? (
          <ExportButton
            href={hrmsRegisterExportUrl({})}
            what={t('i21.register.hrms')}
            label={t('m01.w.exportShort')}
            className="btn sm"
          />
        ) : (
          <Explained label={t('m01.w.exportShort')} why={t('i21.w.why.export')} kind="sm" />
        )
      }
      cols={[
        { t: t('i21.w.reg.received'), s: 'SYS' },
        { t: t('i21.employee'), s: 'HRMS' },
        { t: t('i21.w.reg.field'), s: 'HRMS' },
        { t: t('i21.w.reg.oldNew'), s: 'HRMS' },
        { t: t('i21.w.col.request'), s: 'INTF' },
        { t: t('i21.w.reg.effective'), s: 'HRMS' },
        { t: t('i21.w.reg.order'), s: 'ENT' },
        { t: t('i21.w.reg.maker'), s: 'SYS' },
        { t: t('i21.w.reg.checker'), s: 'SYS' },
        { t: t('i21.status'), s: 'SYS' },
        { t: t('i21.w.reg.days'), s: 'CALC', num: true },
      ]}
    >
      <ServerPagination
        queryKey={registerKeys.register(search)}
        fetchPage={registerPages.register(search)}
        personal={false}
        empty={<p className="fl-hint">{t('i21.none')}</p>}
      >
        {(rows) =>
          rows.map((r) => (
            <tr key={`${r.kind}-${r.reference}`}>
              <td>{dateTime(r.received)}</td>
              <td>
                {r.eis} {r.employee}
              </td>
              <td>{r.kind === 'REQUEST' ? t('i21.w.reg.request', [r.reference, r.field]) : r.field}</td>
              <td>{r.kind === 'REQUEST' ? '—' : `${r.oldValue ?? '—'} → ${r.newValue ?? '—'}`}</td>
              <td>{r.hrmsRequestId ?? (r.kind === 'REQUEST' ? t('i21.w.reg.fetched') : '—')}</td>
              <td>{r.effective ? date(r.effective) : '—'}</td>
              <td>{r.order ?? '—'}</td>
              <td>{r.maker ?? '—'}</td>
              <td>{r.checker ?? '—'}</td>
              <td>{t(r.kind === 'REQUEST' ? `i21.upd.status.${r.status}` : `i21.status.${r.status}`)}</td>
              <td className="num">{r.daysPending ?? '—'}</td>
            </tr>
          ))
        }
      </ServerPagination>
    </Tbl>
  );
}

/** SRS-1-28, FRS 11.11: the fields with pay effect in force, by group; the list is the registry. */
function FieldsTab() {
  const { t } = useI18n();
  const groups = useFieldGroups(true);
  return (
    <Tbl
      title={t('i21.w.fields.title')}
      cols={[
        { t: t('i21.w.fields.group'), s: 'SYS' },
        { t: t('i21.w.fields.fields'), s: 'HRMS' },
        { t: t('i21.w.fields.effect'), s: 'RULE' },
      ]}
      after={
        <div className="bd">
          <Problem error={groups.error} />
          <div className="oq">{t('i21.w.fields.note')}</div>
        </div>
      }
    >
      {(groups.data ?? []).map((g) => (
        <tr key={g.group}>
          <td>{t(`i21.group.${g.group}`)}</td>
          <td>{g.fields.join('; ')}</td>
          <td>{g.payEffects.join('; ')}</td>
        </tr>
      ))}
    </Tbl>
  );
}

/** The order card of the picked task (SRS-G-31): the Maker attaches while the task can still take it. */
function TaskOrders({ task, mayMake }: { task: HrmsTask | null; mayMake: boolean }) {
  const id = task?.id ?? null;
  const stored = useTaskDocuments(id ?? 0, id !== null);
  const types = useDocumentTypes(true);
  const attach = useAttachTaskOrder();
  return (
    <OrderCopyCard
      key={id ?? 0}
      hint=""
      canAttach={mayMake && task !== null && (submittable(task.status) || task.status === 'PENDING_CHECKER')}
      readOnly={!mayMake}
      stored={id === null ? [] : stored.data}
      storedError={stored.error ?? types.error}
      fileHref={(fileId) => taskDocumentUrl(id ?? 0, fileId)}
      today={todayIso()}
      attachmentRequired
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      officeRequired
      onAttach={async (o) => {
        if (id === null) return;
        for (const file of o.files) {
          await attach.mutateAsync({
            id,
            copy: {
              documentType: o.documentType,
              reference: o.reference,
              orderDate: o.orderDate,
              issuingAuthority: o.issuingOffice,
              file,
            },
          });
        }
      }}
    />
  );
}

/** The Checker's decision here, as on the Approvals Inbox: the OTP is asked where the route needs it. */
function DecisionDialog({
  item,
  onClose,
}: {
  item: { approvalId: number; title: string };
  onClose: (done: boolean) => void;
}) {
  const { t } = useI18n();
  const decide = useDecision();
  const dialog = useDialogFocus<HTMLFormElement>();
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const reasoned = reason.trim() !== '' && TEXT_LINES.test(reason);

  async function run(decision: Decision) {
    setError(null);
    try {
      await decide.mutateAsync({ approvalId: item.approvalId, decision, reason: reason.trim() });
      onClose(true);
    } catch (cause) {
      // The Checker closed the OTP step: nothing was decided and nothing went wrong.
      if (!(cause instanceof ApiError && cause.messageKey === 'otp.cancelled')) setError(cause);
    }
  }

  return (
    <div className="fl-modal-backdrop" role="presentation">
      <form
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="i21-decision"
        onSubmit={(e) => e.preventDefault()}
      >
        <h2 id="i21-decision">{t('i21.w.decisionTitle', [item.title])}</h2>
        <p className="fl-hint">{t('i21.w.decisionHint')}</p>
        <label className="fl-field">
          {t('common.reason')}
          <TextAreaField maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </label>
        <Problem error={error} />
        <div className="fl-actions">
          <button type="button" className="btn" onClick={() => onClose(false)}>
            {t('common.cancel')}
          </button>
          {(['RETURN', 'REJECT', 'APPROVE'] as const).map((d) => (
            <button
              key={d}
              type="button"
              className={`btn${d === 'APPROVE' ? ' pri' : ''}`}
              disabled={decide.isPending || !reasoned}
              title={reasoned ? undefined : t('i21.w.why.reason')}
              onClick={() => void run(d)}
            >
              {t(`i21.w.decide.${d}`)}
            </button>
          ))}
        </div>
      </form>
    </div>
  );
}

/**
 * The functions the pack does not draw, closed until pressed (WF-231): the status and Name / DOB filters with the Name /
 * DOB Change Register export, the counts (SRS-1-30), the ticking of tasks for one order (SRS-1-67) and the HRMS Update
 * Requests (SRS-1-70), which the drawn Raise button opens.
 */
function TaskFiltersAndUpdates({ mayExport, onOpen }: { mayExport: boolean; onOpen: (id: number) => void }) {
  const { t } = useI18n();
  const me = useMe();
  const [filter, setFilter] = useState<HrmsTaskFilter>({});
  const [selected, setSelected] = useState<number[]>([]);
  const [ordering, setOrdering] = useState(false);
  const mayOrder = can(me.data, 'I-21', 'ORDER') || can(me.data, 'I-21', 'ORDER_OTP');

  function toggle(id: number, on: boolean) {
    setSelected((now) => (on ? [...now, id] : now.filter((x) => x !== id)));
  }

  /** UI audit #13: a filter change clears the ticks, so a task no longer listed is never sent. */
  function refilter(next: HrmsTaskFilter) {
    setFilter(next);
    setSelected([]);
  }

  return (
    <>
      <div className="toolbar">
        <label className="fl-field">
          {t('i21.status')}
          <select
            value={filter.status ?? ''}
            onChange={(e) => refilter({ ...filter, status: e.target.value || undefined })}
          >
            <option value="">{t('i21.all')}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`i21.status.${s}`)}
              </option>
            ))}
          </select>
        </label>
        <label className="fl-check">
          <input
            type="checkbox"
            checked={filter.nameDob ?? false}
            onChange={(e) => refilter({ ...filter, nameDob: e.target.checked || undefined })}
          />
          {t('i21.nameDobOnly')}
        </label>
        {mayExport && (
          <ExportButton
            href={hrmsRegisterExportUrl(filter)}
            what={t(filter.nameDob ? 'i21.register.nameDob' : 'i21.register.hrms')}
          />
        )}
        {mayOrder && (
          <button
            type="button"
            className="btn pri"
            disabled={!orderable(selected.length)}
            title={orderable(selected.length) ? undefined : t('i21.bulk.why')}
            onClick={() => setOrdering(true)}
          >
            {t('i21.bulk.enter', [selected.length])}
          </button>
        )}
      </div>
      <Counts filter={filter} />
      <Card title={t('i21.tasks')}>
        <div className="wrap">
          <ServerPagination
            queryKey={[...hrmsChangeKeys.list(filter), 'ticks']}
            fetchPage={hrmsChangePage(filter)}
            personal={false}
            empty={<p className="fl-hint">{t('i21.none')}</p>}
          >
            {(rows) => (
              <table className="t">
                <thead>
                  <tr>
                    {mayOrder && (
                      <th>
                        {t('i21.bulk.pick')}
                        <Src s="ENT" />
                      </th>
                    )}
                    <th>
                      {t('i21.task')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('i21.hrmsRequest')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i21.employee')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i21.change')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i21.reason')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i21.status')}
                      <Src s="SYS" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      {mayOrder && (
                        <td>
                          <input
                            type="checkbox"
                            aria-label={t('i21.bulk.pickTask', [row.taskNo])}
                            disabled={!submittable(row.status)}
                            checked={selected.includes(row.id)}
                            onChange={(e) => toggle(row.id, e.target.checked)}
                          />
                        </td>
                      )}
                      <td>
                        <button type="button" className="ia" onClick={() => onOpen(row.id)}>
                          {row.taskNo}
                        </button>
                        <div className="sub2">{dateTime(row.receivedAt)}</div>
                        {row.orderRef && <div className="sub2">{t('i21.bulk.under', [row.orderRef])}</div>}
                      </td>
                      <td>
                        {row.hrmsRequestId ?? '—'}
                        {/* SRS l.3845: how the task was opened (HRMS interface, eSeva request, fallback). */}
                        <div className="sub2">{t(`i21.source.${row.source}`)}</div>
                      </td>
                      <td>
                        <b>{row.eis}</b> <span className="sub2">{row.employee}</span>
                      </td>
                      <td>
                        {row.currentValue ?? '—'} → <b>{row.newValue ?? '—'}</b>
                      </td>
                      <td className="fl-wrap-any">{row.hrmsReason ?? '—'}</td>
                      <td>
                        {t(`i21.status.${row.status}`)}
                        {pending(row.status) && (
                          <div className="sub2">{t('i21.daysPending', [row.daysPending])}</div>
                        )}
                        {row.overdue && <span className="pill p-bad">{t('i21.overdue')}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </ServerPagination>
        </div>
      </Card>
      <HrmsUpdateRequests mine={false} />
      {ordering && (
        <BulkOrderDialog
          taskIds={selected}
          onClose={(sent) => {
            setOrdering(false);
            if (sent) setSelected([]);
          }}
        />
      )}
    </>
  );
}

/** SRS-1-30: how many tasks the register holds, by status and by field, for the filters. */
function Counts({ filter }: { filter: HrmsTaskFilter }) {
  const { t } = useI18n();
  const counts = useRegisterCounts(filter);
  const data = counts.data;
  return (
    <Card title={t('i21.counts', [data?.total ?? 0])}>
      <Problem error={counts.error} />
      {data && (
        <div className="d01-states">
          <ul aria-label={t('i21.status')}>
            {data.byStatus.map((c) => (
              <li key={c.code}>
                {t(`i21.status.${c.code}`)}: <b>{c.count}</b>
              </li>
            ))}
          </ul>
          <ul aria-label={t('i21.field')}>
            {data.byField.map((c) => (
              <li key={c.code}>
                {c.code}: <b>{c.count}</b>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Card>
  );
}

/** SRS-1-67 / SRS-G-31: the order entered once for every ticked task, sent to the Checker as one item. */
function BulkOrderDialog({ taskIds, onClose }: { taskIds: number[]; onClose: (sent: boolean) => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const create = useCreateBulkOrder();
  const dialog = useDialogFocus<HTMLDivElement>(true);
  const [orderNo, setOrderNo] = useState('');
  const [orderDate, setOrderDate] = useState('');
  const [authority, setAuthority] = useState('');
  const [subject, setSubject] = useState('');
  // SRS-1-67 / SRS-G-31: the order details and its copy, once for every task.
  const [documentType, setDocumentType] = useState('');
  const [copy, setCopy] = useState<File | null>(null);
  const types = useDocumentTypes();
  const ready =
    orderComplete({ orderNo, orderDate, authority, held: false }) &&
    PLAIN_TEXT.test(orderNo) &&
    PLAIN_TEXT.test(authority) &&
    TEXT_LINES.test(subject) &&
    copy !== null &&
    documentType !== '' &&
    !create.isPending;

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ready) return;
    try {
      if (!copy) return;
      const created = await create.mutateAsync({
        body: {
          orderNo: orderNo.trim(),
          orderDate,
          issuingAuthority: authority.trim(),
          subject: subject.trim() || undefined,
          taskIds,
        },
        copy: {
          documentType,
          reference: orderNo.trim(),
          orderDate,
          issuingAuthority: authority.trim(),
          file: copy,
        },
      });
      toast(t('i21.bulk.submitted', [created.orderRef, created.tasks]));
      onClose(true);
    } catch {
      // shown by <Problem error={create.error} />
    }
  }

  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div ref={dialog} className="fl-modal" role="dialog" aria-modal="true" aria-labelledby="i21-bulk-title">
        <h2 id="i21-bulk-title">{t('i21.bulk.title', [taskIds.length])}</h2>
        <form className="fl-form" onSubmit={(e) => void onSubmit(e)} aria-label={t('i21.bulk.order')}>
          <label className="fl-field">
            {t('i21.orderNo')}
            <input
              // OPEN-QUESTION: CLR-77 - SRS l.3852 gives 30, DDL v4.0 order_no 60 (the DDL length kept)
              maxLength={60}
              value={orderNo}
              onChange={(e) => setOrderNo(e.target.value)}
            />
          </label>
          <label className="fl-field">
            {t('i21.orderDate')}
            <input type="date" value={orderDate} onChange={(e) => setOrderDate(e.target.value)} />
          </label>
          <label className="fl-field">
            {t('i21.authority')}
            <input
              // OPEN-QUESTION: CLR-77 - SRS l.3852 gives 100, DDL v4.0 issuing_authority 200 (DDL kept)
              maxLength={200}
              value={authority}
              onChange={(e) => setAuthority(e.target.value)}
            />
          </label>
          <label className="fl-field">
            {t('i21.bulk.subject')}
            <TextAreaField maxLength={500} value={subject} onChange={(e) => setSubject(e.target.value)} />
          </label>
          <label className="fl-field">
            {t('ord.type')}
            <select value={documentType} onChange={(e) => setDocumentType(e.target.value)}>
              <option value="">—</option>
              {(types.data ?? []).map((d) => (
                <option key={d.code} value={d.code}>
                  {d.name}
                </option>
              ))}
            </select>
          </label>
          <label className="fl-field">
            {t('i21.copy')}
            <input
              type="file"
              accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png"
              onChange={(e) => setCopy(e.target.files?.[0] ?? null)}
            />
          </label>
          <Problem error={create.error} />
          <div className="fl-actions">
            <button type="button" className="btn" onClick={() => onClose(false)}>
              {t('common.cancel')}
            </button>
            <button type="submit" className="btn pri" disabled={!ready}>
              {t('i21.submit')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/** One task: the locked values, the DOB preview and, for the Maker, the order form. */
function TaskPanel({ id, onClose }: { id: number; onClose: () => void }) {
  const { t } = useI18n();
  const detail = useHrmsTaskDetail(id);
  const data = detail.data;
  return (
    <Card
      title={data ? t('i21.taskTitle', [data.task.taskNo]) : t('i21.task')}
      actions={
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      }
    >
      <Problem error={detail.error} />
      {data && <TaskBody detail={data} />}
    </Card>
  );
}

function TaskBody({ detail }: { detail: HrmsTaskDetail }) {
  const { t } = useI18n();
  const { task, dob } = detail;
  return (
    <>
      <dl className="fl-vocabulary">
        <dt>{t('i21.employee')}</dt>
        <dd>
          {task.eis} {task.employee}
        </dd>
        <dt>{t('i21.field')}</dt>
        <dd>
          {task.fieldLabel} ({t(`i21.group.${task.fieldGroup}`)})
        </dd>
        <dt>{t('i21.current')}</dt>
        <dd>{task.currentValue ?? '—'}</dd>
        <dt>{t('i21.new')}</dt>
        <dd>
          <b>{task.newValue ?? '—'}</b> <span className="sub2">{t('i21.locked')}</span>
        </dd>
        <dt>{t('i21.hrmsRequest')}</dt>
        <dd>{task.hrmsRequestId ?? '—'}</dd>
        <dt>{t('i21.reason')}</dt>
        <dd>{task.hrmsReason ?? '—'}</dd>
        <dt>{t('i21.timing')}</dt>
        <dd>
          {task.receivedBeforeFreeze
            ? t('i21.beforeFreeze', [task.targetPeriod])
            : t('i21.afterFreeze', [task.targetPeriod])}
        </dd>
        {task.otpRequired && (
          <>
            <dt>{t('i21.otp')}</dt>
            <dd>{t('i21.otpAtApproval')}</dd>
          </>
        )}
        {task.returnReason && (
          <>
            <dt>{t('i21.returned')}</dt>
            <dd>{task.returnReason}</dd>
          </>
        )}
        {task.rejectionReason && (
          <>
            <dt>{t('i21.rejected')}</dt>
            <dd>{task.rejectionReason}</dd>
          </>
        )}
      </dl>
      {dob && (
        <div className={dob.retirementPast || dob.retiresSoon ? 'fl-banner' : undefined} role="status">
          <p>{t('i21.dob.title')}</p>
          {dob.retirementAgeConfigured ? (
            <p>
              {t('i21.dob.dates', [date(dob.newBirthDate), date(dob.newRetirement), date(dob.epsCutoff)])}
              {dob.retirementPast && <b> {t('i21.dob.past')}</b>}
              {dob.retiresSoon && <b> {t('i21.dob.soon')}</b>}
            </p>
          ) : (
            <p>{t('i21.dob.notConfigured')}</p>
          )}
        </div>
      )}
      {detail.impact && (
        <div role="status" aria-label={t('i21.impact')}>
          <p>{t('i21.impact')}</p>
          <p>{t('i21.impact.applies', [detail.impact.appliesFrom, detail.impact.paidFrom])}</p>
          <p>
            {detail.impact.arrearFrom && detail.impact.arrearTo
              ? t('i21.impact.arrear', [detail.impact.arrearFrom, detail.impact.arrearTo])
              : t('i21.impact.noArrear')}
          </p>
        </div>
      )}
      {detail.effect && (
        <div role="status">
          <p>{t('i21.effect')}</p>
          <p>{detail.effect}</p>
        </div>
      )}
      <TaskDocuments id={task.id} />
      <OrderForm detail={detail} />
    </>
  );
}

/** SRS-1-28 / SRS-G-31: the order copies on the task, each opened through View order. */
function TaskDocuments({ id }: { id: number }) {
  const { t } = useI18n();
  const docs = useTaskDocuments(id);
  if (!docs.data || docs.data.length === 0) return null;
  return (
    <div>
      <h4>
        {t('i21.copies')}
        <Src s="ENT" />
      </h4>
      <ul className="fl-list">
        {docs.data.map((d) => (
          <li key={d.id}>
            <a href={taskDocumentUrl(id, d.id)} download>
              {t('i21.viewOrder')}
            </a>{' '}
            {d.fileName}
            {d.referenceNumber ? ` (${d.referenceNumber})` : ''}{' '}
            <span className="sub2">
              {d.uploadedBy} {dateTime(d.uploadedAt)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** SRS-1-28 / SRS-G-31: the Maker's order details with the order copy, or "held in HRMS". */
function OrderForm({ detail }: { detail: HrmsTaskDetail }) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const submit = useSubmitHrmsTask();
  const { task } = detail;
  const [orderNo, setOrderNo] = useState(task.orderNo ?? '');
  const [orderDate, setOrderDate] = useState(task.orderDate ?? '');
  const [authority, setAuthority] = useState(task.issuingAuthority ?? '');
  const [held, setHeld] = useState(task.orderHeldInHrms);
  const [documentType, setDocumentType] = useState('');
  const [copy, setCopy] = useState<File | null>(null);
  const types = useDocumentTypes();
  const attached = useTaskDocuments(task.id);
  const hasCopy = (attached.data?.length ?? 0) > 0;
  const mayMake =
    can(me.data, 'I-21', 'SUBMIT') || can(me.data, 'I-21', 'SUBMIT_OTP') || can(me.data, 'I-21', 'SUBMIT_HQ');
  if (!mayMake) return null;
  const why = !submittable(task.status)
    ? t('i21.why.state', [t(`i21.status.${task.status}`)])
    : !detail.applicable
      ? t('i21.why.notApplicable')
      : null;
  const ready =
    why === null &&
    orderComplete({ orderNo, orderDate, authority, held }) &&
    PLAIN_TEXT.test(orderNo) &&
    PLAIN_TEXT.test(authority) &&
    (held || hasCopy || (copy !== null && documentType !== '')) &&
    !submit.isPending;

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ready) return;
    try {
      await submit.mutateAsync({
        id: task.id,
        body: held
          ? { heldInHrms: true }
          : { orderNo: orderNo.trim(), orderDate, issuingAuthority: authority.trim(), heldInHrms: false },
        copy:
          !held && copy !== null
            ? {
                documentType,
                reference: orderNo.trim(),
                orderDate,
                issuingAuthority: authority.trim(),
                file: copy,
              }
            : undefined,
      });
      toast(t('i21.submitted', [task.taskNo]));
    } catch {
      // shown by <Problem error={submit.error} />
    }
  }

  return (
    <form className="fl-form" onSubmit={(e) => void onSubmit(e)} aria-label={t('i21.order')}>
      <h4>
        {t('i21.order')}
        <Src s="ENT" />
      </h4>
      <label className="fl-check">
        <input type="checkbox" checked={held} onChange={(e) => setHeld(e.target.checked)} />
        {t('i21.heldInHrms')}
      </label>
      {!held && (
        <>
          <label className="fl-field">
            {t('i21.orderNo')}
            <input
              // OPEN-QUESTION: CLR-77 - SRS l.3852 gives 30, DDL v4.0 order_no 60 (the DDL length kept)
              maxLength={60}
              value={orderNo}
              onChange={(e) => setOrderNo(e.target.value)}
            />
          </label>
          <label className="fl-field">
            {t('i21.orderDate')}
            <input type="date" value={orderDate} onChange={(e) => setOrderDate(e.target.value)} />
          </label>
          <label className="fl-field">
            {t('i21.authority')}
            <input
              // OPEN-QUESTION: CLR-77 - SRS l.3852 gives 100, DDL v4.0 issuing_authority 200 (DDL kept)
              maxLength={200}
              value={authority}
              onChange={(e) => setAuthority(e.target.value)}
            />
          </label>
          <label className="fl-field">
            {t('ord.type')}
            <select value={documentType} onChange={(e) => setDocumentType(e.target.value)}>
              <option value="">—</option>
              {(types.data ?? []).map((d) => (
                <option key={d.code} value={d.code}>
                  {d.name}
                </option>
              ))}
            </select>
          </label>
          <label className="fl-field">
            {t('i21.copy')}
            <input
              type="file"
              accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png"
              onChange={(e) => setCopy(e.target.files?.[0] ?? null)}
            />
          </label>
          {hasCopy && <p className="fl-hint">{t('i21.copyAttached')}</p>}
        </>
      )}
      <Problem error={submit.error} />
      <div className="fl-actions">
        <button type="submit" className="btn pri" disabled={!ready} title={why ?? undefined}>
          {t('i21.submit')}
        </button>
      </div>
    </form>
  );
}
