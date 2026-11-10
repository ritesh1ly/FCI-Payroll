'use client';

import { type FormEvent, useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, periodLabel, useMe } from '@/shared/session';
import {
  amount,
  dateTime,
  ExportButton,
  Problem,
  ReasonDialog,
  ServerPagination,
  useDialogFocus,
  useToast,
  TextAreaField,
  withBase,
} from '@/shared/ui';
import {
  Act,
  Explained,
  Locked,
  Panel,
  type PanelItem,
  RowActs,
  Tbl,
  Tiles,
  WfTile,
} from '@/shared/ui/wireframe';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { businessToday } from '@/shared/time';
import type { MdpEntry, MdpFilter } from '../api/mdpApi';
import {
  mdpExportUrl,
  mdpOrderUrl,
  mdpPage,
  useAttachMdpOrder,
  useMdpAction,
  useMdpDocumentTypes,
  useMdpOrders,
  useMdpHeads,
  useMdpPeriods,
  useMdpSummary,
} from '../orchestration/mdpQueries';

const PLAIN_TEXT = /^[\x20-\x7E\t\r\n]*$/;

/** YYYY-MM as the register's title prints it (YYYYMM, the period key of the pack). */
const yymm = (period: string | null | undefined) => (period ? period.replace('-', '') : '—');

/**
 * I-19 Maintain Direct Payroll (MDP) as Wireframes v6.1 draws it (a frozen requirement per OD-121; plan 05
 * phase 05g; SRS-15-2, SRS-15-19, SRS-15-22, SRS-G-37, BR-CYC-010, BR-CYC-018, FRS §6.11.2): "＋ New MDP Entry"
 * and "⬇ Export register" over the screen; the four tiles (window, entries of the window, permitted heads,
 * value); the permitted heads (M-16 MDP_PERMITTED_COMPONENT, every other head blocked); the MDP Transaction
 * Register of the window's period with Filter and Export (the filters: period, status from the
 * MDP_ENTRY_STATUS list, head, employee number); 👁 View opens an entry (and its cancellation for the Maker),
 * ✎ Edit amends it, ✔ takes the Checker to the Approvals Inbox; the controls and what the screen does not do.
 * The Payroll Maker captures in the MDP window of a locked period, amends and cancels; the Payroll Checker
 * decides each on W-01. Nothing is deleted: cancelled entries stay listed (§6.5).
 */
export function DirectPayroll() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const heads = useMdpHeads();
  const periods = useMdpPeriods();
  const summary = useMdpSummary();
  const action = useMdpAction();
  const [filter, setFilter] = useState<MdpFilter>({});
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [capturing, setCapturing] = useState(false);
  const [viewing, setViewing] = useState<MdpEntry | null>(null);
  // The order card follows the selected row; the first row is selected when the page loads.
  const [selected, setSelected] = useState<MdpEntry | null>(null);
  const selectFirst = useCallback((rows: MdpEntry[]) => setSelected((prev) => prev ?? rows[0] ?? null), []);
  const [amending, setAmending] = useState<MdpEntry | null>(null);
  const [cancelling, setCancelling] = useState<MdpEntry | null>(null);
  const [error, setError] = useState<unknown>(null);
  const mayCapture = can(me.data, 'I-19', 'CREATE');
  const mayAmend = can(me.data, 'I-19', 'EDIT');
  const mayCancel = can(me.data, 'I-19', 'CANCEL');
  const mayExport = can(me.data, 'I-19', 'EXPORT');
  const checker = can(me.data, 'I-19', 'APPROVE');
  const open = periods.data ?? [];
  const s = summary.data;
  // Said only once the server has answered (never while it loads).
  const noWindow = periods.data?.length === 0 ? t('i19.noWindow') : null;
  const noHeads = heads.data?.length === 0 ? t('error.MDP-422-NOT-CONFIGURED') : null;
  const captureBlocked = noWindow ?? noHeads;
  // As drawn, the register shows the window's period ("— period 202606") unless a filter names another.
  const shown: MdpFilter = { ...filter, period: filter.period ?? s?.period ?? undefined };
  const on = <span className="pill p-ok">{t('i19.ctl.on')}</span>;

  async function run(input: Parameters<typeof action.mutateAsync>[0], done: string) {
    setError(null);
    try {
      await action.mutateAsync(input);
      toast(t(done));
    } catch (cause) {
      setError(cause);
    }
  }

  /** Why the entry cannot change now, or null. */
  function blocked(e: MdpEntry): string | null {
    if (e.status === 'CANCELLED') return t('i19.why.cancelledKept');
    if (e.status === 'POSTED') return t('error.MDP-409-STATE', [t(`i19.status.${e.status}`)]);
    if (e.pendingApprovalId !== null) return t('i19.pending', [e.pendingApprovalId]);
    if (!open.includes(e.period)) return t('i19.windowClosed', [periodLabel(e.period)]);
    return null;
  }

  function exportControl(label: string, small: boolean) {
    return mayExport ? (
      <ExportButton
        href={mdpExportUrl(shown)}
        what={t('i19.auditReport')}
        label={label}
        className={small ? 'btn sm' : 'btn'}
      />
    ) : (
      <Explained label={label} kind={small ? 'sm' : undefined} why={t('x05.why.export')} />
    );
  }

  const headItems: PanelItem[] = [
    ...(heads.data ?? []).map((h): PanelItem => ({
      icon: '✅',
      tone: 'ok',
      title: `${h.payCode} ${h.name}`,
      src: 'RULE',
      text: t('i19.headPermitted'),
    })),
    {
      icon: '⛔',
      tone: 'bad',
      title: t('i19.otherHeads'),
      src: 'RULE',
      text: t('i19.otherHeadsText', [heads.data?.length ?? 0]),
    },
  ];

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {mayCapture && captureBlocked === null && periods.data && heads.data ? (
          <Act label={t('i19.newEntry')} kind="pri" onClick={() => setCapturing(true)} />
        ) : (
          <Locked
            label={t('i19.newEntry')}
            why={mayCapture ? (captureBlocked ?? t('common.loading')) : t('reason.makerOnly')}
          />
        )}
        {exportControl(t('i19.exportRegister'), false)}
      </div>
      <Problem error={heads.error ?? periods.error ?? summary.error ?? error} />
      <Tiles>
        <WfTile
          icon="🪟"
          label={t('i19.tile.window')}
          src="SYS"
          tone="ok"
          big={s ? t(s.windowOpen ? 'i19.tile.open' : 'i19.tile.closed') : '—'}
          sub={
            s?.period
              ? t('i19.tile.windowSub', [yymm(s.period), s.lockedAt ? dateTime(s.lockedAt) : '—'])
              : t('i19.tile.noPeriod')
          }
        />
        <WfTile
          icon="📝"
          label={t('i19.tile.entries')}
          src="SYS"
          big={s ? s.approved + s.pending : '—'}
          sub={t('i19.tile.entriesSub', [s?.approved ?? '—', s?.pending ?? '—'])}
        />
        <WfTile
          icon="🔒"
          label={t('i19.tile.heads')}
          src="RULE"
          big={s?.heads ?? '—'}
          sub={t('i19.tile.headsSub')}
        />
        <WfTile
          icon="₹"
          label={t('i19.tile.value')}
          src="CALC"
          tone="info"
          big={s ? amount(s.value) : '—'}
          sub={t('i19.tile.valueSub')}
        />
      </Tiles>
      <Panel title={t('i19.headsTitle')} items={headItems} />
      <div className="fl-mt">
        <Tbl
          title={shown.period ? t('i19.registerTitle', [yymm(shown.period)]) : t('i19.registerTitleAll')}
          acts={
            <>
              <Act label={t('i19.filterAct')} kind="sm" onClick={() => setFiltersOpen(!filtersOpen)} />
              {exportControl(t('i19.exportAct'), true)}
            </>
          }
          cols={[
            { t: t('i19.col.ref'), s: 'SYS' },
            { t: t('i19.col.member'), s: 'HRMS' },
            { t: t('i19.col.head'), s: 'RULE' },
            { t: t('i19.col.amount'), s: 'ENT', num: true },
            { t: t('i19.col.reason'), s: 'ENT' },
            { t: t('i19.col.maker'), s: 'SYS' },
            { t: t('i19.col.checker'), s: 'SYS' },
            { t: t('i19.col.status'), s: 'SYS' },
            { t: t('i19.col.actions'), s: 'SYS' },
          ]}
          foot={
            s ? (
              <tr>
                <td>
                  <b>{t('i19.approvedTotal')}</b>
                </td>
                <td />
                <td />
                <td className="num">
                  <b>{amount(s.approvedValue)}</b>
                </td>
                <td colSpan={5} />
              </tr>
            ) : undefined
          }
          after={
            filtersOpen ? (
              <form
                className="toolbar fl-filters"
                onSubmit={(e) => {
                  e.preventDefault();
                  const f = new FormData(e.currentTarget);
                  const value = (k: string) => (f.get(k) as string) || undefined;
                  setFilter({
                    period: value('period'),
                    status: value('status'),
                    payCode: value('payCode'),
                    eis: value('eis'),
                  });
                }}
              >
                <label className="fl-field">
                  {t('i19.period')}
                  <input name="period" type="month" defaultValue={shown.period} />
                </label>
                <label className="fl-field">
                  {t('i19.statusCol')}
                  <select name="status" defaultValue={filter.status ?? ''}>
                    <option value="">{t('common.all')}</option>
                    <ReferenceOptions list="MDP_ENTRY_STATUS" />
                  </select>
                </label>
                <label className="fl-field">
                  {t('i19.head')}
                  <select name="payCode" defaultValue={filter.payCode ?? ''}>
                    <option value="">{t('common.all')}</option>
                    {(heads.data ?? []).map((h) => (
                      <option key={h.payCode} value={h.payCode}>
                        {`${h.payCode} ${h.name}`}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="fl-field">
                  {t('i19.eis')}
                  <input name="eis" maxLength={20} defaultValue={filter.eis ?? ''} />
                </label>
                <button type="submit" className="btn">
                  {t('i19.filter')}
                </button>
              </form>
            ) : undefined
          }
        >
          <ServerPagination
            queryKey={['mdp', 'register', shown]}
            fetchPage={mdpPage(shown)}
            empty={<p className="fl-hint">{t('i19.empty')}</p>}
            onRows={selectFirst}
          >
            {(rows) =>
              rows.map((e) => {
                const why = blocked(e);
                return (
                  <tr
                    key={e.id}
                    data-reference={e.reference}
                    aria-selected={selected?.id === e.id}
                    className={selected?.id === e.id ? 'fl-row-selected' : undefined}
                    onClick={() => setSelected(e)}
                  >
                    <td>
                      <b>{e.reference}</b>
                    </td>
                    <td>{`${e.eis} ${e.member}`}</td>
                    <td>{`${e.payCode} ${e.head}`}</td>
                    <td className="num">{amount(e.amount)}</td>
                    <td>{e.reason}</td>
                    <td>{e.enteredBy}</td>
                    <td>{e.checkedBy ?? '—'}</td>
                    <td>
                      <span
                        className={`pill ${e.status === 'APPROVED' || e.status === 'POSTED' ? 'p-ok' : e.status === 'CANCELLED' ? 'p-gray' : 'p-warn'}`}
                      >
                        {t(`i19.status.${e.status}`)}
                      </span>
                      {e.pendingApprovalId !== null && (
                        <div className="sub2">{t('i19.pending', [e.pendingApprovalId])}</div>
                      )}
                    </td>
                    <td onClick={(ev) => ev.stopPropagation()}>
                      <RowActs
                        viewName={`${t('common.view')} ${e.reference}`}
                        editName={`${t('common.edit')} ${e.reference}`}
                        onView={() => setViewing(e)}
                        onEdit={mayAmend && why === null ? () => setAmending(e) : undefined}
                        editWhy={mayAmend ? (why ?? undefined) : t('i19.why.makerEdit')}
                        onApprove={
                          checker && e.status !== 'CANCELLED'
                            ? () => window.location.assign(withBase('/payroll/?s=W-01'))
                            : undefined
                        }
                      />
                    </td>
                  </tr>
                );
              })
            }
          </ServerPagination>
        </Tbl>
      </div>
      <div className="g2 fl-mt">
        <Panel
          title={t('i19.controls')}
          items={[
            {
              icon: '🪟',
              tone: 'info',
              title: t('i19.ctl.window'),
              src: 'RULE',
              text: t('i19.ctl.windowText'),
              right: on,
            },
            {
              icon: '✅',
              tone: 'warn',
              title: t('i19.ctl.makerChecker'),
              src: 'SYS',
              text: t('i19.ctl.makerCheckerText'),
              right: on,
            },
            {
              icon: '📊',
              tone: 'ok',
              title: t('i19.ctl.reportable'),
              src: 'SYS',
              text: t('i19.ctl.reportableText'),
              right: on,
            },
            {
              icon: '🗂',
              tone: 'gray',
              title: t('i19.ctl.audited'),
              src: 'SYS',
              text: t('i19.ctl.auditedText'),
              right: on,
            },
          ]}
        />
        <Panel
          title={t('i19.notDo')}
          items={[
            { icon: '⛔', tone: 'bad', title: t('i19.notDo.salary'), text: t('i19.notDo.salaryText') },
            { icon: '⚠️', tone: 'warn', title: t('i19.notDo.window'), text: t('i19.notDo.windowText') },
          ]}
        />
      </div>
      <EntryOrders entry={selected} mayAttach={mayCapture} />
      {viewing && (
        <EntryView
          entry={viewing}
          why={blocked(viewing)}
          mayCancel={mayCancel}
          onClose={() => setViewing(null)}
          onCancelEntry={() => {
            setCancelling(viewing);
            setViewing(null);
          }}
        />
      )}
      {capturing && (
        <EntryDialog
          title={t('i19.captureTitle')}
          periods={open}
          heads={heads.data ?? []}
          onCancel={() => setCapturing(false)}
          onConfirm={(body) => {
            setCapturing(false);
            void run({ kind: 'capture', body }, 'i19.captured');
          }}
        />
      )}
      {amending && (
        <EntryDialog
          title={t('i19.amendTitle', [amending.reference])}
          fixed={amending}
          periods={[amending.period]}
          heads={heads.data ?? []}
          onCancel={() => setAmending(null)}
          onConfirm={(body) => {
            const id = amending.id;
            setAmending(null);
            void run({ kind: 'amend', id, amount: body.amount, reason: body.reason }, 'i19.amended');
          }}
        />
      )}
      {cancelling && (
        <ReasonDialog
          title={t('i19.cancelTitle', [cancelling.reference])}
          onCancel={() => setCancelling(null)}
          onConfirm={(reason) => {
            const id = cancelling.id;
            setCancelling(null);
            void run({ kind: 'cancel', id, reason }, 'i19.cancelRequested');
          }}
        />
      )}
    </div>
  );
}

/**
 * The drawn "Order copy — attach & store" card (FRS §6.4.3, A-41) for the selected entry: the Maker attaches the
 * MDP authority order while the entry waits for the Checker (ENTERED / AMENDED); everyone in scope views it.
 */
function EntryOrders({ entry, mayAttach }: { entry: MdpEntry | null; mayAttach: boolean }) {
  const { t } = useI18n();
  const orders = useMdpOrders(entry?.id ?? null);
  const types = useMdpDocumentTypes();
  const attach = useAttachMdpOrder();
  const open = entry !== null && (entry.status === 'ENTERED' || entry.status === 'AMENDED');
  return (
    <OrderCopyCard
      key={entry?.id ?? 0}
      hint={t('i19.ord.hint')}
      canAttach={mayAttach && open}
      readOnly={!mayAttach}
      stored={orders.data}
      storedError={orders.error ?? types.error}
      fileHref={(fileId) => mdpOrderUrl(entry?.id ?? 0, fileId)}
      today={businessToday()}
      // The MDP route (approval_workflow_action) does not require an order: "no file attached yet".
      attachmentRequired={false}
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      onAttach={async (o) => {
        if (!entry) return;
        await attach.mutateAsync({
          id: entry.id,
          order: {
            documentType: o.documentType,
            reference: o.reference,
            orderDate: o.orderDate,
            issuingOffice: o.issuingOffice,
          },
          files: o.files,
        });
      }}
    />
  );
}

/** 👁 View: the entry read-only, and its cancellation for the Maker while it may still change (SRS-G-37). */
function EntryView({
  entry,
  why,
  mayCancel,
  onClose,
  onCancelEntry,
}: {
  entry: MdpEntry;
  why: string | null;
  mayCancel: boolean;
  onClose: () => void;
  onCancelEntry: () => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLDivElement>();
  const cancelWhy = mayCancel ? why : t('i19.why.makerEdit');
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('i19.viewTitle', [entry.reference])}
      >
        <h2>{t('i19.viewTitle', [entry.reference])}</h2>
        <div className="kv">
          <span className="k">{t('i19.period')}</span>
          <span>{periodLabel(entry.period)}</span>
          <span className="k">{t('i19.col.member')}</span>
          <span>{`${entry.eis} ${entry.member}`}</span>
          <span className="k">{t('i19.col.head')}</span>
          <span>{`${entry.payCode} ${entry.head}`}</span>
          <span className="k">{t('i19.col.amount')}</span>
          <span>{amount(entry.amount)}</span>
          <span className="k">{t('i19.col.reason')}</span>
          <span>{entry.reason}</span>
          <span className="k">{t('i19.col.maker')}</span>
          <span>{`${entry.enteredBy} · ${dateTime(entry.enteredAt)}`}</span>
          <span className="k">{t('i19.col.checker')}</span>
          <span>
            {entry.checkedBy ? t('i19.checkedBy', [entry.checkedBy, dateTime(entry.decidedAt)]) : '—'}
          </span>
          <span className="k">{t('i19.col.status')}</span>
          <span>{t(`i19.status.${entry.status}`)}</span>
        </div>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
          <button
            type="button"
            className="btn"
            disabled={cancelWhy !== null}
            title={cancelWhy ?? undefined}
            data-why={cancelWhy ?? undefined}
            onClick={onCancelEntry}
          >
            {t('i19.cancelEntry')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** SRS I-19 field table: period, member, head, amount (deduction only) and reason. */
function EntryDialog({
  title,
  periods,
  heads,
  fixed,
  onConfirm,
  onCancel,
}: {
  title: string;
  periods: string[];
  heads: { payCode: number; name: string }[];
  /** An amendment: the period, member and head stay; only the amount and reason change. */
  fixed?: MdpEntry;
  onConfirm: (body: { period: string; eis: string; payCode: number; amount: number; reason: string }) => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLFormElement>();
  const [period, setPeriod] = useState(fixed?.period ?? periods[0] ?? '');
  const [eis, setEis] = useState(fixed?.eis ?? '');
  const [payCode, setPayCode] = useState(String(fixed?.payCode ?? heads[0]?.payCode ?? ''));
  const [value, setValue] = useState(fixed ? String(fixed.amount) : '');
  const [reason, setReason] = useState('');
  const number = Number(value);
  const [whole = '', fraction = ''] = value.split('.');
  // A deduction of Numeric (12,2): digits only, at most ten before and two after the point.
  const wellFormed =
    value.split('.').length <= 2 &&
    whole.length >= 1 &&
    whole.length <= 10 &&
    fraction.length <= 2 &&
    [...whole, ...fraction].every((c) => c >= '0' && c <= '9');
  const ready =
    period !== '' &&
    eis.trim() !== '' &&
    payCode !== '' &&
    wellFormed &&
    number > 0 &&
    reason.trim() !== '' &&
    PLAIN_TEXT.test(reason);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (ready)
      onConfirm({ period, eis: eis.trim(), payCode: Number(payCode), amount: number, reason: reason.trim() });
  }

  return (
    <div className="fl-modal-backdrop" role="presentation">
      <form
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="mdp-title"
        onSubmit={submit}
      >
        <h2 id="mdp-title">{title}</h2>
        <p className="sub2">{t('i19.note')}</p>
        <label className="fl-field">
          {t('i19.period')}
          <select
            name="period"
            value={period}
            disabled={fixed !== undefined}
            onChange={(e) => setPeriod(e.target.value)}
          >
            {periods.map((p) => (
              <option key={p} value={p}>
                {periodLabel(p)}
              </option>
            ))}
          </select>
        </label>
        <label className="fl-field">
          {t('i19.eis')}
          <input
            name="eis"
            maxLength={20}
            value={eis}
            disabled={fixed !== undefined}
            onChange={(e) => setEis(e.target.value)}
          />
        </label>
        <label className="fl-field">
          {t('i19.head')}
          <select
            name="payCode"
            value={payCode}
            disabled={fixed !== undefined}
            onChange={(e) => setPayCode(e.target.value)}
          >
            {heads.map((h) => (
              <option key={h.payCode} value={h.payCode}>
                {`${h.payCode} ${h.name}`}
              </option>
            ))}
          </select>
        </label>
        <label className="fl-field">
          {t('i19.amount')}
          <input
            name="amount"
            inputMode="decimal"
            maxLength={13}
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
        </label>
        <label className="fl-field">
          {t('common.reason')}
          <TextAreaField
            name="reason"
            maxLength={500}
            rows={2}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="btn pri" disabled={!ready}>
            {t('common.submit')}
          </button>
        </div>
      </form>
    </div>
  );
}
