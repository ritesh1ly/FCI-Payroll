'use client';

import { type ReactNode, useCallback, useState } from 'react';
import { todayIso, useDocumentTypes } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessYymm } from '@/shared/time';
import { amount, Problem, ServerPagination, useToast } from '@/shared/ui';
import { InvoiceCard, type InvoiceRow } from '@/shared/ui/invoiceCard';
import { RegisterSection, useApproveRow } from '@/shared/ui/masterParts';
import { OrderCopyCard, type OrderCopyInput } from '@/shared/ui/orderCopy';
import { Act, Explained, Locked, RowActs, Tbl, Tiles, ViewDialog, WfTile } from '@/shared/ui/wireframe';
import type { LoanDisbursement, LoanRow } from '../api/loansApi';
import { changeable, changeTarget, crore, ledgerPill, ledgerStatus } from '../business/loanRules';
import {
  disbursementPage,
  ledgerPage,
  loanKeys,
  loanOrderUrl,
  useAttachLoanOrder,
  useLoanOrders,
  useLoanSummary,
} from '../orchestration/loanQueries';
import { LoansAdvances } from './LoansAdvances';

/** What the closed section opens on: a loan, a CPF request, or the new-sanction form. */
interface Opening {
  run: number;
  loan: number | null;
  sanction: number | null;
  startNew: boolean;
}

function invoiceRow(
  d: LoanDisbursement,
  t: (key: string, args?: readonly unknown[]) => string,
  maker: boolean,
): InvoiceRow {
  // BR-AUDIT-009: a cancellation is a new version; advance_settlement holds no version or cancel
  // state, so the action stays off with its reason until the table carries one (PENDING I04-INV).
  const sent = d.invoiceStatus !== 'NOT_RAISED' && d.invoiceStatus !== 'RAISED';
  return {
    key: String(d.id),
    entry: d.entry,
    member: `${d.eis} ${d.employee}`,
    paymentType: t(d.source === 'HRMS' ? 'i04w.inv.fromHrms' : 'i04w.inv.fromLabour', [d.paymentType]),
    amount: amount(d.amount),
    invoiceNo: d.invoiceNumber,
    invoiceStatus: d.invoiceStatus,
    sentToAppsDate: d.sentToApps,
    appsCancelled: d.appsCancelled,
    cancelled: false,
    cancelWhy: t(sent ? 'i04w.inv.whySent' : maker ? 'i04w.inv.whyPending' : 'i04w.inv.whyMaker'),
    onCancel: () => undefined,
  };
}

/**
 * I-04 Loans, Advances & CPF Sanctions as Wireframes v6.1 draws it, the same body under CPF / PF & VPF
 * and under Loans & Advances (a frozen requirement per OD-121; SRS-12-1, SRS-12-3, SRS-12-17 ... 12-22,
 * BR-LOAN-011, BR-AUDIT-009, BR-AUDIT-010, SRS-G-30): the loan and CPF tiles, the Loan & Advance Ledger
 * (an employee loan from HRMS is read-only and changes through I-21; the Maker forecloses or reschedules a
 * loan set up here, the Checker approves a foreclosure with the LOAN_FORECLOSURE OTP in W-01), the CPF
 * card, the loan disbursements with their invoice, and the order copies of the chosen loan. Every older
 * function stays in the closed section, whole: the registers, the filters, the requests, the report, the
 * sanction form and each record's tier or change actions, opened from the drawn buttons.
 */
export function LoansScreen({
  cpfTile,
  cpf,
  cpfRegister,
}: {
  cpfTile: ReactNode;
  /** The CPF cards; onOpen opens a request in the closed section. */
  cpf: (onOpen: (id: number) => void) => ReactNode;
  cpfRegister: (opening: number | null) => ReactNode;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const may = (action: string) => can(me.data, 'I-04', action);
  const maker = may('LOAN_SANCTION');
  const checker = me.data?.roles?.includes('PAYROLL_CHECKER') ?? false;
  const approve = useApproveRow();
  const period = me.data?.period?.replace('-', '') || businessYymm();
  const summary = useLoanSummary(period);
  const s = summary.data;
  const [rows, setRows] = useState<LoanRow[]>([]);
  const [chosen, setChosen] = useState<LoanRow | null>(null);
  const [viewing, setViewing] = useState<LoanRow | null>(null);
  const [open, setOpen] = useState(false);
  const [opening, setOpening] = useState<Opening>({ run: 0, loan: null, sanction: null, startNew: false });

  const shown = useCallback((page: LoanRow[]) => {
    setRows(page);
    setChosen((prev) => (prev ? (page.find((l) => l.id === prev.id) ?? prev) : (page[0] ?? null)));
  }, []);

  function register(at: Omit<Opening, 'run'>) {
    setOpening((prev) => ({ ...at, run: prev.run + 1 }));
    setOpen(true);
  }

  /** Foreclose and Reschedule open the chosen loan set up here; an employee loan changes on I-21. */
  function change() {
    const target = changeTarget(chosen, rows);
    if (target) register({ loan: target.id, sanction: null, startNew: false });
    else toast(t('i04w.noLabourLoan'));
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {maker ? (
          <Explained label={t('i04w.import')} why={t('i04w.importWhy')} />
        ) : (
          <Locked label={t('i04w.import')} why={t('i04w.makerOnly')} />
        )}
        {maker ? (
          <Act
            label={t('i04w.newSanction')}
            kind="pri"
            onClick={() => register({ loan: null, sanction: null, startNew: true })}
          />
        ) : (
          <Locked label={t('i04w.newSanction')} why={t('i04w.newSanctionWhy')} />
        )}
        <Locked label={t('i04w.cpfChain')} why={t('i04w.cpfChainWhy')} />
      </div>
      <div className="keep">
        <div className="anno on">
          <b>{t('i04w.bannerLead')}</b> {t('i04w.bannerText')}
        </div>
      </div>
      <Tiles>
        <WfTile
          icon="💳"
          label={t('i04w.tile.active')}
          src="SYS"
          big={amount(s?.activeLoans ?? 0)}
          sub={t('i04w.tile.activeSub', [crore(s?.outstanding ?? 0)])}
        />
        <WfTile
          icon="➖"
          label={t('i04w.tile.recovery')}
          src="CALC"
          big={t('i04w.crore', [crore((s?.recoveryPrincipal ?? 0) + (s?.recoveryInterest ?? 0))])}
          sub={t('i04w.tile.recoverySub', [
            crore(s?.recoveryPrincipal ?? 0),
            crore(s?.recoveryInterest ?? 0),
          ])}
        />
        {cpfTile}
        <WfTile
          icon="⛔"
          label={t('i04w.tile.failures')}
          src="INTF"
          tone="bad"
          bad={(s?.importFailures ?? 0) > 0}
          big={amount(s?.importFailures ?? 0)}
          sub={s?.failingInterface ?? t('i04w.tile.failuresNone')}
        />
      </Tiles>
      <Problem error={summary.error} />
      <Tbl
        title={t('i04w.ledger')}
        acts={
          <>
            {may('LOAN_FORECLOSE') ? (
              <Act label={t('i04w.foreclose')} kind="sm" onClick={change} />
            ) : (
              <Locked label={t('i04w.foreclose')} kind="sm" why={t('i04w.forecloseWhy')} />
            )}
            {may('LOAN_RESCHEDULE') ? (
              <Act label={t('i04w.reschedule')} kind="sm" onClick={change} />
            ) : (
              <Locked label={t('i04w.reschedule')} kind="sm" why={t('i04w.rescheduleWhy')} />
            )}
          </>
        }
        cols={[
          { t: t('i04w.col.loanNo'), s: 'SYS' },
          { t: t('i04w.col.member'), s: 'HRMS' },
          { t: t('i04w.col.type'), s: 'ENT' },
          { t: t('i04w.col.sanctioned'), s: 'ENT', num: true },
          { t: t('i04w.col.instalments'), s: 'ENT', num: true },
          { t: t('i04w.col.perPeriod'), s: 'CALC', num: true },
          { t: t('i04w.col.paidTotal'), s: 'CALC' },
          { t: t('i04w.col.outstanding'), s: 'CALC', num: true },
          { t: t('i04w.col.status'), s: 'SYS' },
          { t: t('i04w.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={[...loanKeys.list({}), 'ledger']}
          fetchPage={ledgerPage}
          empty={t('i04l.none')}
          onRows={shown}
        >
          {(page) =>
            page.map((l) => {
              const status = ledgerStatus(l);
              const editable = changeable(l) && (may('LOAN_FORECLOSE') || may('LOAN_RESCHEDULE'));
              return (
                <tr key={l.id} data-loan={l.loanNumber}>
                  <td>
                    <b>{l.loanNumber}</b>
                  </td>
                  <td>
                    {l.eis} {l.employee}
                  </td>
                  <td>{t(`i04l.type.${l.loanType}`)}</td>
                  <td className="num">{amount(l.sanctioned)}</td>
                  <td className="num">{l.principalInstalments}</td>
                  <td className="num">{status === 'SCHEDULE_OFF' ? '—' : amount(l.instalment)}</td>
                  <td>
                    {l.paidInstalments} / {l.principalInstalments}
                  </td>
                  <td className="num">{amount(l.principalOutstanding + l.interestOutstanding)}</td>
                  <td>
                    <span className={`pill ${ledgerPill(status)}`}>{t(`i04w.status.${status}`)}</span>
                  </td>
                  <td>
                    <RowActs
                      viewName={t('i04w.viewOf', [l.loanNumber])}
                      editName={t('i04w.editOf', [l.loanNumber])}
                      onView={() => {
                        setChosen(l);
                        setViewing(l);
                      }}
                      onEdit={
                        editable
                          ? () => {
                              setChosen(l);
                              register({ loan: l.id, sanction: null, startNew: false });
                            }
                          : undefined
                      }
                      editWhy={t(l.source === 'HRMS' ? 'i04w.editHrms' : 'i04w.editMaker')}
                      // Drawn on a loan set up here; the Checker decides it in W-01 (LOAN_FORECLOSURE OTP).
                      onApprove={checker && changeable(l) ? () => approve(true) : undefined}
                    />
                  </td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
      {viewing && (
        <ViewDialog
          screen={t('screen.I-04')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('i04w.col.loanNo'), v: viewing.loanNumber, s: 'SYS' },
            { k: t('i04w.col.member'), v: `${viewing.eis} ${viewing.employee}`, s: 'HRMS' },
            { k: t('i04w.col.type'), v: t(`i04l.type.${viewing.loanType}`), s: 'ENT' },
            { k: t('i04w.col.sanctioned'), v: amount(viewing.sanctioned), s: 'ENT' },
            { k: t('i04w.col.instalments'), v: viewing.principalInstalments, s: 'ENT' },
            { k: t('i04w.col.perPeriod'), v: amount(viewing.instalment), s: 'CALC' },
            { k: t('i04l.principalOutstanding'), v: amount(viewing.principalOutstanding), s: 'CALC' },
            { k: t('i04l.interestOutstanding'), v: amount(viewing.interestOutstanding), s: 'CALC' },
            { k: t('i04w.col.status'), v: t(`i04w.status.${ledgerStatus(viewing)}`), s: 'SYS' },
          ]}
          actions={
            <Act
              label={t('i04w.openRecord')}
              onClick={() => {
                register({ loan: viewing.id, sanction: null, startNew: false });
                setViewing(null);
              }}
            />
          }
        />
      )}
      {cpf((id) => register({ loan: null, sanction: id, startNew: false }))}
      <ServerPagination
        queryKey={loanKeys.disbursements()}
        fetchPage={disbursementPage}
        empty={<InvoiceCard title={t('i04w.disbursements')} rows={[]} />}
      >
        {(page) => (
          <InvoiceCard title={t('i04w.disbursements')} rows={page.map((d) => invoiceRow(d, t, maker))} />
        )}
      </ServerPagination>
      <LoanOrderCard loan={chosen} maker={maker} />
      <RegisterSection label={t('i04w.register')} id="i04-register" open={open} onToggle={setOpen}>
        {open && (
          <div key={opening.run}>
            {cpfRegister(opening.sanction)}
            <div className="wireframe-body-container">
              <LoansAdvances opening={opening.loan} startNew={opening.startNew} />
            </div>
          </div>
        )}
      </RegisterSection>
    </div>
  );
}

/**
 * The "Order copy - attach & store" card of the loan chosen on the ledger (SRS-G-30, A-41). An employee
 * loan's documents stay with HRMS and its I-21 task, so the Maker stores an order only with a loan set up here.
 */
function LoanOrderCard({ loan, maker }: { loan: LoanRow | null; maker: boolean }) {
  const { t } = useI18n();
  const stored = useLoanOrders(loan?.id ?? null);
  const types = useDocumentTypes();
  const attach = useAttachLoanOrder();
  const id = loan?.id ?? 0;
  return (
    <OrderCopyCard
      key={id}
      hint={t('i04w.orderHint')}
      canAttach={maker && loan !== null && loan.source !== 'HRMS'}
      readOnly={!maker}
      stored={stored.data}
      storedError={stored.error ?? types.error}
      fileHref={(fileId) => loanOrderUrl(id, fileId)}
      today={todayIso()}
      attachmentRequired={false}
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      onAttach={async (order: OrderCopyInput) => {
        for (const file of order.files) {
          await attach.mutateAsync({
            id,
            order: {
              documentType: order.documentType,
              reference: order.reference,
              orderDate: order.orderDate,
              issuingAuthority: order.issuingOffice,
              file,
            },
          });
        }
      }}
    />
  );
}
