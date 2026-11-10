'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, periodLabel, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { amount, ExportButton, ServerPagination } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { Act, Explained, Panel, RowActs, Tbl, Tiles, ViewDialog, WfTile } from '@/shared/ui/wireframe';
import type { NpsRow } from '../api/npsApi';
import { isMismatch, memberLine, periodOf, statusPill, yymmOf } from '../business/npsRules';
import { npsExportUrl, npsKeys, npsPage, useNpsSummary } from '../orchestration/npsQueries';
import { NpsDeductions } from './NpsDeductions';

function crore(value: number): string {
  return (value / 10_000_000).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/**
 * I-14 NPS Deduction (from HRMS) as Wireframes v6.1 draws it (a frozen requirement per OD-121; SRS-9-1,
 * SRS-9-1a, SRS-9-2, SRS-9-6, BR-PEN-001 to 004, BR-PEN-008). View only: the flag, the PRAN and the amounts are
 * what HRMS sent, shown as received and never recalculated (R-SRC), so ✎ Edit is off with its reason. HRMS has
 * no NPS API yet (PENDING NPS-API): the records arrive through nps.api.NpsReceipts. The screen opens on the
 * session's month, or the unit's latest month with records. The filters, the counts by status and the NPS
 * Deduction Report's months stay in the closed section (NpsDeductions), opened by the drawn report button.
 */
export function NpsDeductionScreen() {
  const { t, locale } = useI18n();
  const me = useMe();
  const asked = me.data?.period?.replace('-', '') || yymmOf(businessToday());
  const summary = useNpsSummary(asked).data;
  const yymm = summary?.yymm ?? asked;
  const month = periodLabel(periodOf(yymm), locale);
  const mayExport = can(me.data, 'I-14', 'EXPORT');
  const [viewing, setViewing] = useState<NpsRow | null>(null);
  const [open, setOpen] = useState(false);
  const exportOf = (label: string, className?: string) =>
    mayExport ? (
      <ExportButton
        href={npsExportUrl({ yymm })}
        what={t('screen.I-14')}
        label={label}
        className={className}
      />
    ) : (
      <Explained
        label={label}
        why={t('reason.noPermission')}
        kind={className === 'btn sm' ? 'sm' : undefined}
      />
    );
  const money = (n: number | null) => amount(n);

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {exportOf(t('i14w.exportTop'))}
        {mayExport ? (
          <Act label={t('i14w.report')} onClick={() => setOpen(true)} />
        ) : (
          <Explained label={t('i14w.report')} why={t('reason.noPermission')} />
        )}
      </div>
      <Tiles>
        <WfTile
          icon="🧾"
          label={t('i14w.tile.members', [month.split(' ')[0]])}
          src="HRMS"
          big={amount(summary?.members ?? 0)}
          sub={t('i14w.tile.membersSub')}
        />
        <WfTile
          icon="➖"
          label={t('i14w.tile.employee')}
          src="HRMS"
          big={t('i14w.crore', [crore(summary?.employeeAmount ?? 0)])}
          sub={t('i14w.tile.employeeSub')}
        />
        <WfTile
          icon="⇄"
          label={t('i14w.tile.employer')}
          src="HRMS"
          big={t('i14w.crore', [crore(summary?.employerAmount ?? 0)])}
          sub={t('i14w.tile.employerSub')}
        />
        <WfTile
          icon="⛔"
          label={t('i14w.tile.mismatch')}
          src="SYS"
          tone="bad"
          bad={(summary?.mismatches ?? 0) > 0}
          big={amount(summary?.mismatches ?? 0)}
          sub={t('i14w.tile.mismatchSub')}
        />
      </Tiles>
      <Tbl
        title={t('i14w.table', [month])}
        acts={exportOf(t('i14w.export'), 'btn sm')}
        cols={[
          { t: t('i14w.col.member'), s: 'HRMS' },
          { t: t('i14w.col.flag'), s: 'HRMS' },
          { t: t('i14w.col.pran'), s: 'HRMS' },
          { t: t('i14w.col.employee'), s: 'HRMS', num: true },
          { t: t('i14w.col.employer'), s: 'HRMS', num: true },
          { t: t('i14w.col.deducted'), s: 'CALC', num: true },
          { t: t('i14w.col.status'), s: 'SYS' },
          { t: t('i14w.col.actions'), s: 'SYS' },
        ]}
        after={
          <div className="bd fl-pad-top-0">
            <div className="oq">{t('i14w.note')}</div>
          </div>
        }
      >
        <ServerPagination
          queryKey={[...npsKeys.list({ yymm }), 'card']}
          fetchPage={npsPage({ yymm })}
          empty={t('i14.none')}
        >
          {(rows) =>
            rows.map((r) => {
              const line = memberLine(r.payPattern, r.providentFund);
              return (
                <tr key={r.id} data-nps={r.eis}>
                  <td>
                    {r.eis} {r.employee}
                    {line && <span className="sub2"> — {line}</span>}
                  </td>
                  <td>{t(r.npsFlag ? 'i14w.flag.yes' : 'i14w.flag.no')}</td>
                  <td>{r.pran ?? '—'}</td>
                  <td className="num">{money(r.employeeAmount)}</td>
                  <td className="num">{money(r.employerAmount)}</td>
                  <td className="num">{money(r.deducted)}</td>
                  <td>
                    <span className={`pill ${statusPill(r.status)}`}>{t(`i14w.status.${r.status}`)}</span>
                  </td>
                  <td>
                    <RowActs
                      viewName={t('i14w.viewOf', [r.eis])}
                      editName={t('i14w.editOf', [r.eis])}
                      onView={() => setViewing(r)}
                      editWhy={t('i14w.editWhy')}
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
          screen={t('screen.I-14')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('i14w.col.member'), v: `${viewing.eis} ${viewing.employee}`, s: 'HRMS' },
            { k: t('i14w.col.office'), v: viewing.office ?? '—', s: 'HRMS' },
            { k: t('i14w.col.flag'), v: t(viewing.npsFlag ? 'i14w.flag.yes' : 'i14w.flag.no'), s: 'HRMS' },
            { k: t('i14w.col.pran'), v: viewing.pran ?? '—', s: 'HRMS' },
            { k: t('i14w.col.employee'), v: money(viewing.employeeAmount), s: 'HRMS' },
            { k: t('i14w.col.employer'), v: money(viewing.employerAmount), s: 'HRMS' },
            { k: t('i14w.col.deducted'), v: money(viewing.deducted), s: 'CALC' },
            { k: t('i14.effectiveFrom'), v: viewing.effectiveFrom ?? '—', s: 'HRMS' },
            {
              k: t('i14w.col.status'),
              v: isMismatch(viewing.status)
                ? `${t(`i14w.status.${viewing.status}`)} — ${t('i14.fixInHrms')}`
                : t(`i14w.status.${viewing.status}`),
              s: 'SYS',
            },
          ]}
        />
      )}
      <Panel
        title={t('i14w.panel')}
        items={[
          { icon: '➖', tone: 'info', title: t('i14w.p.deduct'), src: 'HRMS', text: t('i14w.p.deductText') },
          {
            icon: '⇄',
            tone: 'info',
            title: t('i14w.p.employer'),
            src: 'HRMS',
            text: t('i14w.p.employerText'),
          },
          { icon: '🔖', tone: 'gray', title: t('i14w.p.pran'), src: 'HRMS', text: t('i14w.p.pranText') },
          {
            icon: '🚪',
            tone: 'warn',
            title: t('i14w.p.separation'),
            src: 'HRMS',
            text: t('i14w.p.separationText'),
          },
          { icon: '📮', tone: 'ok', title: t('i14w.p.remit'), src: 'SYS', text: t('i14w.p.remitText') },
          { icon: '🚫', tone: 'bad', title: t('i14w.p.outside'), src: 'RULE', text: t('i14w.p.outsideText') },
        ]}
      />
      <RegisterSection label={t('i14w.register')} id="i14-register" open={open} onToggle={setOpen}>
        {open && <NpsDeductions />}
      </RegisterSection>
    </div>
  );
}
