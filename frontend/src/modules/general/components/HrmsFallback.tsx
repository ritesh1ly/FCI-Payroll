'use client';

import { useState, type MouseEvent, type ReactNode } from 'react';
import { useDecision } from '@/modules/workflow';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import {
  Card,
  date,
  dateTime,
  ExportButton,
  Problem,
  ReasonDialog,
  ServerPagination,
  TextAreaField,
  useDialogFocus,
  useToast,
} from '@/shared/ui';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { Act, Explained, KvCard, Locked, Panel, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type {
  Enablement,
  FallbackRejection,
  FallbackType,
  FallbackUpload,
  ReconciliationRow,
} from '../api/generalApi';
import {
  approveBlock,
  defaultUpload,
  enablementPill,
  isAsciiText,
  monthName,
  monthShort,
  monthsBack,
  officeCodes,
  orderUpload,
  reconUnit,
  tone,
  uploadEditable,
  uploadSubmitBlock,
  yymmOf,
} from '../business/generalRules';
import {
  fileUrl,
  generalKeys,
  generalPages,
  reconciliationExportUrl,
  uploadsExportUrl,
  useCancelUpload,
  useDocumentTypes,
  useFallbackSummary,
  useFallbackUploads,
  useHrmsInterfaceStatus,
  useLiveEnablements,
  useProposeEnablement,
  useStoredOrders,
  useSubmitUpload,
  useUploadFile,
  useUploadOrder,
  useUploadRejections,
  useWithdrawEnablement,
} from '../orchestration/generalQueries';
import { todayIso } from './OrderFields';
import { Src, Th } from './Parts';

const LIVE = new Set(['OPEN', 'UPLOADED']);
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** A count as the pack prints it (4,212). */
function count(value: number): string {
  return value.toLocaleString('en-IN');
}

/**
 * X-07 HRMS Fallback Upload as Wireframes v6.1 draws it (a frozen requirement per OD-121; SRS-G-46, SRS-G-47,
 * SRS-EH-11, SRS-G-21, BR-INT-011): 📊 Reconciliation Report, ⚑ Enable upload for the Administrator at Headquarters,
 * ⬆ Upload file for the Payroll Maker of an enabled office, ✅ Approve upload (OTP) for the Payroll Checker, a
 * different user from the Maker (OTP HRMS_FALLBACK_UPLOAD_APPROVAL); the four tiles; the enablements by Headquarters
 * (a row picks the upload shown below) with History; what can and cannot be uploaded; the "Upload" card with its
 * validation result; the HRMS Fallback Upload Reconciliation (Annexure A.11) with Export; the order card of fallback
 * (b). The upload register (submission, cancellation, the list export) and the withdrawal of an enablement are not
 * drawn: they open from History (WF-198), the record lines from "Validation". Reconciled lines are read as stored;
 * the matching with HRMS itself comes with the HRMS interface (SRS-G-47, PENDING).
 */
export function HrmsFallback() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const mayEnable = can(me.data, 'X-07', 'CREATE');
  const mayUpload = can(me.data, 'X-07', 'SUBMIT');
  const mayApprove = can(me.data, 'X-07', 'APPROVE');
  const mayExport = can(me.data, 'X-07', 'EXPORT');
  const live = useLiveEnablements();
  const summary = useFallbackSummary();
  const hrms = useHrmsInterfaceStatus();
  const [picked, setPicked] = useState<Enablement | null>(null);
  const [chosen, setChosen] = useState<FallbackUpload | null>(null);
  const feed = useFallbackUploads(picked?.id ?? null);
  const everyUpload = useFallbackUploads(null);
  const [enableOpen, setEnableOpen] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [linesOpen, setLinesOpen] = useState(false);
  const [deciding, setDeciding] = useState(false);

  const open = live.data ?? [];
  const uploads = feed.data?.items ?? [];
  const pending = (everyUpload.data?.items ?? []).find((u) => u.status === 'PENDING_APPROVAL');
  const upload = chosen ? (uploads.find((u) => u.id === chosen.id) ?? chosen) : defaultUpload(uploads);
  const block = approveBlock(upload, me.data?.loginId);

  function pick(e: Enablement) {
    setPicked(e);
    setChosen(null);
    setLinesOpen(false);
  }

  function choose(u: FallbackUpload) {
    setChosen(u);
    setLinesOpen(false);
  }

  function approveAct(kind?: 'pri') {
    if (!mayApprove) return <Locked label={t('x07.w.act.approve')} why={t('x07.w.why.approve')} />;
    return block ? (
      <Explained label={t('x07.w.act.approve')} why={t(block)} kind={kind} />
    ) : (
      <Act label={t('x07.w.act.approve')} kind={kind} onClick={() => setDeciding(true)} />
    );
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act
          label={t('x07.w.act.recon')}
          onClick={() => document.getElementById('x07-recon')?.scrollIntoView?.({ behavior: 'smooth' })}
        />
        {mayEnable ? (
          <Act label={t('x07.w.act.enable')} kind="pri" onClick={() => setEnableOpen(true)} />
        ) : (
          <Locked label={t('x07.w.act.enable')} why={t('x07.w.why.enable')} />
        )}
        {mayUpload ? (
          <Act label={t('x07.w.act.upload')} onClick={() => setUploadOpen(true)} />
        ) : (
          <Locked label={t('x07.w.act.upload')} why={t('x07.w.why.upload')} />
        )}
        {approveAct('pri')}
      </div>
      <Tiles>
        <WfTile
          icon="🔌"
          label={t('x07.tile.hrms')}
          src="INTF"
          tone={hrms.data?.down === false ? 'ok' : 'bad'}
          bad={hrms.data?.down === true}
          big={hrms.data ? t(hrms.data.down ? 'x07.w.hrmsDown' : 'x07.w.hrmsUp') : '—'}
          sub={hrms.data?.down ? t('x07.w.hrmsDownSub', [dateTime(hrms.data.since)]) : t('x07.w.hrmsUpSub')}
        />
        <WfTile
          icon="⚑"
          label={t('x07.tile.enabled')}
          src="SYS"
          tone="warn"
          big={open.length}
          sub={
            open.length > 0
              ? t('x07.w.enabledSub', [
                  monthShort(open[0]?.payrollYymm),
                  open.filter((e) => e.type === 'ATTENDANCE_LEAVE').length,
                  open.filter((e) => e.type !== 'ATTENDANCE_LEAVE').length,
                ])
              : t('x07.w.enabledNone')
          }
        />
        <WfTile
          icon="✍️"
          label={t('x07.tile.awaiting')}
          src="SYS"
          tone="warn"
          big={summary.data?.awaitingApproval ?? '—'}
          sub={
            pending
              ? t('x07.w.awaitingSub', [pending.office, t(`x07.w.type.${pending.type}`)])
              : t('x07.w.awaitingNone')
          }
        />
        <WfTile
          icon="⚖️"
          label={t('x07.tile.reconcile')}
          src="CALC"
          tone="info"
          big={summary.data?.reconciliationPending ?? '—'}
          sub={t('x07.w.reconcileSub')}
        />
      </Tiles>
      {enableOpen && <EnableForm onClose={() => setEnableOpen(false)} />}
      {uploadOpen && (
        <UploadForm enablements={open} onClose={() => setUploadOpen(false)} onUploaded={choose} />
      )}
      <div className="g2 fl-mt">
        <Enablements picked={picked} onPick={pick} onHistory={() => setHistoryOpen(!historyOpen)} />
        <Panel
          title={t('x07.w.what')}
          items={[
            { icon: 'a', tone: 'ok', title: t('x07.what.a'), src: 'INTF', text: t('x07.w.whatASub') },
            { icon: 'b', tone: 'ok', title: t('x07.what.b'), src: 'INTF', text: t('x07.w.whatBSub') },
            { icon: '✖', tone: 'bad', title: t('x07.what.none'), src: 'RULE', text: t('x07.w.whatNoneSub') },
            {
              icon: '👷',
              tone: 'gray',
              title: t('x07.what.labour'),
              src: 'ENT',
              text: t('x07.w.whatLabourSub'),
            },
          ]}
        />
      </div>
      <div className="g2 fl-mt">
        <UploadCard
          upload={upload}
          enablement={picked}
          mayUpload={mayUpload}
          approve={approveAct('pri')}
          onLines={() => setLinesOpen(!linesOpen)}
        />
        <Reconciliation mayExport={mayExport} />
      </div>
      {linesOpen && upload && <ValidationLines upload={upload} />}
      {historyOpen && (
        <History
          editable={mayUpload}
          mayEnable={mayEnable}
          mayExport={mayExport}
          enablements={open}
          selected={upload?.id ?? null}
          onOpen={(u) => {
            choose(u);
            setLinesOpen(true);
          }}
        />
      )}
      <FallbackOrders
        upload={orderUpload(upload, chosen !== null, uploads)}
        mayUpload={mayUpload}
        mayEnable={mayEnable}
      />
      {deciding && upload && (
        <DecisionDialog
          upload={upload}
          onClose={() => setDeciding(false)}
          onDecided={(message) => {
            setDeciding(false);
            toast(message);
          }}
        />
      )}
    </div>
  );
}

/** Headquarters proposes an enablement; it opens once the HQ Checker approves (X07_CREATE). */
function EnableForm({ onClose }: { onClose: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const propose = useProposeEnablement();
  const empty = { type: '' as FallbackType | '', payrollYymm: '', offices: '', reason: '' };
  const [form, setForm] = useState(empty);
  const [error, setError] = useState<unknown>(null);
  const months = monthsBack(yymmOf(new Date()), 6);
  // DoD 4 (UI #103): the disabled Submit names the first missing field.
  const why =
    form.type === ''
      ? 'x07.why.type'
      : form.payrollYymm === ''
        ? 'x07.why.month'
        : officeCodes(form.offices).length === 0
          ? 'x07.why.offices'
          : !isAsciiText(form.reason, 500)
            ? 'x07.why.reason'
            : null;
  const valid = why === null;

  async function submit() {
    setError(null);
    if (form.type === '') return;
    try {
      const result = await propose.mutateAsync({
        type: form.type,
        payrollYymm: form.payrollYymm,
        offices: officeCodes(form.offices),
        reason: form.reason,
      });
      toast(t('x07.proposed', [result.approvalId]));
      setForm(empty);
      onClose();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('x07.enable')}>
      <Problem error={error} />
      <div className="bd rf-grid">
        <div className="rf-field">
          <label htmlFor="x07-type">{t('x07.type')}</label>
          <Src s="ENT" />
          <select
            id="x07-type"
            value={form.type}
            onChange={(e) => setForm({ ...form, type: e.target.value as FallbackType })}
          >
            <option value="">—</option>
            <ReferenceOptions list="HRMS_FALLBACK_TYPE" />
          </select>
        </div>
        <div className="rf-field">
          <label htmlFor="x07-month">{t('x07.month')}</label>
          <Src s="ENT" />
          <select
            id="x07-month"
            value={form.payrollYymm}
            onChange={(e) => setForm({ ...form, payrollYymm: e.target.value })}
          >
            <option value="">—</option>
            {months.map((m) => (
              <option key={m} value={m}>
                {monthName(m)}
              </option>
            ))}
          </select>
        </div>
        <div className="rf-field">
          <label htmlFor="x07-offices">{t('x07.offices')}</label>
          <Src s="ENT" />
          <input
            id="x07-offices"
            // up to 200 office codes of up to 20 characters, each with its separator (the API's list)
            maxLength={4200}
            value={form.offices}
            onChange={(e) => setForm({ ...form, offices: e.target.value })}
          />
          <span className="hint">{t('x07.officesHint')}</span>
        </div>
        <div className="rf-field span2">
          <label htmlFor="x07-reason">{t('common.reason')}</label>
          <Src s="ENT" />
          <TextAreaField
            id="x07-reason"
            maxLength={500}
            value={form.reason}
            onChange={(e) => setForm({ ...form, reason: e.target.value })}
          />
        </div>
      </div>
      <div className="rf-actbar">
        <button type="button" className="btn" onClick={onClose}>
          {t('common.cancel')}
        </button>
        <button
          type="button"
          className="btn pri"
          disabled={!valid || propose.isPending}
          title={why ? t(why) : undefined}
          onClick={() => void submit()}
        >
          {t('common.submitForApproval')}
        </button>
      </div>
    </Card>
  );
}

/** "Enablement by Headquarters - per cycle and office": a row shows its upload below. */
function Enablements({
  picked,
  onPick,
  onHistory,
}: {
  picked: Enablement | null;
  onPick: (e: Enablement) => void;
  onHistory: () => void;
}) {
  const { t } = useI18n();
  return (
    <Tbl
      title={t('x07.w.enablements')}
      acts={<Act label={t('x07.w.history')} kind="sm" onClick={onHistory} />}
      cols={[
        { t: t('x07.enablement'), s: 'SYS' },
        { t: t('x07.w.col.type'), s: 'ENT' },
        { t: t('x07.cycle'), s: 'ENT' },
        { t: t('x07.offices'), s: 'ENT' },
        { t: t('common.reason'), s: 'ENT' },
        { t: t('x07.enabledBy'), s: 'SYS' },
        { t: t('gen.status'), s: 'SYS' },
      ]}
    >
      <ServerPagination
        queryKey={generalKeys.enablements}
        fetchPage={generalPages.enablements}
        empty={<p className="fl-hint">{t('x07.noEnablements')}</p>}
      >
        {(list) =>
          list.map((e) => {
            const pill = enablementPill(e);
            return (
              <tr
                key={e.id}
                data-reference={e.reference}
                aria-selected={picked?.id === e.id}
                className={picked?.id === e.id ? 'fl-row-selected' : undefined}
                onClick={() => onPick(e)}
              >
                <td>{e.reference}</td>
                <td>{t(`x07.w.typeFull.${e.type}`)}</td>
                <td>{monthName(e.payrollYymm)}</td>
                <td>{e.officeNames.join('; ')}</td>
                <td>{e.reason}</td>
                <td>{e.enabledBy}</td>
                <td>
                  <span className={`pill ${pill.pill}`}>
                    {t(pill.key, [pill.restored ? date(e.hrmsRestoredAt) : ''])}
                  </span>
                  {e.withdrawnReason && <div className="sub2">{e.withdrawnReason}</div>}
                </td>
              </tr>
            );
          })
        }
      </ServerPagination>
    </Tbl>
  );
}

/** The office Maker: upload the HRMS export of an open enablement. */
function UploadForm({
  enablements,
  onClose,
  onUploaded,
}: {
  enablements: Enablement[];
  onClose: () => void;
  onUploaded: (upload: FallbackUpload) => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const upload = useUploadFile();
  const [enablementId, setEnablementId] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<unknown>(null);

  async function send() {
    setError(null);
    if (!file || enablementId === '') return;
    try {
      const u = await upload.mutateAsync({ enablementId: Number(enablementId), file });
      toast(t('x07.uploaded', [u.batchNo, u.acceptedCount, u.rejectedCount]));
      setFile(null);
      onUploaded(u);
      onClose();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('x07.upload')}>
      <Problem error={error} />
      {enablements.length === 0 && <p className="fl-hint">{t('x07.notEnabled')}</p>}
      {enablements.length > 0 && (
        <>
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="x07-enablement">{t('x07.enablement')}</label>
              <select
                id="x07-enablement"
                value={enablementId}
                onChange={(e) => setEnablementId(e.target.value)}
              >
                <option value="">—</option>
                {enablements.map((e) => (
                  <option key={e.id} value={e.id}>
                    {e.reference} · {t(`x07.w.typeFull.${e.type}`)} · {monthName(e.payrollYymm)}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="x07-file">{t('x07.file')}</label>
              <input
                id="x07-file"
                type="file"
                accept=".csv,text/csv,text/plain"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
              <span className="hint">{t('x07.fileHint')}</span>
            </div>
          </div>
        </>
      )}
      <div className="rf-actbar">
        <button type="button" className="btn" onClick={onClose}>
          {t('common.cancel')}
        </button>
        <button
          type="button"
          className="btn pri"
          disabled={file === null || enablementId === '' || upload.isPending}
          onClick={() => void send()}
        >
          {t('x07.uploadValidate')}
        </button>
      </div>
    </Card>
  );
}

/** The validation line as drawn: "4,198 passed · 14 rejected (reason 9; reason 5)". */
function validationText(
  upload: FallbackUpload,
  rejections: FallbackRejection[] | undefined,
  t: ReturnType<typeof useI18n>['t'],
): string {
  const line = t('x07.w.validation', [count(upload.acceptedCount), count(upload.rejectedCount)]);
  const why = (rejections ?? []).map((r) => `${r.reason} ${count(r.count)}`).join('; ');
  return why === '' ? line : `${line} (${why})`;
}

/**
 * The "Upload" card of the upload in hand: file, rows, validation (it opens the records), who uploaded, the source
 * file kept read-only, the Checker, what follows approval. The Maker's next steps on a validated or returned upload
 * appear under it, beside the drawn Approve.
 */
function UploadCard({
  upload,
  enablement,
  mayUpload,
  approve,
  onLines,
}: {
  upload: FallbackUpload | null;
  enablement: Enablement | null;
  mayUpload: boolean;
  approve: ReactNode;
  onLines: () => void;
}) {
  const { t } = useI18n();
  const rejections = useUploadRejections(upload?.id ?? null);
  const none = t('x07.w.notUploaded');
  const reference = upload?.enablementReference ?? enablement?.reference;
  const office = upload?.office ?? enablement?.officeNames.join('; ');
  const type = upload?.type ?? enablement?.type;
  const title =
    reference && office && type
      ? t('x07.w.detail', [reference, office, t(`x07.w.type.${type}`)])
      : t('x07.w.detailNone');

  function openLines(e: MouseEvent) {
    e.preventDefault();
    onLines();
  }

  return (
    <div data-upload={upload?.batchNo ?? ''}>
      <KvCard
        title={title}
        rows={[
          {
            k: t('x07.w.file'),
            s: 'INTF',
            v: upload ? t('x07.w.fileValue', [upload.fileName]) : none,
          },
          {
            k: t('x07.w.rows'),
            s: 'SYS',
            v: upload ? t('x07.w.rowsValue', [count(upload.recordCount)]) : none,
          },
          {
            k: t('x07.w.validationLabel'),
            s: 'SYS',
            v: upload ? (
              <a href="#x07-lines" onClick={openLines}>
                {validationText(upload, rejections.data, t)}
              </a>
            ) : (
              none
            ),
          },
          {
            k: t('x07.uploadedBy'),
            s: 'SYS',
            v: upload ? `${upload.uploadedBy} · ${dateTime(upload.uploadedAt)}` : none,
          },
          {
            k: t('x07.sourceFile'),
            s: 'SYS',
            v:
              upload?.sourceAttachmentId != null ? (
                <a href={fileUrl('hrms-fallback/uploads', upload.id, upload.sourceAttachmentId)}>
                  {t('x07.w.sourceKept')}
                </a>
              ) : (
                none
              ),
          },
          {
            k: t('x07.checker'),
            s: 'SYS',
            v: upload?.checkerUser
              ? t('x07.w.checkerValue', [upload.checkerUser])
              : t('x07.w.checkerPending'),
          },
          { k: t('x07.afterApproval'), s: 'RULE', v: t('x07.w.afterApprovalText') },
        ]}
        after={
          <div className="fl-actions fl-mt-10">
            {approve}
            {mayUpload && upload && uploadEditable(upload) && <MakerSteps upload={upload} />}
          </div>
        }
      />
    </div>
  );
}

/** The Maker's steps on a validated or returned upload: submit it to the Checker, or cancel it (never deleted). */
function MakerSteps({ upload }: { upload: FallbackUpload }) {
  const { t } = useI18n();
  const toast = useToast();
  const submit = useSubmitUpload();
  const cancel = useCancelUpload();
  const [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const block = uploadSubmitBlock(upload);

  async function act(run: () => Promise<unknown>, done: string) {
    setError(null);
    try {
      await run();
      toast(done);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <>
      <button
        type="button"
        className="btn"
        disabled={block !== null || submit.isPending}
        title={block ? t(block) : undefined}
        onClick={() => void act(() => submit.mutateAsync(upload.id), t('x07.submitted', [upload.batchNo]))}
      >
        {t('common.submitForApproval')}
      </button>
      <button type="button" className="btn" onClick={() => setCancelling(true)}>
        {t('gen.cancelRecord')}
      </button>
      <Problem error={error} />
      {cancelling && (
        <ReasonDialog
          maxLength={1000}
          title={t('x07.cancelTitle', [upload.batchNo])}
          onCancel={() => setCancelling(false)}
          onConfirm={(reason) => {
            setCancelling(false);
            void act(
              () => cancel.mutateAsync({ id: upload.id, reason }),
              t('x07.cancelled', [upload.batchNo]),
            );
          }}
        />
      )}
    </>
  );
}

/** A value of the reconciliation: a date as the screens show it, anything else as stored. */
function reconValue(value: string): string {
  return ISO_DATE.test(value) ? date(value) : value;
}

/** "HRMS Fallback Upload Reconciliation (Annexure A.11)": as stored on the lines; Export for a role that may. */
function Reconciliation({ mayExport }: { mayExport: boolean }) {
  const { t } = useI18n();

  function cells(r: ReconciliationRow) {
    if (r.others > 0) {
      return {
        employee: t('x07.w.others', [count(r.others)]),
        uploaded: t('x07.w.match'),
        hrms: t('x07.w.match'),
        effect: t('x07.w.effect.NONE'),
      };
    }
    const unit = r.difference === null ? 'event' : reconUnit(r.item);
    return {
      employee: `${r.employeeNumber ?? '—'} ${r.employeeName ?? ''}`.trim(),
      uploaded: r.uploaded === null ? '—' : reconValue(r.uploaded),
      hrms: r.hrms === null ? t('x07.w.notInHrms') : reconValue(r.hrms),
      effect:
        r.effect === 'NONE'
          ? t('x07.w.effect.NONE')
          : t(`x07.w.effect.${r.effect}.${unit}`, [r.difference ?? '']),
    };
  }

  function nextCycle(r: ReconciliationRow) {
    if (r.status === 'DIFFERENCE') {
      return (
        <span className="pill p-warn">{t(`x07.w.next.${r.effect}`, [monthShort(r.nextCycleYymm)])}</span>
      );
    }
    return r.status === 'NOT_IN_HRMS' ? (
      <span className="pill p-bad">{t('x07.w.notInHrms')}</span>
    ) : (
      <span className="pill p-ok">{t(`x07.w.next.${r.status}`)}</span>
    );
  }

  return (
    <div id="x07-recon">
      <Tbl
        title={t('x07.recon')}
        acts={
          mayExport ? (
            <ExportButton
              className="btn sm"
              href={reconciliationExportUrl()}
              what={t('x07.recon')}
              label={t('x07.w.export')}
            />
          ) : (
            <Explained label={t('x07.w.export')} why={t('x07.w.why.export')} kind="sm" />
          )
        }
        cols={[
          { t: t('x07.w.col.upload'), s: 'SYS' },
          { t: t('x07.w.col.employee'), s: 'HRMS' },
          { t: t('x07.w.col.item'), s: 'SYS' },
          { t: t('x07.w.col.uploaded'), s: 'INTF' },
          { t: t('x07.w.col.hrms'), s: 'HRMS' },
          { t: t('x07.w.col.effect'), s: 'CALC' },
          { t: t('x07.w.col.next'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={generalKeys.reconciliation}
          fetchPage={generalPages.reconciliation}
          empty={<p className="fl-hint">{t('x07.w.reconNone')}</p>}
        >
          {(rows) =>
            rows.map((r) => {
              const c = cells(r);
              return (
                <tr key={`${r.reference}-${r.employeeNumber ?? 'others'}-${r.item}`}>
                  <td>
                    {r.reference} ({monthShort(r.payrollYymm)})
                  </td>
                  <td>{c.employee}</td>
                  <td>{t(`x07.w.item.${r.item}`)}</td>
                  <td>{c.uploaded}</td>
                  <td>{c.hrms}</td>
                  <td>{c.effect}</td>
                  <td>{nextCycle(r)}</td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
    </div>
  );
}

/** The Checker's decision here (SRS X-07 "approves with OTP", "returns the upload with a mandatory reason"). */
function DecisionDialog({
  upload,
  onClose,
  onDecided,
}: {
  upload: FallbackUpload;
  onClose: () => void;
  onDecided: (message: string) => void;
}) {
  const { t } = useI18n();
  const decide = useDecision();
  const dialog = useDialogFocus<HTMLFormElement>();
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const reasoned = isAsciiText(reason, 500);

  async function run(decision: 'APPROVE' | 'RETURN') {
    setError(null);
    try {
      await decide.mutateAsync({ approvalId: upload.approvalId ?? 0, decision, reason: reason.trim() });
      onDecided(t(`x07.w.decided.${decision}`, [upload.batchNo]));
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
        aria-labelledby="x07-decision"
        onSubmit={(e) => e.preventDefault()}
      >
        <h2 id="x07-decision">{t('x07.w.decisionTitle', [upload.batchNo])}</h2>
        <p className="sub2">
          {t('x07.counts', [upload.recordCount, upload.acceptedCount, upload.rejectedCount])}
        </p>
        <p className="fl-hint">{t('x07.w.decisionHint')}</p>
        <label className="fl-field">
          {t('common.reason')}
          <TextAreaField
            name="reason"
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            data-autofocus
          />
        </label>
        <Problem error={error} />
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="btn"
            disabled={!reasoned || decide.isPending}
            title={reasoned ? undefined : t('x07.w.why.returnReason')}
            onClick={() => void run('RETURN')}
          >
            {t('x07.w.return')}
          </button>
          <button
            type="button"
            className="btn pri"
            disabled={decide.isPending}
            onClick={() => void run('APPROVE')}
          >
            {t('x07.w.act.approve')}
          </button>
        </div>
      </form>
    </div>
  );
}

/** Opened from History: every upload of the session's offices, and the withdrawal of a live enablement. */
function History({
  editable,
  mayEnable,
  mayExport,
  enablements,
  selected,
  onOpen,
}: {
  editable: boolean;
  mayEnable: boolean;
  mayExport: boolean;
  enablements: Enablement[];
  selected: number | null;
  onOpen: (u: FallbackUpload) => void;
}) {
  return (
    <>
      <Uploads editable={editable} mayExport={mayExport} selected={selected} onOpen={onOpen} />
      {mayEnable && <Withdrawals enablements={enablements} />}
    </>
  );
}

/** Headquarters withdraws an enablement nothing was approved under, with a reason. */
function Withdrawals({ enablements }: { enablements: Enablement[] }) {
  const { t } = useI18n();
  const toast = useToast();
  const withdraw = useWithdrawEnablement();
  const [withdrawFor, setWithdrawFor] = useState<Enablement | null>(null);
  const live = enablements.filter((e) => LIVE.has(e.status));
  return (
    <div className="card fl-mt">
      <div className="hd">
        <h3>{t('x07.w.live')}</h3>
        <span className="sp" />
      </div>
      <Problem error={withdraw.error} />
      <div className="wrap">
        <table className="t">
          <thead>
            <tr>
              <Th label={t('x07.enablement')} s="SYS" />
              <Th label={t('x07.w.col.type')} s="ENT" />
              <Th label={t('x07.cycle')} s="ENT" />
              <th />
            </tr>
          </thead>
          <tbody>
            {live.map((e) => (
              <tr key={e.id} data-live={e.reference}>
                <td>{e.reference}</td>
                <td>{t(`x07.w.typeFull.${e.type}`)}</td>
                <td>{monthName(e.payrollYymm)}</td>
                <td>
                  <button type="button" className="btn sm" onClick={() => setWithdrawFor(e)}>
                    {t('x07.withdraw')}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {withdrawFor && (
        <ReasonDialog
          maxLength={1000}
          title={t('x07.withdrawTitle', [withdrawFor.reference])}
          onCancel={() => setWithdrawFor(null)}
          onConfirm={(reason) => {
            const target = withdrawFor;
            setWithdrawFor(null);
            withdraw
              .mutateAsync({ id: target.id, reason })
              .then(() => toast(t('x07.withdrawn', [target.reference])))
              .catch(() => undefined); // shown through withdraw.error
          }}
        />
      )}
    </div>
  );
}

/** Every upload of the session's offices: open one for its records, submit or cancel it. */
function Uploads({
  editable,
  mayExport,
  selected,
  onOpen,
}: {
  editable: boolean;
  mayExport: boolean;
  selected: number | null;
  onOpen: (u: FallbackUpload) => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const submit = useSubmitUpload();
  const cancel = useCancelUpload();
  const [cancelFor, setCancelFor] = useState<FallbackUpload | null>(null);
  const [rowError, setRowError] = useState<unknown>(null);

  async function act(run: () => Promise<unknown>, done: string) {
    setRowError(null);
    try {
      await run();
      toast(done);
    } catch (cause) {
      setRowError(cause);
    }
  }

  return (
    <div className="card fl-mt">
      <div className="hd">
        <h3>{t('x07.uploads')}</h3>
        {/* i18n-ignore: requirement references, not labels */}
        <span className="frs">SRS-G-46 · SRS-EH-11</span>
        <span className="sp" />
        {mayExport && <ExportButton className="btn sm" href={uploadsExportUrl()} what={t('screen.X-07')} />}
      </div>
      <Problem error={rowError} />
      <div className="wrap">
        <ServerPagination
          queryKey={generalKeys.uploads}
          fetchPage={generalPages.uploads}
          empty={<p className="fl-hint">{t('x07.noUploads')}</p>}
        >
          {(rows) => (
            <table className="t">
              <thead>
                <tr>
                  <Th label={t('x07.batch')} s="SYS" />
                  <Th label={t('x07.type')} s="ENT" />
                  <Th label={t('x07.records')} s="SYS" />
                  <Th label={t('x07.uploadedBy')} s="SYS" />
                  <Th label={t('gen.status')} s="SYS" />
                  {/* SRS l.882 (UI_BMAD_g1 #15): Reconciliation Status on the register. */}
                  <Th label={t('x07.reconStatus')} s="SYS" />
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((u) => {
                  const block = uploadSubmitBlock(u);
                  return (
                    <tr
                      key={u.id}
                      data-reference={u.batchNo}
                      className={selected === u.id ? 'sel' : undefined}
                    >
                      <td>
                        <b>{u.batchNo}</b>
                        <div className="sub2">
                          {u.office} · {monthName(u.payrollYymm)}
                        </div>
                      </td>
                      <td>{t(`x07.w.typeFull.${u.type}`)}</td>
                      <td>{t('x07.counts', [u.recordCount, u.acceptedCount, u.rejectedCount])}</td>
                      <td>
                        {u.uploadedBy}
                        <div className="sub2">{dateTime(u.uploadedAt)}</div>
                      </td>
                      <td>
                        <span className={`pill ${tone(u.status)}`}>{t(`x07.upload.${u.status}`)}</span>
                        {u.decisionReason && <div className="sub2">{u.decisionReason}</div>}
                      </td>
                      <td>{t(`x07.recon.${u.reconStatus}`)}</td>
                      <td className="fl-actions">
                        <button type="button" className="btn sm" onClick={() => onOpen(u)}>
                          {t('x07.lines')}
                        </button>
                        {editable && uploadEditable(u) && (
                          <button
                            type="button"
                            className="btn sm pri"
                            disabled={block !== null || submit.isPending}
                            title={block ? t(block) : undefined}
                            onClick={() =>
                              void act(() => submit.mutateAsync(u.id), t('x07.submitted', [u.batchNo]))
                            }
                          >
                            {t('common.submitForApproval')}
                          </button>
                        )}
                        {editable && uploadEditable(u) && (
                          <button type="button" className="btn sm" onClick={() => setCancelFor(u)}>
                            {t('gen.cancelRecord')}
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </div>
      {cancelFor && (
        <ReasonDialog
          maxLength={1000}
          title={t('x07.cancelTitle', [cancelFor.batchNo])}
          onCancel={() => setCancelFor(null)}
          onConfirm={(reason) => {
            const target = cancelFor;
            setCancelFor(null);
            void act(
              () => cancel.mutateAsync({ id: target.id, reason }),
              t('x07.cancelled', [target.batchNo]),
            );
          }}
        />
      )}
    </div>
  );
}

/** The validation result: every record of the upload, accepted or rejected with its reason. */
function ValidationLines({ upload }: { upload: FallbackUpload }) {
  const { t } = useI18n();
  return (
    <div className="card fl-mt" id="x07-lines" data-lines={upload.batchNo}>
      <div className="hd">
        <h3>{t('x07.w.linesTitle', [upload.batchNo])}</h3>
        <span className="sp" />
      </div>
      <div className="bd">
        <ServerPagination queryKey={generalKeys.lines(upload.id)} fetchPage={generalPages.lines(upload.id)}>
          {(lines) => (
            <table className="t">
              <thead>
                <tr>
                  <th>
                    #
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('gen.eis')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('x07.period')}
                    <Src s="INTF" />
                  </th>
                  <th>
                    {t('x07.result')}
                    <Src s="SYS" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {lines.map((l) => (
                  <tr key={l.lineNo}>
                    <td>{l.lineNo}</td>
                    <td>{l.employeeNumber ?? '—'}</td>
                    <td>
                      {l.periodFrom ?? l.eventEffectiveDate ?? '—'}
                      {l.periodTo ? ` – ${l.periodTo}` : ''}
                      {l.orderNo ? <div className="sub2">{l.orderNo}</div> : null}
                    </td>
                    <td>
                      <span className={`pill ${l.validationStatus === 'ACCEPTED' ? 'p-ok' : 'p-bad'}`}>
                        {t(`x07.line.${l.validationStatus}`)}
                      </span>
                      {l.rejectionReason && <div className="sub2">{l.rejectionReason}</div>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </div>
    </div>
  );
}

/**
 * The "📎 Order copy" card as drawn on the screen (fallback (b) only, SRS-G-30, SRS-G-31): the fields are open to the
 * pack's editing roles; only the Payroll Maker attaches, to a validated or returned suspension upload.
 */
function FallbackOrders({
  upload,
  mayUpload,
  mayEnable,
}: {
  upload: FallbackUpload | null;
  mayUpload: boolean;
  mayEnable: boolean;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const orders = upload?.type === 'SUSPENSION_ABSCONDING' ? upload : null;
  const canAttach = mayUpload && orders !== null && uploadEditable(orders);
  const stored = useStoredOrders('upload', orders?.id ?? null);
  const types = useDocumentTypes(true);
  const attach = useUploadOrder();
  return (
    <OrderCopyCard
      key={upload?.id ?? 0}
      hint={t('x07.w.orderHint')}
      canAttach={canAttach}
      readOnly={!mayUpload && !mayEnable}
      stored={stored.data}
      storedError={stored.error ?? types.error}
      fileHref={(fileId) => fileUrl('hrms-fallback/uploads', orders?.id ?? 0, fileId)}
      today={todayIso()}
      // X-07 field table: the order copy is mandatory for fallback (b).
      attachmentRequired={orders !== null}
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      officeRequired
      onAttach={async (o) => {
        if (!orders) return;
        for (const file of o.files) {
          await attach.mutateAsync({
            id: orders.id,
            order: {
              documentType: o.documentType,
              reference: o.reference,
              orderDate: o.orderDate,
              issuingAuthority: o.issuingOffice,
              file,
            },
          });
        }
        await stored.refetch();
        toast(t('ord.attached'));
      }}
    />
  );
}
