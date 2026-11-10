'use client';

import { type ReactNode, useEffect, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, periodLabel, useMe } from '@/shared/session';
import { yymmToMonth } from '@/shared/time';
import { API_BASE, ApiError } from '@/shared/api';
import {
  amount,
  date,
  dateTime,
  downloadFile,
  Empty,
  Problem,
  Src,
  TextAreaField,
  useToast,
  withBase,
} from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { Act, Explained, KvCard, Locked, Panel, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { MemberDocument, MemberOverview, MemberPayslip, MemberRequest, Preview } from '../api/esevaApi';
import {
  documentPdfPath,
  initials,
  payslipPdfPath,
  previewReady,
  REASON_MAX,
  requestIcon,
  requestTone,
  slipRows,
} from '../business/esevaRules';
import {
  useClosePreview,
  useMemberOverview,
  useMemberSlip,
  useOwnBand,
  usePreview,
  usePreviewOverview,
} from '../orchestration/esevaQueries';
import { MyNotifications } from './MyNotifications';
import { PayslipDocument } from './PayslipDocument';
import { PrivacyNotice } from './PrivacyNotice';

/** What the drawn HRMS card is told in a support preview (null for the member). */
export interface HrmsView {
  why: string;
  rows: { value: string | null; status: string; submittedAt: string }[];
}

/**
 * The functions of other modules, composed by the shell (the eSeva module depends on none): each stays
 * whole, mounted only when its closed section opens, so no read fires until the member asks for it.
 */
export interface EsevaPortalParts {
  record: ReactNode;
  withdrawals: ReactNode;
  vpf: ReactNode;
  statement: ReactNode;
  tax: ReactNode;
  form16: ReactNode;
  leave: ReactNode;
  payslips: (onOpen: (yymm: string) => void) => ReactNode;
  hrms: (preview: HrmsView | undefined) => ReactNode;
}

type Open = (...sections: string[]) => void;

/** What the drawn objects show: one member, the signed-in one or the one of the support session. */
interface Drawn {
  /** Support preview: every submit disabled with its drawn reason (SRS-17-6, l.496). */
  preview: boolean;
  name: string | null;
  line: string;
  overview: MemberOverview | undefined;
  slip: MemberPayslip | undefined;
  error: unknown;
  onOpen: Open;
  /** Opens the HRMS card's place on the page. */
  onHrms: () => void;
  parts: EsevaPortalParts;
  /** The support preview's session (null for the member): which payslip PDF is read. */
  sessionId: number | null;
}

/** OD-144: downloads a server PDF; a refusal is said in a toast (the drawn buttons have no dialog). */
function useDocPdf() {
  const { t } = useI18n();
  const toast = useToast();
  return (path: string, name: string) => {
    void downloadFile(`${API_BASE}${path}`, name).catch((cause: unknown) => {
      const e = cause instanceof ApiError ? cause : new ApiError(0, {}, undefined);
      toast(t(e.messageKey, e.args));
    });
  };
}

function fy(t: (key: string, args?: readonly unknown[]) => string, year: string): string {
  return t('e01w.fy', [year]);
}

/** Which closed sections are open; opening one by a drawn button scrolls to it. */
function useSections() {
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set());
  const [target, setTarget] = useState<string | null>(null);
  useEffect(() => {
    if (target) document.getElementById(target)?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  }, [target]);
  const show: Open = (...sections) => {
    setOpen((now) => new Set([...now, ...sections]));
    const first = sections[0];
    if (first) setTarget(`e01-${first}`);
  };
  const toggle = (section: string, on: boolean) =>
    setOpen((now) => {
      const next = new Set(now);
      if (on) next.add(section);
      else next.delete(section);
      return next;
    });
  return { open, show, toggle, scrollTo: setTarget };
}

/** A closed section holding one undrawn function; the function is mounted when it opens. */
function Section({
  id,
  label,
  sections,
  children,
}: {
  id: string;
  label: string;
  sections: ReturnType<typeof useSections>;
  children: ReactNode;
}) {
  const open = sections.open.has(id);
  return (
    <div id={`e01-${id}`}>
      <RegisterSection
        id={`e01-section-${id}`}
        label={label}
        open={open}
        onToggle={(on) => sections.toggle(id, on)}
      >
        {open && children}
      </RegisterSection>
    </div>
  );
}

/**
 * E-01 Employee Self-Service (eSeva) Portal as Wireframes v6.1 draws it (OD-121, OD-135; SRS-17-1,
 * SRS-17-2, SRS-17-6, SRS-17-12, SRS-17-14..17, BR-ESEVA-004..006). Two modes on one component:
 * the member (E-01 SELF) reads and submits on the own record; a staff user holding E-01 VIEW only
 * sees the same drawn objects in a support preview (l.496, l.5499), every submit disabled with its
 * drawn reason, opened for one named member with a reason and closed again (SRS-17-8). A staff user
 * never calls a /mine read: a refused call ends the session (OD-130). The PDFs (OD-144) are the server's: My Payslip
 * (the latest locked month), My Form 16 (the latest released certificate, Part A first) and each row's ⇩ PDF.
 */
export function EsevaPortal(parts: EsevaPortalParts) {
  const me = useMe();
  if (!me.data) return null;
  return can(me.data, 'E-01', 'SELF') ? <MemberPortal parts={parts} /> : <StaffPortal parts={parts} />;
}

function MemberPortal({ parts }: { parts: EsevaPortalParts }) {
  const { t } = useI18n();
  const overview = useMemberOverview(true);
  const own = useOwnBand(true);
  const sections = useSections();
  const [month, setMonth] = useState<string | null>(null);
  const [document, setDocument] = useState(false);
  const shown = month ?? overview.data?.latest?.yymm ?? null;
  const slip = useMemberSlip(null, shown, false);
  const band = own.data;
  const name = band ? `${band.firstName} ${band.lastName ?? ''}`.trim() : null;
  const pattern = band ? [band.payPattern, band.band].filter(Boolean).join(' ') : '';
  const line = band
    ? [`${t('m01.eis')} ${band.eis}`, band.designation, pattern, band.location].filter(Boolean).join(' · ')
    : '';
  const openSlip = (yymm: string) => {
    setMonth(yymm);
    setDocument(true);
  };
  return (
    <>
      <PrivacyNotice />
      <div className="wireframe-body-container">
        <DrawnPortal
          preview={false}
          name={name}
          line={line}
          overview={overview.data}
          slip={slip.data}
          error={overview.error ?? own.error ?? slip.error}
          onOpen={sections.show}
          onHrms={() => sections.scrollTo('e01-hrms')}
          parts={parts}
          sessionId={null}
        />
        <Section id="record" label={t('e01w.sec.record')} sections={sections}>
          {parts.record}
        </Section>
        <Section id="notices" label={t('e01.notices')} sections={sections}>
          <MyNotifications />
        </Section>
        <Section id="withdrawals" label={t('e01w.sec.withdrawals')} sections={sections}>
          {parts.withdrawals}
        </Section>
        <Section id="vpf" label={t('e01w.sec.vpf')} sections={sections}>
          {parts.vpf}
        </Section>
        <Section id="statement" label={t('e01w.sec.statement')} sections={sections}>
          {parts.statement}
        </Section>
        <Section id="tax" label={t('e01w.sec.tax')} sections={sections}>
          {parts.tax}
        </Section>
        <Section id="form16" label={t('e01w.sec.form16')} sections={sections}>
          {parts.form16}
        </Section>
        <Section id="months" label={t('e01w.previousMonths')} sections={sections}>
          {parts.payslips(openSlip)}
        </Section>
        <Section id="leave" label={t('e01w.sec.leave')} sections={sections}>
          {parts.leave}
        </Section>
        <Section id="claim" label={t('e01w.sec.claim')} sections={sections}>
          <LabourClaimSubmit />
        </Section>
      </div>
      {document && slip.data && (
        <PayslipDocument
          slip={slip.data}
          overview={overview.data}
          cpfCode={band?.cpfCode ?? null}
          onClose={() => setDocument(false)}
        />
      )}
    </>
  );
}

function StaffPortal({ parts }: { parts: EsevaPortalParts }) {
  const { t } = useI18n();
  const me = useMe();
  const sections = useSections();
  const [session, setSession] = useState<Preview | null>(null);
  const [month, setMonth] = useState<string | null>(null);
  const [document, setDocument] = useState(false);
  const id = session?.sessionId ?? null;
  const overview = usePreviewOverview(id);
  const shown = month ?? overview.data?.latest?.yymm ?? null;
  const slip = useMemberSlip(id, shown, true);
  const member = session?.member;
  const p = slip.data;
  const line = member
    ? [`${t('m01.eis')} ${member.eis}`, p?.designation, p?.payPattern, member.office]
        .filter(Boolean)
        .join(' · ')
    : '';
  const openSlip = (yymm: string) => {
    setMonth(yymm);
    setDocument(true);
  };
  return (
    <div className="wireframe-body-container">
      <Section id="session" label={t('e01w.sec.session')} sections={sections}>
        <SupportSession
          session={session}
          onOpened={(s) => {
            setSession(s);
            setMonth(null);
          }}
          onClosed={() => {
            setSession(null);
            setMonth(null);
            setDocument(false);
          }}
        />
      </Section>
      <DrawnPortal
        preview
        name={member?.name ?? null}
        line={line}
        overview={overview.data}
        slip={p}
        error={overview.error ?? slip.error}
        onOpen={sections.show}
        onHrms={() => sections.scrollTo('e01-hrms')}
        parts={parts}
        sessionId={id}
      />
      <Section id="member" label={t('e01w.sec.member')} sections={sections}>
        <KvCard
          title={t('e01w.sec.member')}
          rows={[
            { k: t('e02.office'), v: member?.office ?? '—', s: 'HRMS' },
            { k: t('m01.pan'), v: member?.panMasked ?? '—', s: 'HRMS' },
            { k: t('e02.aadhaar'), v: member?.aadhaarMasked ?? '—', s: 'HRMS' },
            { k: t('e02.bankAccount'), v: member?.bankAccountMasked ?? '—', s: 'HRMS' },
            { k: t('e02.mobile'), v: member?.mobileMasked ?? '—', s: 'HRMS' },
            { k: t('e02.email'), v: member?.emailMasked ?? '—', s: 'HRMS' },
          ]}
        />
      </Section>
      <Section id="months" label={t('e01w.previousMonths')} sections={sections}>
        <PreviewMonths months={session?.payslipMonths ?? []} onOpen={openSlip} />
      </Section>
      <Section id="submissions" label={t('e01w.sec.submissions')} sections={sections}>
        <PreviewSubmissions rows={session?.submissions ?? []} />
      </Section>
      <Section id="claim" label={t('e01w.sec.claim')} sections={sections}>
        <LabourClaimSubmit />
      </Section>
      {/* G04: E-02 has no sidebar entry (WF-204); the Submission Log and the VPF verification live there. */}
      {can(me.data, 'E-02', 'VIEW') && (
        <Section id="support" label={t('e01w.sec.support')} sections={sections}>
          <div className="card">
            <div className="bd">
              <p className="fl-hint">{t('e01w.support.text')}</p>
              <a className="btn" href={withBase('/payroll/?s=E-02')}>
                {t('e01w.support.open')}
              </a>
            </div>
          </div>
        </Section>
      )}
      {document && p && (
        <PayslipDocument
          slip={p}
          overview={overview.data}
          cpfCode={null}
          sessionId={id}
          onClose={() => setDocument(false)}
        />
      )}
    </div>
  );
}

/**
 * SRS l.496, SRS-17-8 (not drawn; the pack's banner is stripped at run time, so it stands in a closed section):
 * the staff user names the member and the reason, which opens a recorded session; Close stamps its end. Never
 * opened by itself; the portal above it stays empty until then, and no /mine read is made.
 */
function SupportSession({
  session,
  onOpened,
  onClosed,
}: {
  session: Preview | null;
  onOpened: (s: Preview) => void;
  onClosed: () => void;
}) {
  const { t } = useI18n();
  const open = usePreview();
  const close = useClosePreview();
  const [eis, setEis] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const ready = previewReady(eis, reason);

  async function start() {
    setError(null);
    try {
      onOpened(await open.mutateAsync({ eis: eis.trim(), reason: reason.trim() }));
      setReason('');
    } catch (cause) {
      setError(cause);
    }
  }

  async function finish(id: number) {
    setError(null);
    try {
      await close.mutateAsync(id);
      onClosed();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="card" data-testid="e01-support-session">
      <div className="bd">
        <p className="sub2">{t('e02.previewNote')}</p>
        <Problem error={error} />
        {session ? (
          <div className="toolbar">
            <span role="status">{t('e02.readOnly', [String(session.sessionId)])}</span>
            <span className="sp" />
            <button
              type="button"
              className="btn"
              disabled={close.isPending}
              onClick={() => void finish(session.sessionId)}
            >
              {t('action.e02.closePreview')}
            </button>
          </div>
        ) : (
          <div className="toolbar">
            <label>
              {t('m01.eis')} <input maxLength={20} value={eis} onChange={(e) => setEis(e.target.value)} />
            </label>
            <label>
              {t('common.reason')}{' '}
              <TextAreaField
                maxLength={REASON_MAX}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </label>
            <button
              type="button"
              className="btn pri"
              disabled={!ready || open.isPending}
              title={ready ? undefined : t('e02.why.preview')}
              data-why={ready ? undefined : t('e02.why.preview')}
              onClick={() => void start()}
            >
              {t('action.e02.preview')}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

function DrawnPortal(d: Drawn) {
  const { t, locale } = useI18n();
  const { preview, overview, slip } = d;
  const latest = overview?.latest ?? null;
  const month = (yymm: string) => periodLabel(yymmToMonth(yymm), locale);
  const hrmsRows = (overview?.myRequests ?? []).filter((r) => r.type === 'HRMS_UPDATE_REQUEST');
  const pdf = useDocPdf();
  const form16 = (overview?.documents ?? []).find((doc) => doc.kind === 'FORM_16' && doc.state === 'ISSUED');
  const form16Path = form16 ? documentPdfPath(form16, d.sessionId) : null;
  // OD-153: the latest released certificate has no PDF on file when it was released before issued copies were kept.
  const noCopy =
    !form16 &&
    (overview?.documents ?? []).some((doc) => doc.kind === 'FORM_16' && doc.state === 'ISSUED_NO_COPY');
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        {/* OD-144: the latest locked month and the latest released certificate as the server's PDF. */}
        {latest ? (
          <Act
            label={t('e01w.myPayslip')}
            onClick={() => pdf(payslipPdfPath(latest.yymm, d.sessionId), `payslip-${latest.yymm}.pdf`)}
          />
        ) : (
          <Explained label={t('e01w.myPayslip')} why={t('e01w.why.noPayslip')} />
        )}
        {form16Path && form16 ? (
          <Act label={t('e01w.myForm16')} onClick={() => pdf(form16Path, `form16-${form16.period}.pdf`)} />
        ) : (
          <Explained
            label={t('e01w.myForm16')}
            why={t(
              preview ? 'e01w.why.form16Preview' : noCopy ? 'e01w.why.noIssuedCopy' : 'e01w.why.noForm16',
            )}
          />
        )}
        {preview ? (
          <Locked label={t('e01w.submitDeclaration')} why={t('e01w.why.declaration')} kind="pri" />
        ) : (
          <Act label={t('e01w.submitDeclaration')} kind="pri" onClick={() => d.onOpen('tax')} />
        )}
      </div>
      <Problem error={d.error} />
      <div className="card e01-band">
        <div className="bd">
          <div className="me" aria-hidden="true">
            {d.name ? initials(d.name) : '—'}
          </div>
          <div>
            <div className="e01-band-name">{d.name ?? '—'}</div>
            <div className="e01-band-line">{d.line}</div>
          </div>
          <div className="e01-band-gap" />
          <div className="e01-band-net">
            <div className="e01-band-line">
              {latest ? t('e01w.netPay', [month(latest.yymm)]) : t('e01w.netPayNone')}
            </div>
            <div className="e01-band-amount">{latest ? `₹ ${amount(latest.net)}` : '—'}</div>
          </div>
        </div>
      </div>
      <OverviewTiles overview={overview} />
      <Documents documents={overview?.documents ?? []} preview={preview} sessionId={d.sessionId} />
      <div className="g2 fl-mt">
        <PayslipTable slip={slip} onPrevious={() => d.onOpen('months')} />
        <div>
          <SelfServiceActions preview={preview} onOpen={d.onOpen} onHrms={d.onHrms} />
          <MyRequests
            rows={overview?.myRequests ?? []}
            onHistory={() => d.onOpen(preview ? 'submissions' : 'notices')}
          />
        </div>
      </div>
      {d.parts.hrms(preview ? { why: t('e01w.why.hrmsRaise'), rows: hrmsRows } : undefined)}
      <LabourView slip={slip} />
      <div className="fl-mt">
        <VpfCard overview={overview} />
      </div>
    </>
  );
}

function OverviewTiles({ overview }: { overview: MemberOverview | undefined }) {
  const { t } = useI18n();
  const cpf = overview?.cpf;
  const loan = overview?.loan;
  const tds = overview?.tds;
  const requests = overview?.requests;
  const money = (value: number | null | undefined) =>
    value === null || value === undefined ? '—' : `₹ ${amount(value)}`;
  let loanSub = '';
  if (loan) {
    loanSub = t('e01w.tile.loanSub', [loan.type, loan.number, loan.paid, loan.total]);
    if (loan.open > 1) loanSub = `${loanSub} ${t('e01w.tile.loanMore', [loan.open - 1])}`;
  } else if (overview) {
    loanSub = t('e01w.tile.loanNone');
  }
  const regime = tds?.regime ? t(`e01t.regime.${tds.regime}`) : t('e01w.tile.regimeNone');
  return (
    <Tiles>
      <WfTile
        icon="🏦"
        tone="ok"
        label={t('e01w.tile.cpf')}
        src="CALC"
        big={money(cpf?.balance)}
        sub={cpf ? t('e01w.tile.cpfSub', [fy(t, cpf.finYear)]) : ''}
      />
      <WfTile
        icon="💳"
        tone="pur"
        label={t('e01w.tile.loan')}
        src="CALC"
        big={money(loan?.outstanding)}
        sub={loanSub}
      />
      <WfTile
        icon="🧾"
        label={t('e01w.tile.tds')}
        src="CALC"
        big={tds && tds.amount === null ? t('e01w.notConfigured') : money(tds?.amount)}
        sub={tds ? `${regime} · ${fy(t, tds.finYear)}` : ''}
      />
      <WfTile
        icon="🏦"
        tone="ok"
        label={t('e01w.tile.vpf')}
        src="ESS"
        big={money(cpf?.vpfMonthly)}
        sub={t('e01w.tile.vpfSub')}
      />
      <WfTile
        icon="📨"
        tone="warn"
        label={t('e01w.tile.open')}
        src="SYS"
        big={requests ? requests.open : '—'}
        sub={requests ? t('e01w.tile.openSub', [requests.hrms, requests.cpf, requests.other]) : ''}
      />
    </Tiles>
  );
}

const DOCUMENT_TONE: Record<MemberDocument['state'], string> = {
  FINAL: 'p-ok',
  ISSUED: 'p-ok',
  ISSUED_NO_COPY: 'p-ok',
  NOT_YET_AVAILABLE: 'p-gray',
  NOT_DUE: 'p-gray',
  NOT_AVAILABLE: 'p-gray',
};

function Documents({
  documents,
  preview,
  sessionId,
}: {
  documents: MemberDocument[];
  preview: boolean;
  sessionId: number | null;
}) {
  const { t, locale } = useI18n();
  return (
    <div className="fl-mt">
      <Tbl
        title={t('e01w.docs.title')}
        acts={
          preview ? (
            <Locked label={t('e01w.reissue')} why={t('e01w.why.reissue')} kind="sm" />
          ) : (
            <Explained label={t('e01w.reissue')} why={t('e01w.why.reissue')} kind="sm" />
          )
        }
        cols={[
          { t: t('e01w.docs.document'), s: 'RULE' },
          { t: t('e01w.docs.period'), s: 'SYS' },
          { t: t('e01w.docs.issued'), s: 'SYS' },
          { t: t('e01w.docs.basis'), s: 'SYS' },
          { t: t('e01w.docs.status'), s: 'SYS' },
          { t: t('e01w.docs.download'), s: 'SYS' },
        ]}
      >
        {documents.length === 0 ? (
          <tr>
            <td>—</td>
            <td>—</td>
            <td>—</td>
            <td>—</td>
            <td>—</td>
            <td>
              <PdfCell
                doc={{ kind: 'PAY_SLIP', period: '', issued: null, state: 'NOT_AVAILABLE', id: null }}
                sessionId={null}
              />
            </td>
          </tr>
        ) : (
          documents.map((doc) => (
            <tr key={`${doc.kind}-${doc.period}-${doc.state}`}>
              <td>
                <b>{t(`e01w.doc.kind.${doc.kind}`)}</b>
              </td>
              <td>
                {doc.kind === 'PAY_SLIP' ? periodLabel(yymmToMonth(doc.period), locale) : fy(t, doc.period)}
              </td>
              <td>{doc.issued ? date(doc.issued) : '—'}</td>
              <td>{t(`e01w.basis.${doc.kind}.${doc.state}`)}</td>
              <td>
                <span className={`pill ${DOCUMENT_TONE[doc.state]}`}>{t(`e01w.docState.${doc.state}`)}</span>
              </td>
              <td>
                <PdfCell doc={doc} sessionId={sessionId} />
              </td>
            </tr>
          ))
        )}
      </Tbl>
    </div>
  );
}

/**
 * The drawn ⇩ PDF of a row: a month not locked has none and a year not closed is disabled, both as drawn; a locked
 * month and a released Form 16 download the server's PDF (OD-144); any other row says why when pressed.
 */
function PdfCell({ doc, sessionId }: { doc: MemberDocument; sessionId: number | null }) {
  const { t } = useI18n();
  const toast = useToast();
  const pdf = useDocPdf();
  if (doc.state === 'NOT_YET_AVAILABLE') return <>—</>;
  if (doc.state === 'NOT_DUE' || doc.state === 'ISSUED_NO_COPY') {
    // OD-153 ("Disable PDF with reason"): a Form 16 released before issued copies were kept has no PDF.
    const why = t(doc.state === 'NOT_DUE' ? 'e01w.why.notDue' : 'e01w.why.noIssuedCopy');
    return (
      <button type="button" className="ia off pdf" disabled title={why} data-why={why}>
        {t('e01w.pdf')}
      </button>
    );
  }
  const path = documentPdfPath(doc, sessionId);
  if (path) {
    const name = doc.kind === 'PAY_SLIP' ? `payslip-${doc.period}.pdf` : `form16-${doc.period}.pdf`;
    return (
      <button type="button" className="ia pdf" onClick={() => pdf(path, name)}>
        {t('e01w.pdf')}
      </button>
    );
  }
  const why = t(doc.kind === 'FORM_16' && sessionId !== null ? 'e01w.why.form16Preview' : 'e01w.why.pdf');
  return (
    <button type="button" className="ia pdf" title={why} data-guard={why} onClick={() => toast(why)}>
      {t('e01w.pdf')}
    </button>
  );
}

function PayslipTable({ slip, onPrevious }: { slip: MemberPayslip | undefined; onPrevious: () => void }) {
  const { t, locale } = useI18n();
  return (
    <Tbl
      title={slip ? t('e01w.payslip', [periodLabel(yymmToMonth(slip.yymm), locale)]) : t('e01w.payslipNone')}
      label={t('e01w.payslipLabel')}
      acts={<Act label={t('e01w.previousMonths')} kind="sm" onClick={onPrevious} />}
      cols={[
        { t: t('e01p.earnings'), s: 'CALC' },
        { t: t('e01w.amount'), s: 'CALC', num: true },
        { t: t('e01w.deductions'), s: 'CALC' },
        { t: t('e01w.amount'), s: 'CALC', num: true },
      ]}
      foot={
        slip && (
          <tr>
            <td>
              <b>{t('e01w.gross')}</b>
            </td>
            <td className="num">
              <b>{amount(slip.gross)}</b>
            </td>
            <td>
              <b>{t('e01w.totalNet')}</b>
            </td>
            <td className="num">
              <b>
                {amount(slip.totalDeductions)} · {amount(slip.net)}
              </b>
            </td>
          </tr>
        )
      }
      after={
        // SRS-17-7, BR-ESEVA-005: a labour member's payslip is the wage set, never the staff set.
        slip?.layoutVariant === 'LABOUR' ? (
          <p className="bd sub2" data-layout="LABOUR">
            {t('e01w.labourLayout')}
          </p>
        ) : undefined
      }
    >
      {slip ? (
        slipRows(slip.earnings, slip.deductions).map(([e, d], i) => (
          <tr key={i}>
            <td>{e?.name ?? ''}</td>
            <td className="num">{e ? amount(e.amount) : ''}</td>
            <td>{d?.name ?? ''}</td>
            <td className="num">{d ? amount(d.amount) : ''}</td>
          </tr>
        ))
      ) : (
        <tr>
          <td>—</td>
          <td className="num">—</td>
          <td>—</td>
          <td className="num">—</td>
        </tr>
      )}
    </Tbl>
  );
}

/** One drawn tile of the Self-Service Actions card (a `.q`, as the pack draws it). */
function ActionTile({ label, why, onPick }: { label: string; why?: string; onPick?: () => void }) {
  const toast = useToast();
  const pick = () => (why ? toast(why) : onPick?.());
  return (
    <div
      className="q"
      role="button"
      tabIndex={0}
      aria-disabled={why ? true : undefined}
      title={why}
      data-why={why}
      onClick={pick}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          pick();
        }
      }}
    >
      {label}
    </div>
  );
}

function SelfServiceActions({
  preview,
  onOpen,
  onHrms,
}: {
  preview: boolean;
  onOpen: Open;
  onHrms: () => void;
}) {
  const { t } = useI18n();
  const off = preview ? t('e01w.why.preview') : undefined;
  return (
    <div className="card e01-actions">
      <div className="hd">
        <h3>{t('e01w.actions')}</h3>
      </div>
      <div className="bd">
        <div className="qa">
          <ActionTile label={t('e01w.act.payslip')} why={off} onPick={() => onOpen('months', 'form16')} />
          <ActionTile label={t('e01w.act.tax')} why={off} onPick={() => onOpen('tax')} />
          <ActionTile label={t('e01w.act.regime')} why={off} onPick={() => onOpen('tax')} />
          <ActionTile label={t('e01w.act.hrms')} why={off} onPick={onHrms} />
          <ActionTile label={t('e01w.act.loan')} why={off ?? t('e01w.why.loan')} />
          <ActionTile label={t('e01w.act.cpf')} why={off} onPick={() => onOpen('withdrawals')} />
          <ActionTile label={t('e01w.act.statement')} why={off} onPick={() => onOpen('statement')} />
          <ActionTile label={t('e01w.act.status')} why={off} onPick={() => onOpen('notices')} />
        </div>
      </div>
    </div>
  );
}

function MyRequests({ rows, onHistory }: { rows: MemberRequest[]; onHistory: () => void }) {
  const { t } = useI18n();
  return (
    <Panel
      title={t('e01w.requests')}
      act={<Act label={t('e01w.history')} kind="sm" onClick={onHistory} />}
      items={rows.map((r) => ({
        icon: requestIcon(r.type),
        tone: 'info' as const,
        title: t(`e02.type.${r.type}`),
        src: 'ESS' as const,
        text: `${r.value ? `${r.value} · ` : ''}${dateTime(r.submittedAt)}`,
        right: <span className={`pill ${requestTone(r.status)}`}>{t(`e02.status.${r.status}`)}</span>,
      }))}
      after={rows.length === 0 ? <p className="bd fl-hint">—</p> : undefined}
    />
  );
}

/** The drawn table has no button: a labour claim cannot be submitted yet, so the reason stands in a closed section. */
function LabourClaimSubmit() {
  const { t } = useI18n();
  return (
    <div className="card">
      <div className="bd">
        <Locked label={t('e01w.claim.submit')} why={t('e01w.why.labourClaim')} kind="sm" />
      </div>
    </div>
  );
}

/** G20, G24: drawn for every viewer (WF row); claim submission has no member API yet. */
function LabourView({ slip }: { slip: MemberPayslip | undefined }) {
  const { t } = useI18n();
  const labour = slip?.layoutVariant === 'LABOUR';
  return (
    <div className="g2 fl-mt">
      <KvCard
        title={labour ? t('e01w.labour.titleOf', [slip.eis, slip.name]) : t('e01w.labour.title')}
        rows={[
          { k: t('e01w.labour.layout'), v: t('e01w.labour.layoutValue'), s: 'RULE' },
          { k: t('e01w.labour.claims'), v: t('e01w.labour.claimsValue'), s: 'ESS' },
          { k: t('e01w.labour.cpf'), v: t('e01w.labour.cpfValue'), s: 'CALC' },
          { k: t('e01w.labour.signIn'), v: t('e01w.labour.signInValue'), s: 'SYS' },
        ]}
      />
      <Tbl
        title={t('e01w.claim.title')}
        cols={[
          { t: t('e01w.claim.field'), s: 'ESS' },
          { t: t('e01w.claim.value'), s: 'ESS' },
        ]}
      >
        <tr>
          <td>{t('e01w.claim.type')}</td>
          <td>—</td>
        </tr>
        <tr>
          <td>{t('e01w.claim.period')}</td>
          <td>—</td>
        </tr>
        <tr>
          <td>{t('e01w.claim.amount')}</td>
          <td>—</td>
        </tr>
        <tr>
          <td>{t('e01w.claim.document')}</td>
          <td>—</td>
        </tr>
        <tr>
          <td>{t('e01w.claim.routes')}</td>
          <td>{t('e01w.claim.routesValue')}</td>
        </tr>
      </Tbl>
    </div>
  );
}

function VpfCard({ overview }: { overview: MemberOverview | undefined }) {
  const { t } = useI18n();
  const vpf = overview?.cpf?.vpfMonthly;
  const request = overview?.myRequests.find((r) => r.type === 'VPF_SUBSCRIPTION');
  return (
    <KvCard
      title={t('e01w.vpf.title')}
      rows={[
        {
          k: t('e01w.vpf.current'),
          v: vpf === null || vpf === undefined ? '—' : t('e01w.vpf.perMonth', [amount(vpf)]),
          s: 'ESS',
        },
        { k: t('e01w.vpf.requested'), v: request?.value ?? '—', s: 'ESS' },
        { k: t('e01w.vpf.routing'), v: t('e01w.vpf.routingValue'), s: 'SYS' },
        {
          k: t('e01w.vpf.status'),
          v: request ? (
            <span className={`pill ${requestTone(request.status)}`}>{t(`e02.status.${request.status}`)}</span>
          ) : (
            '—'
          ),
          s: 'SYS',
        },
      ]}
    />
  );
}

function PreviewMonths({
  months,
  onOpen,
}: {
  months: Preview['payslipMonths'];
  onOpen: (yymm: string) => void;
}) {
  const { t, locale } = useI18n();
  if (months.length === 0) return <Empty text={t('e02.noPayslips')} />;
  return (
    <table className="t" aria-label={t('e01p.months')}>
      <thead>
        <tr>
          <th>
            {t('e01p.period')}
            <Src s="SYS" />
          </th>
          <th className="num">
            {t('e01p.gross')}
            <Src s="CALC" />
          </th>
          <th className="num">
            {t('e01p.deductions')}
            <Src s="CALC" />
          </th>
          <th className="num">
            {t('e01p.net')}
            <Src s="CALC" />
          </th>
        </tr>
      </thead>
      <tbody>
        {months.map((m) => (
          <tr key={m.yymm}>
            <td>
              <button type="button" className="ia" onClick={() => onOpen(m.yymm)}>
                {periodLabel(yymmToMonth(m.yymm), locale)}
              </button>
            </td>
            <td className="num">{amount(m.gross)}</td>
            <td className="num">{amount(m.deductions)}</td>
            <td className="num">{amount(m.net)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function PreviewSubmissions({ rows }: { rows: Preview['submissions'] }) {
  const { t } = useI18n();
  if (rows.length === 0) return <Empty text={t('e02.none')} />;
  return (
    <ul className="fl-list" aria-label={t('e02.submissions')}>
      {rows.map((s) => (
        <li key={`${s.submittedAt}-${s.type}`}>
          {t(`e02.type.${s.type}`)} - {t(`e02.status.${s.status}`)} ({dateTime(s.submittedAt)})
        </li>
      ))}
    </ul>
  );
}
