'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { amount, Card, Problem, ServerPagination, Src, usePdfDownload } from '@/shared/ui';
import type { Payslip, PayslipLine } from '../api/payslipApi';
import { bulkFileName, bulkWhy, payslipDownloadPath } from '../business/payslipBulkRules';
import { hasLines, periodLabel } from '../business/payslipRules';
import {
  payslipKeys,
  payslipPage,
  usePayslip,
  usePayslipBulkSettings,
} from '../orchestration/payslipQueries';

/**
 * E-01 My payslips (plan 16 phase 16e-3; SRS-1-10, SRS-17-1, SRS-17-7): the member's own locked
 * months (never a provisional payslip) and one month's payslip in the staff layout, earnings and
 * deductions side by side, PAN and bank account masked. On the drawn E-01, {@code onOpen} takes the
 * chosen month to the drawn Pay Slip document in place of the panel here.
 *
 * OD-149 (owner 2026-11-09, an addition to the drawn E-01, PENDING E01-BULK): the member ticks months and downloads
 * them at once: one month is its PDF, several one ZIP of single-month PDFs, at most the configured number of months
 * (read from the server; while it is not set only one month at a time). Only locked months are listed, so an open
 * month cannot be chosen. The button says why it cannot act; a refusal (and a 429 / 503 with its wait) is shown.
 */
export function MyPayslips({ onOpen }: { onOpen?: (yymm: string) => void } = {}) {
  const { t, locale } = useI18n();
  const me = useMe();
  const [open, setOpen] = useState<string | null>(null);
  const [chosen, setChosen] = useState<ReadonlySet<string>>(new Set());
  const self = can(me.data, 'E-01', 'SELF');
  const settings = usePayslipBulkSettings(self);
  const pdf = usePdfDownload();
  if (!can(me.data, 'E-01', 'VIEW')) return null;
  const months = [...chosen];
  const why = bulkWhy(months.length, settings.data?.maxPeriods);
  const toggle = (yymm: string) =>
    setChosen((prev) => {
      const next = new Set(prev);
      if (next.has(yymm)) next.delete(yymm);
      else next.add(yymm);
      return next;
    });
  return (
    <Card title={t('e01p.title')}>
      <p className="fl-hint">{t('e01p.hint')}</p>
      <ServerPagination
        queryKey={payslipKeys.months}
        fetchPage={payslipPage}
        personal
        empty={<p className="fl-hint">{t('e01p.none')}</p>}
      >
        {(rows) => (
          <table className="t" aria-label={t('e01p.months')}>
            <thead>
              <tr>
                {self && <th aria-label={t('e01p.bulk.selectCol')} />}
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
              {rows.map((m) => (
                <tr key={m.yymm}>
                  {self && (
                    <td>
                      <input
                        type="checkbox"
                        aria-label={t('e01p.bulk.select', [periodLabel(m.yymm, locale)])}
                        checked={chosen.has(m.yymm)}
                        onChange={() => toggle(m.yymm)}
                      />
                    </td>
                  )}
                  <td>
                    <button
                      type="button"
                      className="ia"
                      onClick={() => (onOpen ? onOpen(m.yymm) : setOpen(m.yymm))}
                    >
                      {periodLabel(m.yymm, locale)}
                    </button>
                  </td>
                  <td className="num">{amount(m.gross)}</td>
                  <td className="num">{amount(m.deductions)}</td>
                  <td className="num">
                    <b>{amount(m.net)}</b>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </ServerPagination>
      {self && (
        <div className="fl-actions" data-testid="e01-bulk">
          <span className="sub2" role="status">
            {t('e01p.bulk.count', [months.length])}
          </span>
          <button
            type="button"
            className="btn pri"
            disabled={why !== null || pdf.busy || pdf.retryIn > 0}
            title={why ? t(why.key, why.args) : undefined}
            data-why={why ? t(why.key, why.args) : undefined}
            aria-busy={pdf.busy || undefined}
            onClick={() => void pdf.download(payslipDownloadPath(months), bulkFileName(months))}
          >
            {t('e01p.bulk.download')}
          </button>
          {pdf.retryIn > 0 && (
            <span className="sub2" role="status">
              {t('download.retryIn', [pdf.retryIn])}
            </span>
          )}
        </div>
      )}
      <Problem error={settings.error ?? pdf.error} />
      {open && <PayslipPanel key={open} yymm={open} onClose={() => setOpen(null)} />}
    </Card>
  );
}

function PayslipPanel({ yymm, onClose }: { yymm: string; onClose: () => void }) {
  const { t, locale } = useI18n();
  const slip = usePayslip(yymm);
  const p = slip.data;
  return (
    <section className="fl-form" aria-label={t('e01p.slip', [periodLabel(yymm, locale)])}>
      <h4>{t('e01p.slip', [periodLabel(yymm, locale)])}</h4>
      <Problem error={slip.error} />
      {p && <PayslipBody p={p} />}
      <div className="fl-actions">
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
    </section>
  );
}

function PayslipBody({ p }: { p: Payslip }) {
  const { t } = useI18n();
  return (
    <>
      <dl className="fl-vocabulary">
        <dt>{t('e01p.employee')}</dt>
        <dd>
          {p.eis} {p.name}
        </dd>
        <dt>{t('e01p.designation')}</dt>
        <dd>{p.designation ?? '—'}</dd>
        <dt>{t('e01p.office')}</dt>
        <dd>{p.office ?? '—'}</dd>
        <dt>{t('e01p.pan')}</dt>
        <dd>{p.panMasked ?? '—'}</dd>
        <dt>{t('e01p.bank')}</dt>
        <dd>{p.bankAccountMasked ?? '—'}</dd>
        {p.pran && (
          <>
            <dt>{t('e01p.pran')}</dt>
            <dd>{p.pran}</dd>
          </>
        )}
      </dl>
      <div className="wrap">
        <Side title={t('e01p.earnings')} lines={p.earnings} total={p.gross} totalLabel={t('e01p.gross')} />
        <Side
          title={t('e01p.deductions')}
          lines={p.deductions}
          total={p.totalDeductions}
          totalLabel={t('e01p.deductions')}
        />
      </div>
      <p>
        {t('e01p.net')}: <b>{amount(p.net)}</b>
      </p>
    </>
  );
}

function Side({
  title,
  lines,
  total,
  totalLabel,
}: {
  title: string;
  lines: PayslipLine[];
  total: number;
  totalLabel: string;
}) {
  const { t } = useI18n();
  return (
    <table className="t" aria-label={title}>
      <thead>
        <tr>
          <th>
            {title}
            <Src s="CALC" />
          </th>
          <th className="num">
            {t('e01p.amount')}
            <Src s="CALC" />
          </th>
        </tr>
      </thead>
      <tbody>
        {hasLines(lines) ? (
          lines.map((l) => (
            <tr key={l.payCode}>
              <td>{l.name}</td>
              <td className="num">{amount(l.amount)}</td>
            </tr>
          ))
        ) : (
          <tr>
            <td colSpan={2}>—</td>
          </tr>
        )}
        <tr>
          <td>
            <b>{totalLabel}</b>
          </td>
          <td className="num">
            <b>{amount(total)}</b>
          </td>
        </tr>
      </tbody>
    </table>
  );
}
