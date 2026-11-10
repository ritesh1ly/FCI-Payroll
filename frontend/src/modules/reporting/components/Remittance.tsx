'use client';

import { useI18n } from '@/shared/i18n';
import { periodLabel, useMe } from '@/shared/session';
import { amount, dateTime, Problem, ServerPagination } from '@/shared/ui';
import { Explained, Locked, Panel, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { RemittanceOverview, RemittanceRun } from '../api/outputsApi';
import { closingYear, invoiceRange, monthOf, statusPill } from '../business/outputRules';
import { outputKeys, outputPages, useRemittanceOverview } from '../orchestration/outputsQueries';

const DASH = '—';

/**
 * O-11 Monthly & Year-End Remittance as Wireframes v6.1 draws it (OD-121; SRS-G-52, SRS-18-19, SRS-18-23, SRS-18-24,
 * BR-MIS-006, BR-MIS-007, FRS §6.10.3.3): the window of what was sent to APPS in the month, the year-end state, the
 * runs and the two rule panels. It is read-only: the report layout, the APPS contract and the year-end logic are not
 * supplied (SRS-18-24, API-23-2). An action the pack draws enabled for the role says why when pressed; one it draws
 * disabled stays disabled with its reason. No action sends anything or starts an OTP (WF-1900).
 */
export function Remittance() {
  const { t, locale } = useI18n();
  const me = useMe();
  const overview = useRemittanceOverview();
  const o = overview.data;
  const roles = me.data?.roles ?? [];
  const finance = roles.includes('FINANCE');
  const checker = roles.includes('PAYROLL_CHECKER');
  const notSupplied = t('o11.why.apps');
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Explained label={t('o11.act.report')} why={t('o11.why.report')} />
        {finance ? (
          <Explained label={t('o11.act.transmit')} why={notSupplied} kind="pri" />
        ) : (
          <Locked label={t('o11.act.transmit')} why={t('o11.why.transmit')} />
        )}
        {finance ? (
          <Explained label={t('o11.act.yearEnd')} why={notSupplied} />
        ) : (
          <Locked label={t('o11.act.yearEnd')} why={t('o11.why.yearEnd')} />
        )}
        {checker ? (
          <Explained label={t('o11.act.approve')} why={notSupplied} />
        ) : (
          <Locked label={t('o11.act.approve')} why={t('o11.why.approve')} />
        )}
      </div>
      <Problem error={overview.error} />
      <Tiles>
        <WfTile
          icon="📅"
          label={t('o11.tile.month')}
          src="SYS"
          big={o ? periodLabel(monthOf(o.yymm), locale) : DASH}
          sub={t('o11.tile.monthSub')}
        />
        <WfTile
          icon="🧾"
          label={t('o11.tile.payments')}
          src="SYS"
          big={amount(o?.payments ?? 0)}
          sub={t('o11.tile.paymentsSub', [o?.lateArrears ?? 0, o?.supplementary ?? 0])}
        />
        <WfTile
          icon="⛔"
          label={t('o11.tile.unmapped')}
          src="RULE"
          tone="bad"
          bad={(o?.unmappedPayCodes ?? 0) > 0}
          big={amount(o?.unmappedPayCodes ?? 0)}
          sub={t(o && o.unmappedPayCodes > 0 ? 'o11.tile.unmappedBlocked' : 'o11.tile.unmappedSub')}
        />
        <YearEndTile o={o} />
      </Tiles>
      <Tbl
        title={t('o11.runs')}
        acts={<Explained label={t('o11.act.export')} why={t('reason.noExport')} kind="sm" />}
        cols={[
          { t: t('o11.col.run'), s: 'SYS' },
          { t: t('o11.col.prerequisite'), s: 'RULE' },
          { t: t('o11.col.invoice'), s: 'SYS' },
          { t: t('o11.col.generated'), s: 'SYS' },
          { t: t('o11.col.transmitted'), s: 'SYS' },
          { t: t('o11.col.status'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={outputKeys.runs}
          fetchPage={outputPages.runs}
          personal={false}
          empty={<p className="fl-hint">{t('o11.none')}</p>}
        >
          {(rows) => rows.map((r) => <RunRow key={r.id} r={r} />)}
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt keep">
        <Panel
          title={t('o11.ye.title')}
          items={[
            { icon: '🔒', tone: 'bad', title: t('o11.ye.lock'), src: 'RULE', text: t('o11.ye.lockSub') },
            { icon: '⚠', tone: 'warn', title: t('o11.ye.warn'), src: 'SYS', text: t('o11.ye.warnSub') },
            { icon: '🏁', tone: 'info', title: t('o11.ye.closed'), src: 'SYS', text: t('o11.ye.closedSub') },
            {
              icon: '↪',
              tone: 'gray',
              title: t('o11.ye.carried'),
              src: 'RULE',
              text: t('o11.ye.carriedSub'),
            },
          ]}
        />
        <Panel
          title={t('o11.tx.title')}
          items={[
            {
              icon: '📄',
              tone: 'info',
              title: t('o11.tx.preview'),
              src: 'SYS',
              text: t('o11.tx.previewSub'),
            },
            {
              icon: '🔗',
              tone: 'info',
              title: t('o11.tx.routing'),
              src: 'RULE',
              text: t('o11.tx.routingSub'),
            },
            { icon: '🔐', tone: 'warn', title: t('o11.tx.otp'), src: 'SYS', text: t('o11.tx.otpSub') },
            { icon: '🏷', tone: 'gray', title: t('o11.tx.names'), src: 'RULE', text: t('o11.tx.namesSub') },
          ]}
        />
      </div>
    </div>
  );
}

/** "FY 2025-26 year-end": Run once the financial year is closed, else the run's state. */
function YearEndTile({ o }: { o: RemittanceOverview | undefined }) {
  const { t } = useI18n();
  const fy = o?.yearEndFinYear ?? null;
  const closed = o?.yearEndClosedAt ?? null;
  const big = closed ? t('o11.ye.run') : fy ? t(`o11.status.YEAR_END.${o?.yearEndStatus}`, [fy]) : DASH;
  return (
    <WfTile
      icon="🏁"
      label={fy ? t('o11.tile.yearEnd', [fy]) : t('o11.tile.yearEndNone')}
      src="SYS"
      tone="ok"
      big={big}
      sub={closed ? t('o11.tile.yearEndClosed', [dateTime(closed)]) : t('o11.tile.yearEndOpen')}
    />
  );
}

/** A row of Remittance runs: name and prerequisite follow the run's type. */
function RunRow({ r }: { r: RemittanceRun }) {
  const { t, locale } = useI18n();
  const month = r.period ? periodLabel(monthOf(r.period), locale) : DASH;
  const yearEnd = r.type === 'YEAR_END';
  const closing = r.finYear ? periodLabel(`${closingYear(r.finYear)}-03`, locale) : DASH;
  const statusKey = yearEnd ? `o11.status.YEAR_END.${r.status}` : `o11.status.${r.status}`;
  const range = invoiceRange(r);
  const transmitted = r.transmittedBy
    ? t('o11.transmitted', [r.transmittedBy, dateTime(r.transmittedAt), r.reference ?? DASH])
    : DASH;
  return (
    <tr data-reference={String(r.id)}>
      <td>
        <b>{yearEnd ? t('o11.run.YEAR_END', [r.finYear ?? DASH]) : t(`o11.run.${r.type}`, [month])}</b>
      </td>
      <td>{yearEnd ? t('o11.prereq.YEAR_END', [closing]) : t(`o11.prereq.${r.type}`, [month])}</td>
      <td>{range ?? (r.status === 'DRAFT' && !yearEnd ? t('o11.invoiceLater') : DASH)}</td>
      <td>{dateTime(r.generated)}</td>
      <td>{transmitted}</td>
      <td>
        <span className={`pill ${statusPill(r.status)}`}>{t(statusKey, [r.finYear ?? DASH])}</span>
        {r.status === 'SENT' && (
          <>
            {' '}
            <span className="pill p-pur" title={t('o11.otpTitle')}>
              {t('o11.otp')}
            </span>
          </>
        )}
      </td>
    </tr>
  );
}
