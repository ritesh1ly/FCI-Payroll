'use client';

import { useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import {
  amount,
  Problem,
  ReasonDialog,
  ServerPagination,
  useDialogFocus,
  useToast,
  withBase,
} from '@/shared/ui';
import { InvoiceCard } from '@/shared/ui/invoiceCard';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { Act, Locked, Panel, RowActs, Tbl } from '@/shared/ui/wireframe';
import type { OffCycleCase } from '../api/generalApi';
import {
  isAsciiText,
  isEis,
  monthName,
  monthsBack,
  offCycleCancellable,
  offCycleEditable,
  offCycleSubmitBlock,
  tone,
  yymmOf,
} from '../business/generalRules';
import {
  fileUrl,
  generalKeys,
  generalPages,
  useCancelOffCycle,
  useCaptureOffCycle,
  useDocumentTypes,
  useOffCycleOrder,
  useStoredOrders,
  useSubmitOffCycle,
} from '../orchestration/generalQueries';
import { todayIso } from './OrderFields';
import { Banner } from './Parts';

/**
 * I-20 Off-Cycle / Exceptional Salary Processing as Wireframes v6.1 draws it (a frozen requirement per OD-121;
 * SRS-G-10, FRS §6.9.6): "＋ Raise Off-Cycle Case" for the Payroll Maker; the Off-Cycle Cases register with its
 * Filter (case type from the OFF_CYCLE_CASE_TYPE list, OD-123) and the row actions (👁 View opens the case with
 * its submission, ✎ Edit the Maker's case in Draft or Returned, ✔ the Checker's decision in W-01); the mandatory
 * fields and the duplicate-payment guard; the invoice status of every payment with ⊘ Cancel (BR-AUDIT-009 / 010);
 * the order card of the selected case (the approval note or order, SRS-G-16, SRS-G-30). The amount is computed by
 * the regular rules (never typed) and the duplicate-payment check runs on capture, on submission and again at
 * approval. A month already locked is paid through I-23.
 */
export function OffCycle() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const mayCreate = can(me.data, 'I-20', 'CREATE');
  const maySubmit = can(me.data, 'I-20', 'SUBMIT');
  const checker = can(me.data, 'I-20', 'APPROVE');
  // The cases of the shown page: the invoice table reads them (X-pagination).
  const [rows, setRows] = useState<OffCycleCase[]>([]);
  const [caseType, setCaseType] = useState('');
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [raising, setRaising] = useState(false);
  const [viewing, setViewing] = useState<OffCycleCase | null>(null);
  const [selected, setSelected] = useState<OffCycleCase | null>(null);
  const [cancelFor, setCancelFor] = useState<OffCycleCase | null>(null);
  const [rowError, setRowError] = useState<unknown>(null);
  const submit = useSubmitOffCycle();
  const cancel = useCancelOffCycle();
  const shown = useCallback((page: OffCycleCase[]) => {
    setRows(page);
    setSelected((prev) => (prev ? (page.find((c) => c.id === prev.id) ?? prev) : (page[0] ?? null)));
  }, []);

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
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {mayCreate ? (
          <Act label={t('i20.raise')} kind="pri" onClick={() => setRaising(true)} />
        ) : (
          <Locked label={t('i20.raise')} why={t('reason.makerOnly')} />
        )}
      </div>
      <Banner>
        <b>{t('i20.bannerLead')}</b> {t('i20.bannerText')}
      </Banner>
      <Problem error={rowError} />
      <Tbl
        title={t('i20.cases')}
        acts={<Act label={t('i20.filterAct')} kind="sm" onClick={() => setFiltersOpen(!filtersOpen)} />}
        cols={[
          { t: t('i20.col.case'), s: 'SYS' },
          { t: t('i20.col.member'), s: 'HRMS' },
          { t: t('i20.col.caseType'), s: 'ENT' },
          { t: t('i20.col.salaryMonth'), s: 'ENT' },
          { t: t('i20.col.processingMonth'), s: 'SYS' },
          { t: t('i20.col.amount'), s: 'CALC', num: true },
          { t: t('i20.col.approvalReference'), s: 'ENT' },
          { t: t('i20.col.duplicateCheck'), s: 'SYS' },
          { t: t('i20.col.status'), s: 'SYS' },
          { t: t('i20.col.actions'), s: 'SYS' },
        ]}
        after={
          filtersOpen ? (
            <div className="toolbar fl-filters">
              <label>
                {t('i20.caseType')}{' '}
                <select value={caseType} onChange={(e) => setCaseType(e.target.value)}>
                  <option value="">{t('common.all')}</option>
                  <ReferenceOptions list="OFF_CYCLE_CASE_TYPE" />
                </select>
              </label>
            </div>
          ) : undefined
        }
      >
        <ServerPagination
          queryKey={[...generalKeys.offCycle, caseType]}
          fetchPage={generalPages.offCycleOf(caseType || undefined)}
          empty={<p className="fl-hint">{t('i20.none')}</p>}
          onRows={shown}
        >
          {(page) =>
            page.map((c) => {
              const editable = mayCreate && offCycleEditable(c);
              return (
                <tr
                  key={c.id}
                  data-reference={c.reference}
                  aria-selected={selected?.id === c.id}
                  className={selected?.id === c.id ? 'fl-row-selected' : undefined}
                  onClick={() => setSelected(c)}
                >
                  <td>
                    <b>{c.reference}</b>
                  </td>
                  <td>
                    {c.eis ?? '—'} {c.memberName}
                  </td>
                  <td>{t(`i20.type.${c.caseType}`)}</td>
                  <td>{monthName(c.salaryYymm)}</td>
                  <td>{monthName(c.processingYymm)}</td>
                  <td className="num">{amount(c.amount)}</td>
                  <td>{c.approvalReference || '—'}</td>
                  <td>
                    {/* SRS l.761 (UI_BMAD_g1 #18): Pass, Fail with its reason, or not run yet. */}
                    {c.duplicateCheck == null ? (
                      <span className="pill">{t('i20.dupPending')}</span>
                    ) : (
                      <span className={`pill ${c.duplicateCheck === 'PASS' ? 'p-ok' : 'p-bad'}`}>
                        {c.duplicateCheck === 'PASS'
                          ? t('i20.dupPassed')
                          : t('i20.dupFailed', [c.duplicateCheck.replace(/^FAIL: /, '')])}
                      </span>
                    )}
                  </td>
                  <td>
                    <span className={`pill ${tone(c.status)}`}>{t(`i20.status.${c.status}`)}</span>
                    {c.excludedFromYymm && (
                      <div className="sub2">{t('i20.excluded', [monthName(c.excludedFromYymm)])}</div>
                    )}
                    {c.cancelReason && <div className="sub2">{c.cancelReason}</div>}
                  </td>
                  <td onClick={(e) => e.stopPropagation()}>
                    <RowActs
                      viewName={`${t('common.view')} ${c.reference}`}
                      editName={`${t('common.edit')} ${c.reference}`}
                      onView={() => setViewing(c)}
                      onEdit={editable ? () => setViewing(c) : undefined}
                      editWhy={
                        !mayCreate
                          ? t('i20.why.makerEdit')
                          : c.status === 'BLOCKED'
                            ? t('i20.why.duplicate')
                            : t('i20.why.notEditable')
                      }
                      onApprove={
                        checker && c.status === 'PENDING_CHECKER'
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
      <div className="g2 fl-mt">
        <Panel
          title={t('i20.mandatory')}
          items={[
            { icon: '🏷', tone: 'info', title: t('i20.caseType'), src: 'ENT', text: t('i20.caseTypeSub') },
            { icon: '🗓', tone: 'gray', title: t('i20.monthPair'), src: 'SYS', text: t('i20.monthPairSub') },
            {
              icon: '🔖',
              tone: 'bad',
              title: t('i20.approvalReference'),
              src: 'ENT',
              text: t('i20.approvalReferenceSub'),
            },
            {
              icon: '📎',
              tone: 'bad',
              title: t('i20.supportingDoc'),
              src: 'ENT',
              text: t('i20.supportingDocSub'),
            },
          ]}
        />
        <Panel
          title={t('i20.guard')}
          items={[
            {
              icon: '🔍',
              tone: 'bad',
              title: t('i20.guardValidated'),
              src: 'SYS',
              text: t('i20.guardValidatedSub'),
              right: <span className="pill p-ok">{t('i20.guardHard')}</span>,
            },
            {
              icon: '🚫',
              tone: 'warn',
              title: t('i20.guardExclusion'),
              src: 'SYS',
              text: t('i20.guardExclusionSub'),
              right: <span className="pill p-ok">{t('i20.guardOn')}</span>,
            },
          ]}
        />
      </div>
      <div className="fl-mt">
        <InvoiceCard
          title={t('i20.invoices')}
          rows={rows.map((c) => ({
            key: String(c.id),
            entry: c.reference,
            member: `${c.eis ?? '—'} ${c.memberName ?? ''}`.trim(),
            paymentType: t(`i20.type.${c.caseType}`),
            amount: amount(c.amount),
            invoiceNo: c.invoiceNo,
            invoiceStatus: c.invoiceStatus,
            sentToAppsDate: c.invoiceSentToAppsDate,
            appsCancelled: c.appsCancelConfirmedAt !== null,
            cancelled: c.status === 'CANCELLED',
            cancelWhy: !maySubmit
              ? t('wf.inv.why.maker')
              : offCycleCancellable(c)
                ? null
                : t('inv.cancelBlocked'),
            onCancel: () => setCancelFor(c),
          }))}
        />
      </div>
      <CaseOrders kase={selected} mayAttach={mayCreate} />
      {raising && <RaiseDialog onClose={() => setRaising(false)} onRaised={(c) => setSelected(c)} />}
      {viewing && (
        <CaseView
          kase={rows.find((c) => c.id === viewing.id) ?? viewing}
          maySubmit={maySubmit}
          busy={submit.isPending}
          onClose={() => setViewing(null)}
          onSubmit={(c) => {
            setViewing(null);
            void act(() => submit.mutateAsync(c.id), t('i20.submitted', [c.reference]));
          }}
        />
      )}
      {cancelFor && (
        <ReasonDialog
          title={t('i20.cancelTitle', [cancelFor.reference])}
          onCancel={() => setCancelFor(null)}
          onConfirm={(reason) => {
            const target = cancelFor;
            setCancelFor(null);
            void act(
              () => cancel.mutateAsync({ id: target.id, reason }),
              t('i20.cancelled', [target.reference]),
            );
          }}
        />
      )}
    </div>
  );
}

/** "＋ Raise Off-Cycle Case" (SRS-G-10): the member, the case type (database list), the salary month, the approval. */
function RaiseDialog({ onClose, onRaised }: { onClose: () => void; onRaised: (c: OffCycleCase) => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const dialog = useDialogFocus<HTMLFormElement>();
  const capture = useCaptureOffCycle();
  const months = monthsBack(yymmOf(new Date()), 24);
  const [form, setForm] = useState({ eis: '', caseType: '', salaryYymm: '', approvalReference: '' });
  const [error, setError] = useState<unknown>(null);
  const valid =
    isEis(form.eis) &&
    form.caseType !== '' &&
    form.salaryYymm !== '' &&
    isAsciiText(form.approvalReference, 100);

  async function record() {
    setError(null);
    try {
      const created = await capture.mutateAsync(form);
      toast(t('i20.captured', [created.reference, created.duplicateCheck ?? '']));
      onRaised(created);
      onClose();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="fl-modal-backdrop" role="presentation">
      <form
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('i20.raiseTitle')}
        onSubmit={(e) => {
          e.preventDefault();
          if (valid) void record();
        }}
      >
        <h2>{t('i20.raiseTitle')}</h2>
        <Problem error={error} />
        <div className="rf-grid">
          <div className="rf-field">
            <label htmlFor="i20-eis">{t('gen.eis')}</label>
            <input
              id="i20-eis"
              maxLength={20}
              value={form.eis}
              onChange={(e) => setForm({ ...form, eis: e.target.value })}
            />
          </div>
          <div className="rf-field">
            <label htmlFor="i20-type">{t('i20.caseType')}</label>
            <select
              id="i20-type"
              value={form.caseType}
              onChange={(e) => setForm({ ...form, caseType: e.target.value })}
            >
              <option value="">—</option>
              <ReferenceOptions list="OFF_CYCLE_CASE_TYPE" />
            </select>
          </div>
          <div className="rf-field">
            <label htmlFor="i20-month">{t('i20.salaryMonth')}</label>
            <select
              id="i20-month"
              value={form.salaryYymm}
              onChange={(e) => setForm({ ...form, salaryYymm: e.target.value })}
            >
              <option value="">—</option>
              {months.map((m) => (
                <option key={m} value={m}>
                  {monthName(m)}
                </option>
              ))}
            </select>
            <span className="hint">{t('i20.salaryMonthHint')}</span>
          </div>
          <div className="rf-field">
            <label htmlFor="i20-ref">{t('i20.approvalReference')}</label>
            <input
              id="i20-ref"
              maxLength={100}
              value={form.approvalReference}
              onChange={(e) => setForm({ ...form, approvalReference: e.target.value })}
            />
          </div>
        </div>
        <p className="fl-hint">{t('i20.amountNote')}</p>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="btn pri" disabled={!valid || capture.isPending}>
            {t('i20.capture')}
          </button>
        </div>
      </form>
    </div>
  );
}

/** 👁 View / ✎ Edit: the case read-only, and its submission for the Maker while it is a Draft or Returned. */
function CaseView({
  kase,
  maySubmit,
  busy,
  onClose,
  onSubmit,
}: {
  kase: OffCycleCase;
  maySubmit: boolean;
  busy: boolean;
  onClose: () => void;
  onSubmit: (c: OffCycleCase) => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLDivElement>();
  const block = offCycleSubmitBlock(kase);
  const why = !maySubmit ? t('reason.makerOnly') : block ? t(block) : null;
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('i20.viewTitle', [kase.reference])}
      >
        <h2>{t('i20.viewTitle', [kase.reference])}</h2>
        <div className="kv">
          <span className="k">{t('i20.col.member')}</span>
          <span>{`${kase.eis ?? '—'} ${kase.memberName ?? ''}`}</span>
          <span className="k">{t('i20.col.caseType')}</span>
          <span>{t(`i20.type.${kase.caseType}`)}</span>
          <span className="k">{t('i20.col.salaryMonth')}</span>
          <span>{monthName(kase.salaryYymm)}</span>
          <span className="k">{t('i20.col.processingMonth')}</span>
          <span>{monthName(kase.processingYymm)}</span>
          <span className="k">{t('i20.col.amount')}</span>
          <span>{amount(kase.amount)}</span>
          <span className="k">{t('i20.col.approvalReference')}</span>
          <span>{kase.approvalReference || '—'}</span>
          <span className="k">{t('i20.col.status')}</span>
          <span>{t(`i20.status.${kase.status}`)}</span>
          <span className="k">{t('ord.orders')}</span>
          <span>{kase.orders}</span>
        </div>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
          <button
            type="button"
            className="btn pri"
            disabled={why !== null || busy}
            title={why ?? undefined}
            data-why={why ?? undefined}
            onClick={() => onSubmit(kase)}
          >
            {t('common.submitForApproval')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** The drawn order card for the selected case: the approval note or order (SRS-G-16, SRS-G-30). */
function CaseOrders({ kase, mayAttach }: { kase: OffCycleCase | null; mayAttach: boolean }) {
  const { t } = useI18n();
  const stored = useStoredOrders('off-cycle', kase?.id ?? null);
  const types = useDocumentTypes(true);
  const attach = useOffCycleOrder();
  return (
    <OrderCopyCard
      key={kase?.id ?? 0}
      hint={t('i20.ord.hint')}
      canAttach={mayAttach && kase !== null && offCycleEditable(kase)}
      readOnly={!mayAttach}
      stored={stored.data}
      storedError={stored.error ?? types.error}
      fileHref={(fileId) => fileUrl('off-cycle', kase?.id ?? 0, fileId)}
      today={todayIso()}
      // SRS-G-16: an off-cycle case is submitted only with its approval note or order.
      attachmentRequired
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      officeRequired
      onAttach={async (o) => {
        if (!kase) return;
        for (const file of o.files) {
          await attach.mutateAsync({
            id: kase.id,
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
      }}
    />
  );
}
