'use client';

import { useI18n } from '@/shared/i18n';
import { businessToday } from '@/shared/time';
import { amount, date, ServerPagination } from '@/shared/ui';
import { InvoiceCard } from '@/shared/ui/invoiceCard';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { Act, Explained, KvCard, Panel, type PanelItem, Tbl } from '@/shared/ui/wireframe';
import type { CaseDetail, InvoiceRow, Recovery } from '../api/settlementApi';
import { outstanding, recoveryReference, statementRows } from '../business/settlementRules';
import {
  orderFileUrl,
  settlementKeys,
  settlementPages,
  useCaseOrders,
  useDocumentTypes,
  useOrderAttach,
} from '../orchestration/settlementQueries';
import type { FormState } from './SettlementDialogs';

/** O-05 l.2932: the dues a recovery is adjusted against (never the CPF final payment, SRS-10-21). */
const RECOVERY_SOURCES = ['PAYROLL_DUES', 'GRATUITY'] as const;

/** A heading of the open case: the drawn title, then the case and the member. */
function caseTitle(t: (key: string, args?: unknown[]) => string, key: string, d: CaseDetail): string {
  return t(key, [d.summary.caseReference, d.summary.name]);
}

/** SRS-10-4, SRS l.2934-2935: one row per recovery, each with the dues it is adjusted against. */
export function RecoveriesCard({
  d,
  editable,
  form,
  onForm,
}: {
  d: CaseDetail;
  editable: boolean;
  form: FormState;
  onForm: (next: FormState) => void;
}) {
  const { t } = useI18n();
  const total = d.recoveries.reduce((sum, r) => sum + r.amount, 0);
  const fromGratuity = d.recoveries
    .filter((r) => r.source === 'GRATUITY')
    .reduce((sum, r) => sum + r.amount, 0);
  return (
    <Tbl
      title={caseTitle(t, 'o05.recoveriesTitle', d)}
      label={t('o05.recoveries')}
      acts={<Act kind="sm" label={t('o05.print')} onClick={() => window.print()} />}
      cols={[
        { t: t('o05.col.recovery'), s: 'CALC' },
        { t: t('o05.reference'), s: 'SYS' },
        { t: t('o05.period'), s: 'SYS' },
        { t: t('o05.col.amount'), s: 'CALC', num: true },
        { t: t('o05.col.adjustedAgainst'), s: 'ENT' },
        { t: t('o05.col.approval'), s: 'SYS' },
      ]}
      foot={
        d.recoveries.length > 0 && (
          <tr>
            <td colSpan={3}>
              <b>{t('o05.totalRecoveries')}</b>
            </td>
            <td className="num">
              <b>{amount(total)}</b>
            </td>
            <td>{t('o05.recoverySplit', [amount(total - fromGratuity), amount(fromGratuity)])}</td>
            <td />
          </tr>
        )
      }
    >
      {d.recoveries.length === 0 && (
        <tr>
          <td colSpan={6} className="sub2">
            {t('o05.noRecoveries')}
          </td>
        </tr>
      )}
      {d.recoveries.map((r) => (
        <tr key={r.id}>
          <td>{t(`o05.recovery.${r.type}`)}</td>
          <td>{recoveryReference(r.reference) ?? '—'}</td>
          <td>{r.period ?? '—'}</td>
          <td className="num">{amount(r.amount)}</td>
          <td>
            {editable ? <SourceSelect r={r} form={form} onForm={onForm} /> : t(`o05.source.${r.source}`)}
          </td>
          <td>
            <Approval r={r} />
          </td>
        </tr>
      ))}
    </Tbl>
  );
}

function SourceSelect({
  r,
  form,
  onForm,
}: {
  r: Recovery;
  form: FormState;
  onForm: (next: FormState) => void;
}) {
  const { t } = useI18n();
  return (
    <select
      aria-label={t('o05.sourceOf', [r.reference ?? t(`o05.recovery.${r.type}`)])}
      value={form?.kind === 'SOURCE' && form.recoveryId === r.id ? form.source : r.source}
      onChange={(e) => {
        const source = e.target.value === 'GRATUITY' ? 'GRATUITY' : 'PAYROLL_DUES';
        onForm(
          source === r.source
            ? null
            : {
                kind: 'SOURCE',
                recoveryId: r.id,
                source,
                reason: '',
                orderRef: '',
                orderDate: '',
                authority: '',
                file: null,
              },
        );
      }}
    >
      {RECOVERY_SOURCES.map((x) => (
        <option key={x} value={x}>
          {t(`o05.source.${x}`)}
        </option>
      ))}
    </select>
  );
}

/** SRS l.2940: the HQ approval of a recovery from gratuity; a recovery from the dues is the Checker's. */
function Approval({ r }: { r: Recovery }) {
  const { t } = useI18n();
  if (r.source !== 'GRATUITY') return <>{t('o05.approval.checker')}</>;
  if (!r.hqApprovalStatus) return <>—</>;
  const approved = r.hqApprovalStatus === 'APPROVED';
  return (
    <span className={`pill ${approved ? 'p-ok' : 'p-pur'}`}>
      {t('o05.approval.hq', [t(`o05.hq.${r.hqApprovalStatus}`)])}
    </span>
  );
}

const CLEARANCE_ICON: Record<string, { icon: string; src: 'CALC' | 'ENT' }> = {
  LOANS_ADVANCES: { icon: '💳', src: 'CALC' },
  CPF_FINAL_STATUS: { icon: '🏦', src: 'CALC' },
  PENALTY_RECOVERY: { icon: '⚖️', src: 'CALC' },
  ALLOWANCES: { icon: '🧳', src: 'CALC' },
  ESTABLISHMENT_NO_DUES: { icon: '🏠', src: 'ENT' },
};

/** The pack's checklist order (UC-06 steps 2-3). */
const CLEARANCE_ORDER = Object.keys(CLEARANCE_ICON);

/** SRS-10-27, SRS l.2949: each clearance with its answer; drawn Pending while outstanding. */
export function ClearanceChecklist({ d }: { d: CaseDetail }) {
  const { t } = useI18n();
  const known = CLEARANCE_ORDER.flatMap((type) => d.clearances.filter((c) => c.type === type));
  const others = d.clearances.filter((c) => !CLEARANCE_ORDER.includes(c.type));
  const items: PanelItem[] = [...known, ...others].map((c) => {
    const open = outstanding(c.status);
    return {
      icon: CLEARANCE_ICON[c.type]?.icon ?? '📋',
      tone: open ? 'warn' : 'ok',
      title: t(`o05.clearance.${c.type}`),
      src: CLEARANCE_ICON[c.type]?.src ?? 'SYS',
      text: c.remark ?? undefined,
      right: (
        <span className={`pill ${open ? 'p-warn' : 'p-ok'}`}>{t(`o05.clearanceStatus.${c.status}`)}</span>
      ),
    };
  });
  // SRS-10-2: the freeze rule is drawn; which action freezes what waits for CLR-150 (WF-046).
  items.push({
    icon: '🧊',
    tone: 'info',
    title: t('o05.componentFreeze'),
    src: 'SYS',
    right: <span className="pill p-ok">{t('o05.on')}</span>,
  });
  return (
    <div data-testid="o05-clearances">
      <Panel
        title={t('o05.checklistTitle')}
        act={<Explained kind="sm" label={t('o05.chase')} why={t('o05.why.chase')} />}
        items={items}
      />
      {d.summary.status === 'COMPUTED' && d.clearances.some((c) => outstanding(c.status)) && (
        <p className="fl-hint">{t('o05.approvalHeld')}</p>
      )}
    </div>
  );
}

/** The statement table (SRS-10-12, SRS-10-24): the card on the page and the dialog share it. */
export function StatementTable({ d }: { d: CaseDetail }) {
  const { t } = useI18n();
  const rows = statementRows(d);
  return (
    <>
      {rows.map((r) => {
        const value = r.amount === null ? '—' : amount(r.amount);
        return (
          <tr key={r.key} data-component={r.key}>
            <td>
              {r.strong ? <b>{t(`o05.st.${r.key}`)}</b> : t(`o05.st.${r.key}`)}
              {r.key === 'GRATUITY' && !d.gratuity && <div className="sub2">{t('o05.gratuityBlocked')}</div>}
              {r.key === 'CPF_FINAL' && <div className="sub2">{t('o05.st.information')}</div>}
            </td>
            <td>{r.key === 'DUES' ? '' : t(`o05.st.${r.key}.by`)}</td>
            <td>{r.key === 'DUES' ? '' : t(`o05.st.${r.key}.basis`)}</td>
            <td className="num">
              {r.key === 'CPF_FINAL' ? (
                <CpfLink d={d} />
              ) : r.strong ? (
                <b className={r.amount !== null && r.amount < 0 ? 'fl-bad' : undefined}>{value}</b>
              ) : (
                value
              )}
            </td>
            <td className="num">{recoveryCell(r.key, r.recovery)}</td>
            <td>{r.key === 'DUES' ? '' : t(`o05.st.${r.key}.via`)}</td>
          </tr>
        );
      })}
    </>
  );
}

function CpfLink({ d }: { d: CaseDetail }) {
  const { t } = useI18n();
  if (d.cpfSanctionNumber) return <>{t('o05.st.linked', [d.cpfSanctionNumber])}</>;
  return <>{d.cpfAmount === null ? '—' : amount(d.cpfAmount)}</>;
}

function recoveryCell(key: string, recovery: number): string {
  if (key === 'DUES') return recovery > 0 ? `(${amount(recovery)})` : '—';
  if (key === 'NET') return '';
  return recovery > 0 ? `− ${amount(recovery)}` : '—';
}

/** The statement columns of the pack (Paid through last). */
export function useStatementCols() {
  const { t } = useI18n();
  return [
    { t: t('o05.component'), s: 'SYS' as const },
    { t: t('o05.computedBy'), s: 'SYS' as const },
    { t: t('o05.basis'), s: 'RULE' as const },
    { t: t('o05.col.amount'), s: 'CALC' as const, num: true },
    { t: t('o05.col.recoveryAgainst'), s: 'CALC' as const, num: true },
    { t: t('o05.paidThrough'), s: 'SYS' as const },
  ];
}

export function StatementCard({ d, onOpen }: { d: CaseDetail; onOpen: () => void }) {
  const { t } = useI18n();
  const cols = useStatementCols();
  return (
    <div>
      <Tbl
        title={caseTitle(t, 'o05.statementTitle', d)}
        label={t('o05.components')}
        acts={<Act kind="sm" label={t('o05.statementCard')} onClick={onOpen} />}
        cols={cols}
      >
        <StatementTable d={d} />
      </Tbl>
      {d.summary.netPayable !== null && d.summary.netPayable < 0 && (
        <p className="fl-hint">{t('o05.netRecovery')}</p>
      )}
    </div>
  );
}

/** SRS-10-25, BR-FNF-015: the latest entry of the case against its sanction letter. */
export function ExGratiaCard({ d }: { d: CaseDetail }) {
  const { t } = useI18n();
  const entry = d.exGratiaEntries.at(-1);
  return (
    <KvCard
      title={t('o05.exGratiaTitle')}
      rows={[
        { k: t('o05.kv.case'), s: 'SYS', v: `${d.summary.caseReference} · ${d.summary.name}` },
        {
          k: t('o05.letter'),
          s: 'ENT',
          v: entry ? t('o05.kv.letter', [entry.letterNo, date(entry.letterDate)]) : t('o05.kv.noLetter'),
        },
        {
          k: t('o05.amount'),
          s: 'ENT',
          v: entry ? t('o05.kv.amount', [amount(entry.amount)]) : '—',
        },
        { k: t('o05.kv.makerChecker'), s: 'SYS', v: t('o05.kv.makerCheckerText') },
        {
          k: t('o05.entryStatus'),
          s: 'SYS',
          v: entry ? <span className="pill p-warn">{t(`o05.exGratiaStatus.${entry.status}`)}</span> : '—',
        },
      ]}
    />
  );
}

/** Pack panel 'Recovery at separation': SRS-10-6, 10-7, 10-20, 10-21, 10-22; Assumption F-1. */
export function RecoveryRules() {
  const { t } = useI18n();
  return (
    <div className="fl-mt">
      <Panel
        title={t('o05.rules.title')}
        items={[
          { icon: '💳', tone: 'ok', title: t('o05.rules.full'), src: 'RULE' },
          {
            icon: '🎯',
            tone: 'info',
            title: t('o05.rules.source'),
            src: 'ENT',
            text: t('o05.rules.sourceText'),
          },
          { icon: '🏛', tone: 'pur', title: t('o05.rules.gratuity'), src: 'SYS' },
          { icon: '⛔', tone: 'bad', title: t('o05.rules.residual'), src: 'RULE' },
          {
            icon: '🕊',
            tone: 'info',
            title: t('o05.rules.death'),
            src: 'RULE',
            text: t('o05.rules.deathText'),
          },
          { icon: '↩️', tone: 'gray', title: t('o05.rules.reversed'), src: 'RULE' },
        ]}
      />
    </div>
  );
}

/**
 * SRS-G-37, SRS-10-31: each settlement payment with its invoice. A cancellation of a settlement payment
 * reaches APPS and has no contract yet (module 23), so ⊘ Cancel is drawn with its reason and sends nothing.
 */
export function SettlementInvoices({
  maker,
  memberOf,
}: {
  maker: boolean;
  memberOf: (row: InvoiceRow) => string;
}) {
  const { t } = useI18n();
  const title = t('o05.invoices');
  return (
    <ServerPagination
      queryKey={settlementKeys.invoices}
      fetchPage={settlementPages.invoices}
      empty={<InvoiceCard title={title} rows={[]} />}
    >
      {(rows) => (
        <InvoiceCard
          title={title}
          rows={rows.map((r) => ({
            key: String(r.caseId),
            entry: r.caseReference,
            member: memberOf(r),
            paymentType: t(r.creditMemo ? 'o05.pay.creditMemo' : 'o05.pay.net'),
            amount: amount(r.amount),
            invoiceNo: r.invoiceNo,
            invoiceStatus: r.invoiceStatus,
            sentToAppsDate: r.sentToApps,
            appsCancelled: r.appsCancelled,
            cancelled: r.settlementStatus === 'CANCELLED',
            cancelWhy: maker ? t('o05.why.cancelApps') : t('wf.inv.why.maker'),
            onCancel: () => undefined,
          }))}
        />
      )}
    </ServerPagination>
  );
}

/** SRS-G-30, SRS-G-32: the ex-gratia sanction letter or succession document stored with the case. */
export function SettlementOrders({ kase, mayAttach }: { kase: CaseDetail | null; mayAttach: boolean }) {
  const { t } = useI18n();
  const id = kase?.summary.caseId ?? null;
  const stored = useCaseOrders(id);
  const types = useDocumentTypes();
  const attach = useOrderAttach();
  const closed = kase !== null && ['POSTED', 'CLOSED'].includes(kase.summary.stage);
  return (
    <OrderCopyCard
      key={id ?? 0}
      hint={t('o05.ord.hint')}
      canAttach={mayAttach && kase !== null && !closed}
      readOnly={!mayAttach}
      stored={stored.data}
      storedError={stored.error ?? types.error}
      fileHref={(fileId) => orderFileUrl(id ?? 0, fileId)}
      today={businessToday()}
      attachmentRequired
      referenceRequired={(code) => types.data?.find((x) => x.code === code)?.referenceRequired ?? false}
      onAttach={async (o) => {
        if (id === null) return;
        for (const file of o.files) {
          await attach.mutateAsync({
            id,
            order: {
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
