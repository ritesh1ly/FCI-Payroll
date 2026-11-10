'use client';

import { useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, periodLabel, useMe } from '@/shared/session';
import {
  amount,
  ConfirmDialog,
  dateTime,
  Problem,
  ServerPagination,
  Src,
  useDialogFocus,
  BulkJobButton,
  usePdfDownload,
  useToast,
} from '@/shared/ui';
import { Act, Explained, KvCard, Locked, Tbl, Tiles, Toolbar, WfTile } from '@/shared/ui/wireframe';
import type { MemberPayslip, PayslipLevel, PayslipRow } from '../api/outputsApi';
import {
  firstOpenRow,
  firstPeriod,
  monthOf,
  noSlipKey,
  PAYSLIP_LEVELS,
  slipRows,
  statusPill,
} from '../business/outputRules';
import {
  outputKeys,
  outputPages,
  useGeneratePayslips,
  useMemberPayslip,
  usePayslipPeriods,
  usePayslipSummary,
} from '../orchestration/outputsQueries';

const DASH = '—';

/**
 * O-01 Payslip as Wireframes v6.1 draws it (OD-121; SRS-17-1, SRS-17-7, SRS-18-12, SRS l.3381-3407, FRS §6.21): the
 * level and Period filters (the months come from the cycles, OD-123), the four tiles, the payslip of the selected
 * register row, the Payslip Register paged on the server and the labour lines. The Payroll Maker generates a locked
 * month in bulk (a period not locked is refused, SRS l.3389). The PDF actions (OD-144) download the server's PDF of the
 * committed run, masked as on screen: the selected payslip, a register row, the Preview, and the Bulk PDF of the filtered
 * register as a background job (OD-149, SRS-NF-2: a ZIP of single PDFs, a held or suppressed row has none, the
 * requester notified and downloading it here). Every role with O-01 VIEW may download. E-mail is drawn disabled (A-36; SRS-17-1 stays as the rule).
 * The screen opens on the newest month with results. Members read their own payslip in eSeva, never here.
 */
export function PayslipScreen() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const me = useMe();
  const periods = usePayslipPeriods();
  const [picked, setPicked] = useState('');
  const [level, setLevel] = useState<PayslipLevel>('STAFF');
  const [text, setText] = useState('');
  const [selected, setSelected] = useState<PayslipRow | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const yymm = picked || firstPeriod(periods.data);
  const summary = usePayslipSummary(yymm, level);
  const s = summary.data;
  const generate = useGeneratePayslips();
  const noSlip = noSlipKey(selected);
  const slip = useMemberPayslip(noSlip === null && selected ? selected.eis : '', yymm);
  const shown = useCallback((page: PayslipRow[]) => {
    setSelected((prev) => page.find((r) => r.eis === prev?.eis) ?? firstOpenRow(page));
  }, []);

  const month = yymm ? periodLabel(monthOf(yymm), locale) : DASH;
  const maker = can(me.data, 'O-01', 'GENERATE');
  const generateWhy = !maker ? t('o01.onlyMaker') : s?.locked ? null : t('o01.why.notLocked');
  const emailWhy = t('o01.why.email');
  const pdf = usePdfDownload();
  const memberPdf = (eis: string) =>
    void pdf.download(
      `/v1/payslips/members/${encodeURIComponent(eis)}/${yymm}/pdf`,
      `payslip-${eis}-${yymm}.pdf`,
    );
  const bulkQuery = new URLSearchParams({ yymm, level }).toString();

  function changeFilter(apply: () => void) {
    setSelected(null);
    apply();
  }

  async function runGenerate() {
    setConfirming(false);
    setError(null);
    try {
      const done = await generate.mutateAsync({ yymm, level });
      toast(t('o01.generated', [done.written, done.alreadyGenerated]));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {noSlip === null ? (
          <Act label={t('o01.act.preview')} onClick={() => setPreviewing(true)} />
        ) : (
          <Explained label={t('o01.act.preview')} why={t(noSlip)} />
        )}
        {yymm === '' ? (
          <Explained label={t('o01.act.bulkPdf')} why={t('o01.noPeriods')} />
        ) : (
          // OD-149, SRS-NF-2: a background job (a ZIP of the unit's payslips of a locked month).
          <BulkJobButton
            label={t('o01.act.bulkPdf')}
            statusPath={`/v1/payslip-bulk-files?${bulkQuery}`}
            requestPath="/v1/payslip-bulk-files"
            requestBody={{ yymm, level }}
            filePath={(id) => `/v1/payslip-bulk-files/${id}/file`}
            fileName={`payslips-${yymm}.zip`}
            why={!s ? t('common.loading') : s.locked ? null : t('o01.why.notLocked')}
          />
        )}
        {generateWhy === null ? (
          <Act label={t('o01.act.generate')} kind="pri" onClick={() => setConfirming(true)} />
        ) : (
          <Locked label={t('o01.act.generate')} why={generateWhy} kind={maker ? 'pri' : undefined} />
        )}
      </div>
      <Toolbar
        search={text}
        onSearch={(value) => changeFilter(() => setText(value.slice(0, 60)))}
        exportControl={<Explained label={t('wf.export')} why={t('reason.noExport')} />}
      >
        <select
          aria-label={t('o01.filter.level')}
          value={level}
          onChange={(e) => changeFilter(() => setLevel(e.target.value as PayslipLevel))}
        >
          {PAYSLIP_LEVELS.map((l) => (
            <option key={l} value={l}>
              {t(`o01.level.${l}`)}
            </option>
          ))}
        </select>
        <select
          aria-label={t('o01.filter.period')}
          value={yymm}
          onChange={(e) => changeFilter(() => setPicked(e.target.value))}
        >
          {(periods.data ?? []).map((p) => (
            <option key={p.yymm} value={p.yymm}>
              {t('o01.period', [periodLabel(monthOf(p.yymm), locale)])}
            </option>
          ))}
        </select>
      </Toolbar>
      <Problem error={periods.error ?? summary.error ?? error ?? pdf.error} />
      <Tiles>
        <WfTile
          icon="🧾"
          label={t('o01.tile.toGenerate')}
          src="CALC"
          big={amount(s?.toGenerate ?? 0)}
          sub={t('o01.tile.toGenerateSub')}
        />
        <WfTile
          icon="📤"
          label={t('o01.tile.published')}
          src="SYS"
          big={amount(s?.published ?? 0)}
          sub={t('o01.tile.publishedSub')}
        />
        <WfTile
          icon="🚫"
          label={t('o01.tile.suppressed')}
          src="ENT"
          tone="bad"
          big={amount(s?.suppressed ?? 0)}
          sub={t('o01.tile.suppressedSub')}
        />
        <WfTile
          icon="📅"
          label={t('o01.tile.lastPublished')}
          src="SYS"
          tone="ok"
          big={s?.lastPublished ? periodLabel(monthOf(s.lastPublished), locale) : DASH}
          sub={
            s?.lastPublished
              ? t('o01.tile.lastPublishedSub', [dateTime(s.lastPublishedAt), amount(s.lastPublishedCount)])
              : t('o01.tile.noneYet')
          }
        />
      </Tiles>
      <div className="g2">
        <SlipCard
          row={selected}
          slip={slip.data}
          month={month}
          emptyWhy={noSlip === null ? null : t(noSlip)}
          onPdf={selected && noSlip === null ? () => memberPdf(selected.eis) : null}
          emailWhy={emailWhy}
          error={slip.error}
        />
        <Tbl
          title={t('o01.register')}
          acts={<Locked label={t('o01.act.emailBatch')} why={emailWhy} kind="sm" />}
          cols={[
            { t: t('o01.col.eis'), s: 'HRMS' },
            { t: t('o01.col.name'), s: 'HRMS' },
            { t: t('o01.col.type'), s: 'RULE' },
            { t: t('o01.col.net'), s: 'CALC', num: true },
            { t: t('o01.col.status'), s: 'SYS' },
            { t: t('o01.col.document'), s: 'SYS' },
          ]}
          after={
            <div className="keep">
              <div className="oq">
                <b>{t('o01.noteLead')}</b> {t('o01.note')}
              </div>
            </div>
          }
        >
          {yymm === '' ? (
            <tr className="fl-state-row">
              <td colSpan={6}>
                <p className="fl-hint">{t('o01.noPeriods')}</p>
              </td>
            </tr>
          ) : (
            <ServerPagination
              queryKey={outputKeys.register(yymm, level, text)}
              fetchPage={outputPages.register(yymm, level, text)}
              empty={<p className="fl-hint">{t('o01.none')}</p>}
              onRows={shown}
            >
              {(page) =>
                page.map((r) => (
                  <RegisterRow
                    key={`${r.eis}-${r.payrollType}`}
                    r={r}
                    selected={selected?.eis === r.eis}
                    onSelect={() => setSelected(r)}
                    onPdf={() => memberPdf(r.eis)}
                  />
                ))
              }
            </ServerPagination>
          )}
        </Tbl>
      </div>
      <div className="g2 fl-mt">
        <KvCard
          title={t('o01.labour')}
          rows={[
            { k: t('o01.labour.mgw'), v: t('o01.labour.mgwV'), s: 'CALC' },
            { k: t('o01.labour.dps'), v: t('o01.labour.dpsV'), s: 'CALC' },
            { k: t('o01.labour.ir'), v: t('o01.labour.irV'), s: 'CALC' },
            { k: t('o01.labour.incentive'), v: t('o01.labour.incentiveV'), s: 'CALC' },
            { k: t('o01.labour.ot'), v: t('o01.labour.otV'), s: 'CALC' },
            { k: t('o01.labour.nph'), v: t('o01.labour.nphV'), s: 'CALC' },
            { k: t('o01.labour.attendance'), v: t('o01.labour.attendanceV'), s: 'CALC' },
            { k: t('o01.labour.rate'), v: <b>{t('o01.labour.rateV')}</b>, s: 'RULE' },
            { k: t('o01.labour.scope'), v: t('o01.labour.scopeV'), s: 'ENT' },
          ]}
        />
      </div>
      {previewing && slip.data && (
        <PayslipDocument
          slip={slip.data}
          month={month}
          onPdf={() => memberPdf(slip.data.eis)}
          onClose={() => setPreviewing(false)}
        />
      )}
      {confirming && (
        <ConfirmDialog
          title={t('o01.sheet.title')}
          confirmLabel={t('o01.sheet.confirm')}
          busy={generate.isPending}
          onCancel={() => setConfirming(false)}
          onConfirm={() => void runGenerate()}
        >
          <p>
            <b>{t('o01.sheet.what', [t(`o01.level.${level}`), month])}</b>
          </p>
          <div className="kv">
            <div className="k">{t('o01.sheet.role')}</div>
            <div>{t('o01.sheet.roleV')}</div>
            <div className="k">{t('o01.sheet.pre')}</div>
            <div>{t('o01.sheet.preV')}</div>
            <div className="k">{t('o01.sheet.writes')}</div>
            <div>{t('o01.sheet.writesV')}</div>
            <div className="k">{t('o01.sheet.effect')}</div>
            <div>{t('o01.sheet.effectV')}</div>
          </div>
        </ConfirmDialog>
      )}
    </div>
  );
}

/** A row of the Payslip Register; the row selects the payslip drawn beside it. */
function RegisterRow({
  r,
  selected,
  onSelect,
  onPdf,
}: {
  r: PayslipRow;
  selected: boolean;
  onSelect: () => void;
  onPdf: () => void;
}) {
  const { t } = useI18n();
  const held = r.status === 'HELD';
  const none = noSlipKey(r);
  const type = t(`o01.type.${r.payrollType}`, [r.payPattern ?? DASH]);
  return (
    <tr
      data-reference={r.eis}
      aria-selected={selected}
      className={selected ? 'fl-row-selected' : undefined}
      onClick={onSelect}
    >
      <td>
        <b>{r.eis}</b>
      </td>
      <td>{r.name}</td>
      <td>{type}</td>
      <td className="num">{held ? DASH : amount(r.net)}</td>
      <td>
        <span className={`pill ${statusPill(r.status)}`}>{t(`o01.status.${r.status}`)}</span>
      </td>
      <td>
        {none ? (
          <button type="button" className="ia off pdf" disabled title={t(none)} data-why={t(none)}>
            {t('o01.act.rowPdf')}
          </button>
        ) : (
          <button
            type="button"
            className="ia pdf"
            onClick={(event) => {
              event.stopPropagation();
              onPdf();
            }}
          >
            {t('o01.act.rowPdf')}
          </button>
        )}
      </td>
    </tr>
  );
}

/** The drawn "Payslip — EIS ..." table of the selected row; a held or suppressed row shows why it has none. */
function SlipCard({
  row,
  slip,
  month,
  emptyWhy,
  onPdf,
  emailWhy,
  error,
}: {
  row: PayslipRow | null;
  slip: MemberPayslip | undefined;
  month: string;
  emptyWhy: string | null;
  /** The PDF of the selected payslip; null when the row has none (emptyWhy says why). */
  onPdf: (() => void) | null;
  emailWhy: string;
  error: unknown;
}) {
  const { t } = useI18n();
  const title = row
    ? t(slip?.provisional ? 'o01.slipProvisional' : 'o01.slip', [row.eis, row.name, month])
    : t('o01.slipNone');
  return (
    <Tbl
      title={title}
      acts={
        <>
          {onPdf ? (
            <Act label={t('o01.act.pdf')} kind="sm" onClick={onPdf} />
          ) : (
            <Explained label={t('o01.act.pdf')} why={emptyWhy ?? t('o01.why.selectRow')} kind="sm" />
          )}
          <Locked label={t('o01.act.email')} why={emailWhy} kind="sm" />
        </>
      }
      cols={[
        { t: t('o01.col.earnings'), s: 'CALC' },
        { t: t('o01.col.amount'), s: 'CALC', num: true },
        { t: t('o01.col.deductions'), s: 'CALC' },
        { t: t('o01.col.amount'), s: 'CALC', num: true },
      ]}
      foot={
        slip && (
          <tr>
            <td>
              <b>{t('o01.gross')}</b>
            </td>
            <td className="num">
              <b>{amount(slip.gross)}</b>
            </td>
            <td>
              <b>{t('o01.totalDeductions')}</b>
            </td>
            <td className="num">
              <b>{amount(slip.totalDeductions)}</b>
            </td>
          </tr>
        )
      }
      after={<Problem error={error} />}
    >
      {slip ? (
        slipRows(slip.earnings, slip.deductions).map(([e, d], i) => (
          <tr key={`${e?.payCode}-${d?.payCode}-${i}`}>
            <td>{e?.name ?? ''}</td>
            <td className="num">{e ? amount(e.amount) : ''}</td>
            <td>{d?.name ?? ''}</td>
            <td className="num">{d ? amount(d.amount) : ''}</td>
          </tr>
        ))
      ) : error ? null : (
        <tr className="fl-state-row">
          <td colSpan={4}>
            <p className="fl-hint">{emptyWhy ?? t('common.loading')}</p>
          </td>
        </tr>
      )}
    </Tbl>
  );
}

/** The Preview dialog (the pack's openDoc): masked figures of the committed run; Download PDF (OD-144). */
function PayslipDocument({
  slip,
  month,
  onPdf,
  onClose,
}: {
  slip: MemberPayslip;
  month: string;
  onPdf: () => void;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLDivElement>();
  const title = t('o01.doc.title', [month, slip.name]);
  return (
    <div
      className="fl-modal-backdrop"
      role="presentation"
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose();
      }}
    >
      <div ref={dialog} className="fl-modal fl-modal-wide" role="dialog" aria-modal="true" aria-label={title}>
        <h2>{title}</h2>
        <div className="docpaper">
          <div className="wm">{t('o01.doc.layout')}</div>
          <h2>{t('o01.doc.org')}</h2>
          <div className="dsub">
            {t('o01.doc.month', [month])}{' '}
            {slip.provisional && <b className="fl-doc-provisional">{t('o01.doc.provisional')}</b>}
            {slip.office ? ` · ${slip.office}` : ''}
          </div>
          <table>
            <tbody>
              <tr>
                <th scope="row">{t('o01.doc.eis')}</th>
                <td>{slip.eis}</td>
                <th scope="row">{t('o01.doc.name')}</th>
                <td>{slip.name}</td>
              </tr>
              <tr>
                <th scope="row">{t('o01.doc.designation')}</th>
                <td>{slip.designation ?? DASH}</td>
                <th scope="row">{t('o01.doc.pattern')}</th>
                <td>{slip.payPattern ?? DASH}</td>
              </tr>
              <tr>
                <th scope="row">{t('o01.doc.pan')}</th>
                <td>{slip.panMasked ?? DASH}</td>
                <th scope="row">{t('o01.doc.bank')}</th>
                <td>{slip.bankAccountMasked ?? DASH}</td>
              </tr>
            </tbody>
          </table>
          <div className="band">{t('o01.doc.band')}</div>
          <table>
            <tbody>
              <tr>
                <th>
                  {t('o01.col.earnings')} <Src s="CALC" />
                </th>
                <th className="rt">
                  {t('o01.col.amount')} <Src s="CALC" />
                </th>
                <th>
                  {t('o01.col.deductions')} <Src s="CALC" />
                </th>
                <th className="rt">
                  {t('o01.col.amount')} <Src s="CALC" />
                </th>
              </tr>
              {slipRows(slip.earnings, slip.deductions).map(([e, d], i) => (
                <tr key={`${e?.payCode}-${d?.payCode}-${i}`}>
                  <td>{e?.name ?? ''}</td>
                  <td className="rt">{e ? amount(e.amount) : ''}</td>
                  <td>{d?.name ?? ''}</td>
                  <td className="rt">{d ? amount(d.amount) : ''}</td>
                </tr>
              ))}
              <tr>
                <th scope="row" className="rt">
                  {t('o01.doc.gross')}
                </th>
                <td className="rt">
                  <b>{amount(slip.gross)}</b>
                </td>
                <th scope="row" className="rt">
                  {t('o01.doc.deductions')}
                </th>
                <td className="rt">
                  <b>{amount(slip.totalDeductions)}</b>
                </td>
              </tr>
            </tbody>
          </table>
          <div className="band">{t('o01.doc.net', [amount(slip.net)])}</div>
          {slip.provisional && (
            <div className="sig">
              <span>{t('o01.doc.notLocked', [month])}</span>
            </div>
          )}
        </div>
        <div className="rf-actbar">
          <span className="sub2 fl-grow">{t('o01.doc.committed')}</span>
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
          <button type="button" className="btn" onClick={() => window.print()}>
            {t('o01.doc.print')}
          </button>
          <Act label={t('o01.doc.pdf')} kind="pri" onClick={onPdf} />
        </div>
      </div>
    </div>
  );
}
