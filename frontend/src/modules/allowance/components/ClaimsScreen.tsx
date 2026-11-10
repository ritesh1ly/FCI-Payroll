'use client';

import { useState } from 'react';
import { todayIso, useDocumentTypes } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, periodLabel, useMe } from '@/shared/session';
import { businessMonth, monthToYymm } from '@/shared/time';
import { amount, date, dateTime, Empty, ExportButton, Problem, ServerPagination } from '@/shared/ui';
import { RegisterSection, useApproveRow } from '@/shared/ui/masterParts';
import { InvoiceCard, type InvoiceRow } from '@/shared/ui/invoiceCard';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import {
  Act,
  Explained,
  Locked,
  Panel,
  RowActs,
  Tbl,
  Tiles,
  Toolbar,
  ViewDialog,
  WfTile,
  type KvRow,
} from '@/shared/ui/wireframe';
import { withBase } from '@/shared/ui/basePath';
import type { ClaimFilter, ClaimRow, LeaseRow } from '../api/claimsApi';
import {
  claimDocumentUrl,
  claimKeys,
  claimPage,
  claimRegisterUrl,
  leasePage,
  useClaimActions,
  useClaimCounts,
  useClaimDetail,
  useFirstClaims,
  useInvoicedClaims,
} from '../orchestration/claimQueries';
import { Claims } from './Claims';

const EIS = /^[A-Za-z0-9]{1,20}$/;

/** Pill tone of a claim status. */
function tone(status: string): string {
  if (status === 'APPROVED' || status === 'PAID') return 'p-ok';
  if (status === 'REJECTED' || status === 'OVERLAP_REJECTED' || status === 'CANCELLED') return 'p-bad';
  return 'p-warn';
}

/** The drawn period: a month when the claim covers one, the span otherwise. */
function periodText(c: ClaimRow): string {
  return `${date(c.periodFrom)} – ${date(c.periodTo)}`;
}

/** The ids of statuses waiting for the Payroll Checker's decision (W-01). */
const WITH_CHECKER = new Set(['VALIDATED', 'CAPPED']);

/**
 * I-03 Allowances & Reimbursements as Wireframes v6.1 draws it (OD-121; SRS §6.12, SRS-11-1, SRS-11-13, SRS-11-9,
 * SRS-11-11, SRS-11-17, SRS-G-30, SRS-G-37; DDL reimbursement_claim, lease_accommodation_detail): the source
 * banner, the tiles, the Claim Register of the month with row View / Edit / ✔, the Lease / Licence table, the
 * Entitlement rules, the invoice card and the order-copy card. An employee claim comes only from HRMS, approved
 * on I-21, and is read-only here. What the drawing leaves out stays in the closed register: the member and status
 * filters, the labour claim entry of the Labour Dealing Hand, the claims waiting on I-21, the validation, taxable
 * flag and IOGA of a claim and the Medical Register. A claim's own labels (HRMS Request ID, invoice) are in View.
 */
export function ClaimsScreen() {
  const { locale, t } = useI18n();
  const me = useMe();
  const may = (action: string) => can(me.data, 'I-03', action);
  const counts = useClaimCounts();
  const approve = useApproveRow();
  const period = me.data?.period ?? businessMonth();
  const month = monthToYymm(period);
  const [claimType, setClaimType] = useState('');
  const [search, setSearch] = useState('');
  // The Labour Dealing Hand enters claims from the register: it stands open for that role until closed.
  const [toggled, setRegister] = useState<boolean | null>(null);
  const register = toggled ?? may('CREATE');
  const [focus, setFocus] = useState<number | null>(null);
  const [selected, setSelected] = useState<ClaimRow | null>(null);
  const [viewing, setViewing] = useState<ClaimRow | null>(null);
  const [lease, setLease] = useState<LeaseRow | null>(null);
  const eis = EIS.test(search.trim()) ? search.trim() : undefined;
  const filter: ClaimFilter = { yymm: month, claimType: claimType || undefined, eis };
  // The order card reads the claim picked in the register, or its first row until one is picked (as I-06).
  const first = useFirstClaims(filter, may('VIEW'));
  if (!may('VIEW')) return null;

  function editWhy(c: ClaimRow): string {
    if (c.memberType === 'EMPLOYEE') return t('i03w.why.hrms');
    return may('VALIDATE') ? t('i03w.why.validated') : t('i03w.why.maker');
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {may('APPROVE') ? (
          <Act label={t('w01.approveSelected')} kind="pri" href={withBase('/payroll/?s=W-01')} />
        ) : (
          <Locked label={t('w01.approveSelected')} kind="pri" why={t('i03w.why.checker')} />
        )}
      </div>
      <div className="anno on">
        <b>{t('i03w.banner.lead')}</b> {t('i03w.banner.a')} <a href={withBase('/payroll/?s=I-21')}>I-21</a>
        {t('i03w.banner.b')}
      </div>
      <Toolbar
        search={search}
        onSearch={setSearch}
        exportControl={
          may('EXPORT') ? (
            <ExportButton href={claimRegisterUrl(filter)} what={t('i03.register')} label={t('wf.export')} />
          ) : (
            <Explained label={t('wf.export')} why={t('i03w.why.export')} />
          )
        }
      >
        <select
          aria-label={t('i03w.allTypes')}
          value={claimType}
          onChange={(e) => setClaimType(e.target.value)}
        >
          <option value="">{t('i03w.allTypes')}</option>
          <ReferenceOptions list="CLAIM_TYPE" />
        </select>
      </Toolbar>
      <Tiles>
        <WfTile
          icon="🧳"
          label={t('i03w.tile.claims')}
          src="INTF"
          big={counts.data?.claims ?? '—'}
          sub={t('i03w.tile.claimsSub', [amount(counts.data?.payable ?? 0)])}
        />
        <WfTile
          icon="✍️"
          tone="warn"
          label={t('i03w.tile.pending')}
          src="SYS"
          big={counts.data?.pendingApproval ?? '—'}
          sub={t('i03w.tile.pendingSub')}
        />
        <WfTile
          icon="⛔"
          tone="bad"
          bad
          label={t('i03w.tile.capped')}
          src="CALC"
          big={counts.data?.capped ?? '—'}
          sub={t('i03w.tile.cappedSub')}
        />
        <WfTile
          icon="🏠"
          tone="pur"
          label={t('i03w.tile.leases')}
          src="ENT"
          big={counts.data?.leases ?? '—'}
          sub={t('i03w.tile.leasesSub')}
        />
      </Tiles>
      <Tbl
        title={t('i03w.registerTitle', [periodLabel(period, locale)])}
        label={t('i03.register')}
        acts={<Act label={t('i03w.ceilingRules')} kind="sm" href={withBase('/payroll/?s=M-15')} />}
        cols={[
          { t: t('i03w.col.claim'), s: 'SYS' },
          { t: t('i03.member'), s: 'HRMS' },
          { t: t('i03w.col.type'), s: 'ESS' },
          { t: t('i03w.col.period'), s: 'ESS' },
          { t: t('i03w.col.claimed'), s: 'ESS', num: true },
          { t: t('i03w.col.entitlement'), s: 'RULE', num: true },
          { t: t('i03w.col.payable'), s: 'CALC', num: true },
          { t: t('i03w.col.source'), s: 'INTF' },
          { t: t('i03.status'), s: 'SYS' },
          { t: t('m04.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={claimKeys.register(filter)}
          fetchPage={claimPage(filter)}
          empty={<Empty text={t('i03.none')} />}
        >
          {(rows) =>
            rows.map((c) => {
              const editable = c.memberType === 'LABOUR' && may('VALIDATE') && c.status === 'SUBMITTED';
              return (
                <tr
                  key={c.id}
                  data-reference={String(c.id)}
                  aria-selected={selected?.id === c.id}
                  onClick={() => setSelected(c)}
                >
                  <td>
                    <b>{t('i03w.claimNo', [c.id])}</b>
                  </td>
                  <td>
                    {c.eis} {c.name}
                  </td>
                  <td>{c.claimTypeLabel ?? c.claimType}</td>
                  <td>{periodText(c)}</td>
                  <td className="num">{amount(c.claimed)}</td>
                  <td className="num">{c.entitlement === null ? '—' : amount(c.entitlement)}</td>
                  <td className="num">{c.payable === null ? '—' : amount(c.payable)}</td>
                  <td>{t(`i03.src.${c.source}`)}</td>
                  <td>
                    <span className={`pill ${tone(c.status)}`}>{t(`i03.st.${c.status}`)}</span>
                  </td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${t('i03w.claimNo', [c.id])}`}
                      onView={() => {
                        setSelected(c);
                        setViewing(c);
                      }}
                      onEdit={
                        editable
                          ? () => {
                              setSelected(c);
                              setFocus(c.id);
                              setRegister(true);
                            }
                          : undefined
                      }
                      editWhy={editWhy(c)}
                      onApprove={may('APPROVE') ? () => approve(WITH_CHECKER.has(c.status)) : undefined}
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
          title={t('i03w.leaseTitle')}
          cols={[
            { t: t('i03.member'), s: 'HRMS' },
            { t: t('i03w.col.accom'), s: 'ENT' },
            { t: t('i03w.col.lease'), s: 'ENT', num: true },
            { t: t('i03w.col.seven'), s: 'CALC', num: true },
            { t: t('i03w.col.hra'), s: 'CALC' },
            { t: t('i03.status'), s: 'SYS' },
            { t: t('m04.col.actions'), s: 'SYS' },
          ]}
        >
          <ServerPagination
            queryKey={claimKeys.leases()}
            fetchPage={leasePage}
            empty={<Empty text={t('i03w.noLease')} />}
          >
            {(rows) =>
              rows.map((l) => (
                <tr key={l.id} data-reference={l.eis}>
                  <td>
                    {l.eis} {l.name}
                  </td>
                  <td>{t(`i03w.accom.${l.accommodationType}`)}</td>
                  <td className="num">{l.leaseAmount === null ? '—' : amount(l.leaseAmount)}</td>
                  <td className="num">
                    {/* SRS-11-3: the 7 % rule is not configured (PENDING 10-lease): never a guessed figure. */}
                    {l.sevenPercent === null ? (
                      <span className="pill p-warn">{t('i03w.notConfigured')}</span>
                    ) : (
                      amount(l.sevenPercent)
                    )}
                  </td>
                  <td>{t(l.hraSuppressed ? 'i03w.hra.suppressed' : 'i03w.hra.paid')}</td>
                  <td>
                    <span className={`pill ${l.conflict ? 'p-bad' : 'p-ok'}`}>
                      {t(l.conflict ? 'i03w.lease.conflict' : 'i03w.lease.ok')}
                    </span>
                  </td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${l.eis}`}
                      onView={() => setLease(l)}
                      editWhy={t(l.memberType === 'LABOUR' ? 'i03w.why.leaseLabour' : 'i03w.why.leaseStaff')}
                      // Drawn on the labour row for the Checker; no lease record carries an approval item, so it says so.
                      onApprove={
                        l.memberType === 'LABOUR' && may('APPROVE') ? () => approve(false) : undefined
                      }
                    />
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
        <Panel
          title={t('m15w.rules')}
          items={[
            { icon: '✈️', tone: 'info', title: t('wf.rule.ltc'), src: 'RULE', text: t('wf.rule.ltcSub') },
            {
              icon: '🩺',
              tone: 'info',
              title: t('wf.rule.quarterly'),
              src: 'RULE',
              text: t('wf.rule.quarterlySub'),
            },
            {
              icon: '🏥',
              tone: 'info',
              title: t('wf.rule.indoor'),
              src: 'RULE',
              text: t('wf.rule.indoorSub'),
            },
            {
              icon: '🏠',
              tone: 'info',
              title: t('wf.rule.lease'),
              src: 'RULE',
              text: t('wf.rule.leaseSub'),
            },
            {
              icon: '⛔',
              tone: 'bad',
              title: t('wf.rule.overlap'),
              src: 'RULE',
              text: t('wf.rule.overlapSub'),
              right: <span className="pill p-bad">{t('wf.rule.enforced')}</span>,
            },
            {
              icon: '💡',
              tone: 'warn',
              title: t('i03w.rule.electricity'),
              src: 'ENT',
              text: t('i03w.rule.electricitySub'),
              right: '',
            },
          ]}
        />
      </div>
      <div className="oq fl-mt">
        <b>{t('i03w.note.lead')}</b> {t('i03w.note.text')}
      </div>
      <div className="fl-mt">
        <ClaimInvoices />
      </div>
      <ClaimOrders claim={selected ?? first.data?.items[0] ?? null} maker={may('VALIDATE')} />
      <RegisterSection id="i03-register" label={t('i03w.register')} open={register} onToggle={setRegister}>
        {register && <Claims focus={focus} />}
      </RegisterSection>
      {viewing && <ClaimView claim={viewing} onClose={() => setViewing(null)} />}
      {lease && (
        <ViewDialog
          screen={t('screen.I-03')}
          onClose={() => setLease(null)}
          rows={[
            { k: t('i03.member'), v: `${lease.eis} ${lease.name}`, s: 'HRMS' },
            { k: t('i03w.col.accom'), v: t(`i03w.accom.${lease.accommodationType}`), s: 'ENT' },
            {
              k: t('i03w.col.lease'),
              v: lease.leaseAmount === null ? '—' : amount(lease.leaseAmount),
              s: 'ENT',
            },
            { k: t('i03.hrmsRequestId'), v: lease.hrmsRequestId ?? '—', s: 'HRMS' },
            { k: t('mst.from'), v: date(lease.effectiveFrom), s: 'HRMS' },
            {
              k: t('i03w.col.hra'),
              v: t(lease.hraSuppressed ? 'i03w.hra.suppressed' : 'i03w.hra.paid'),
              s: 'CALC',
            },
          ]}
        />
      )}
    </div>
  );
}

/** The drawn View of a claim: what the register's columns leave out (SRS l.2804-2806). */
function ClaimView({ claim: c, onClose }: { claim: ClaimRow; onClose: () => void }) {
  const { t } = useI18n();
  const detail = useClaimDetail(c.id);
  const rows: KvRow[] = [
    { k: t('i03w.col.claim'), v: t('i03w.claimNo', [c.id]), s: 'SYS' },
    { k: t('i03.member'), v: `${c.eis} ${c.name}`, s: 'HRMS' },
    { k: t('i03.type'), v: c.claimTypeLabel ?? c.claimType, s: 'ESS' },
    { k: t('i03.period'), v: periodText(c), s: 'ESS' },
    { k: t('i03w.col.claimed'), v: amount(c.claimed), s: 'ESS' },
    { k: t('i03w.col.entitlement'), v: c.entitlement === null ? '—' : amount(c.entitlement), s: 'RULE' },
    { k: t('i03w.col.payable'), v: c.payable === null ? '—' : amount(c.payable), s: 'CALC' },
    { k: t('i03.status'), v: t(`i03.st.${c.status}`), s: 'SYS' },
    { k: t('i03.source'), v: t(`i03.src.${c.source}`), s: 'INTF' },
    { k: t('i03.hrmsRequestId'), v: c.hrmsRequestId ?? '—', s: 'HRMS' },
    { k: t('i03.invoiceStatus'), v: t(`i03.inv.${c.invoiceStatus}`), s: 'INTF' },
    { k: t('i03.invoiceSentAt'), v: c.invoiceSentAt ? dateTime(c.invoiceSentAt) : '—', s: 'INTF' },
    {
      k: t('i03.documents'),
      v: (detail.data?.documents ?? []).map((d) => d.fileName).join(', ') || '—',
      s: 'SYS',
    },
  ];
  return <ViewDialog screen={t('screen.I-03')} rows={rows} onClose={onClose} />;
}

/** The drawn invoice card (SRS-G-37, SRS-11-19): the invoice of each paid claim. */
function ClaimInvoices() {
  const { t } = useI18n();
  const paid = useInvoicedClaims();
  // The cancellation path of a claim's invoice is not built (PENDING 10-invoice): drawn, off, with the reason.
  const rows: InvoiceRow[] = (paid.data?.items ?? []).map((c) => ({
    key: String(c.id),
    entry: t('i03w.claimNo', [c.id]),
    member: `${c.eis} ${c.name}`,
    paymentType: c.claimTypeLabel ?? c.claimType,
    amount: amount(c.payable),
    invoiceNo: null,
    invoiceStatus: c.invoiceStatus,
    sentToAppsDate: c.invoiceSentAt ?? null,
    appsCancelled: c.invoiceStatus === 'CANCELLED_IN_APPS',
    cancelled: c.status === 'CANCELLED',
    cancelWhy: t('i03w.why.invoice'),
    onCancel: () => undefined,
  }));
  return (
    <>
      <Problem error={paid.error} />
      <InvoiceCard title={t('i03w.invoiceTitle')} rows={rows} />
    </>
  );
}

/**
 * The drawn order-copy card (FRS §6.4.3, A-41) for the claim picked in the register: its stored bills and
 * claim documents. The Payroll Maker adds a document to a labour claim not yet validated; an employee claim
 * is HRMS's. The documents an entry needs stay on the entry form (SRS l.2802, WF-1405).
 */
function ClaimOrders({ claim, maker }: { claim: ClaimRow | null; maker: boolean }) {
  const { t } = useI18n();
  const detail = useClaimDetail(claim?.id ?? null);
  const types = useDocumentTypes(true);
  const actions = useClaimActions();
  const open = claim !== null && claim.memberType === 'LABOUR' && claim.status === 'SUBMITTED';
  return (
    <OrderCopyCard
      key={claim?.id ?? 0}
      hint={t('i03w.ord.hint')}
      canAttach={maker && open}
      readOnly={!maker}
      stored={detail.data?.documents.map((d) => ({
        id: d.id,
        fileName: d.fileName,
        documentTypeCode: d.documentTypeCode ?? null,
        documentTypeName: d.documentTypeName,
        referenceNumber: d.referenceNumber,
        uploadedBy: d.uploadedBy,
        uploadedAt: d.uploadedAt,
      }))}
      storedError={detail.error ?? types.error}
      fileHref={(fileId) => claimDocumentUrl(claim?.id ?? 0, fileId)}
      today={todayIso()}
      // SRS-11-14: the required documents are checked at entry; the card adds to them.
      attachmentRequired={false}
      officeRequired
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      onAttach={async (o) => {
        if (!claim) return;
        await actions.attach.mutateAsync({
          id: claim.id,
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
