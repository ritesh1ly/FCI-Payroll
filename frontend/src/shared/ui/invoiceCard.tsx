'use client';

import type { ReactNode } from 'react';
import { useI18n } from '@/shared/i18n';
import { date } from './primitives';
import { Tbl } from './wireframe';

/** One payment entry of the card, its invoice and whether (and why not) it may be cancelled now. */
export interface InvoiceRow {
  key: string;
  entry: string;
  member: string;
  /** Text, or text with the drawn 🔐 OTP pill. */
  paymentType: ReactNode;
  amount: string;
  invoiceNo: string | null;
  /** off_cycle_payment / supplementary_payment invoice_status (DDL v4.0). */
  invoiceStatus: string;
  sentToAppsDate: string | null;
  appsCancelled: boolean;
  /** A cancelled entry: drawn as "Cancelled — new version" instead of the action. */
  cancelled: boolean;
  /** Null when the Maker may cancel it now; otherwise the reason the drawn ⊘ Cancel is off. */
  cancelWhy: string | null;
  onCancel: () => void;
}

/**
 * The "… — invoice status" card of Wireframes v6.1 (invCard(), BR-AUDIT-009, BR-AUDIT-010; OD-121: drawn as the pack
 * draws it): Entry, Member, Payment type, Amount, Invoice no., Invoice status, Invoice Sent to APPS Date, APPS
 * cancellation and ⊘ Cancel - free until the invoice is sent to APPS, after that only once APPS confirms the invoice
 * cancelled; a cancellation is a new version, never a delete (the note under the table).
 */
export function InvoiceCard({ title, rows }: { title: string; rows: InvoiceRow[] }) {
  const { t } = useI18n();
  return (
    <Tbl
      title={title}
      cols={[
        { t: t('wf.inv.entry'), s: 'SYS' },
        { t: t('wf.inv.member'), s: 'HRMS' },
        { t: t('wf.inv.paymentType'), s: 'SYS' },
        { t: t('wf.inv.amount'), s: 'CALC', num: true },
        { t: t('wf.inv.number'), s: 'SYS' },
        { t: t('wf.inv.status'), s: 'SYS' },
        { t: t('wf.inv.sentDate'), s: 'SYS' },
        { t: t('wf.inv.appsCancel'), s: 'INTF' },
        { t: t('wf.inv.action'), s: 'SYS' },
      ]}
      after={
        <div className="bd fl-pad-top-0">
          <div className="oq">
            <b>{t('wf.inv.noteLead')}</b> {t('wf.inv.noteText')}
          </div>
        </div>
      }
    >
      {rows.length === 0 && (
        <tr>
          <td colSpan={9} className="sub2">
            {t('wf.inv.none')}
          </td>
        </tr>
      )}
      {rows.map((r) => {
        const tone =
          r.invoiceStatus === 'SENT_TO_APPS'
            ? 'p-info'
            : /CANCEL/.test(r.invoiceStatus)
              ? 'p-gray'
              : 'p-warn';
        return (
          <tr key={r.key} data-invoice={r.entry}>
            <td>{r.entry}</td>
            <td>{r.member}</td>
            <td>{r.paymentType}</td>
            <td className="num">{r.amount}</td>
            <td>{r.invoiceNo ?? '—'}</td>
            <td>
              <span className={`pill ${tone}`}>{t(`inv.${r.invoiceStatus}`)}</span>
            </td>
            <td>{r.sentToAppsDate ? date(r.sentToAppsDate) : '—'}</td>
            <td>{r.appsCancelled ? t('inv.cancelledInApps') : '—'}</td>
            <td>
              {r.cancelled ? (
                <span className="pill p-gray">{t('wf.inv.cancelledVersion')}</span>
              ) : r.cancelWhy === null ? (
                <button type="button" className="ia" title={t('wf.inv.cancelTitle')} onClick={r.onCancel}>
                  {t('wf.inv.cancel')}
                </button>
              ) : (
                <button type="button" className="ia off" disabled title={r.cancelWhy} data-why={r.cancelWhy}>
                  {t('wf.inv.cancel')}
                </button>
              )}
            </td>
          </tr>
        );
      })}
    </Tbl>
  );
}
