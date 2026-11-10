'use client';

import type { ReactNode } from 'react';
import { useI18n } from '@/shared/i18n';
import { dateTime, Problem, Src, type Source } from '@/shared/ui';
import type { AttachmentView } from '../api/generalApi';

/**
 * Building blocks of the Module 01 screens, after the layout of Wireframe v6.1 (reference only; the
 * KB, SRS and FRS decide content): field-source badges, explanatory control lists, the banner and
 * note boxes, and the stored-orders table of the "Order copy - attach & store" card.
 */

// The field-source badge moved to shared/ui (used by every module's screens); re-exported for Module 01.
export { Src, Th, type Source } from '@/shared/ui';

export interface ControlItem {
  icon: string;
  tone: 'info' | 'warn' | 'bad' | 'ok' | 'gray' | 'pur';
  label: string;
  s?: Source;
  sub?: string;
  right?: ReactNode;
}

/** A card of explanatory rows (icon, label with source badge, sub-text, right-hand pill). */
export function Controls({ title, refs, items }: { title: string; refs?: string; items: ControlItem[] }) {
  return (
    <div className="card">
      <div className="hd">
        <h3>{title}</h3>
        {refs && <span className="frs">{refs}</span>}
        <span className="sp" />
      </div>
      <div className="bd">
        {items.map((item) => (
          <div className="lst" key={item.label}>
            <span className={`ic2 fl-ic-${item.tone}`} aria-hidden="true">
              {item.icon}
            </span>
            <div>
              <b>
                {item.label}
                {item.s && <Src s={item.s} />}
              </b>
              {item.sub && <div className="m">{item.sub}</div>}
            </div>
            {item.right && <div className="rt">{item.right}</div>}
          </div>
        ))}
      </div>
    </div>
  );
}

/** The highlighted rule above a screen's register (wireframe "anno"). */
export function Banner({ children }: { children: ReactNode }) {
  return (
    <div className="keep">
      <div className="anno on">{children}</div>
    </div>
  );
}

/** A note box inside a card (wireframe "oq"). */
export function Note({ children }: { children: ReactNode }) {
  return (
    <div className="keep">
      <div className="oq">{children}</div>
    </div>
  );
}

/** The orders kept with a record, each downloadable as an attachment (read-only). */
export function StoredOrders({
  orders,
  error,
  href,
}: {
  orders: AttachmentView[] | undefined;
  error: unknown;
  href: (attachmentId: number) => string;
}) {
  const { t } = useI18n();
  const rows = Array.isArray(orders) ? orders : [];
  return (
    <>
      <Problem error={error} />
      <table className="t fl-mt">
        <thead>
          <tr>
            <th>
              {t('ord.stored')}
              <Src s="ENT" />
            </th>
            <th>
              {t('ord.type')}
              <Src s="ENT" />
            </th>
            <th>
              {t('ord.reference')}
              <Src s="ENT" />
            </th>
            <th>
              {t('ord.uploadedBy')}
              <Src s="SYS" />
            </th>
            <th>
              {t('ord.on')}
              <Src s="SYS" />
            </th>
            <th />
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr>
              <td colSpan={6} className="sub2">
                {t('ord.none')}
              </td>
            </tr>
          )}
          {rows.map((o) => (
            <tr key={o.id}>
              <td>{o.fileName}</td>
              <td>{o.documentTypeName ?? o.documentTypeCode ?? '—'}</td>
              <td>{o.referenceNumber ?? '—'}</td>
              <td>{o.uploadedBy}</td>
              <td>{dateTime(o.uploadedAt)}</td>
              <td>
                <a className="btn sm" href={href(o.id)}>
                  {t('ord.view')}
                </a>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

/** The invoice status of a payment (BR-AUDIT-010) as a coloured pill. */
export function InvoicePill({ status }: { status: string }) {
  const { t } = useI18n();
  const tone =
    status === 'SENT_TO_APPS'
      ? 'p-info'
      : status === 'NOT_RAISED' || status === 'RAISED'
        ? 'p-warn'
        : status === 'CANCELLED_IN_APPS'
          ? 'p-gray'
          : 'p-bad';
  return <span className={`pill ${tone}`}>{t(`inv.${status}`)}</span>;
}
