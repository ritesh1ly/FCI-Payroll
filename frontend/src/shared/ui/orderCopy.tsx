'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions, useReference } from '@/shared/reference';
import { dateTime, Problem } from './primitives';
import { useToast } from './Toast';

/** One order stored with a record (the document module's attachment view, the fields the card shows). */
export interface StoredOrder {
  id: number;
  fileName: string;
  documentTypeCode: string | null;
  documentTypeName: string | null;
  referenceNumber: string | null;
  uploadedBy: string;
  uploadedAt: string;
}

/** What the Maker enters on the card; the files are uploaded one by one with these particulars. */
export interface OrderCopyInput {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingOffice: string;
  files: File[];
}

const ACCEPT = '.pdf,.jpg,.jpeg,.png';

/**
 * Whether the particulars may be stored: a type, a past or present date, the reference where the type requires
 * one (document_type_master.requires_reference_number, read by the screen) and at least one file.
 */
export function orderCopyReady(
  o: OrderCopyInput,
  today: string,
  referenceRequired: (type: string) => boolean,
  officeRequired = false,
): boolean {
  return (
    o.documentType !== '' &&
    (!officeRequired || o.issuingOffice.trim() !== '') &&
    o.orderDate !== '' &&
    o.orderDate <= today &&
    o.files.length > 0 &&
    (!referenceRequired(o.documentType) || o.reference.trim() !== '')
  );
}

/**
 * The "📎 Order copy — attach & store" card of Wireframes v6.1 (orderCard(), FRS §6.4.3, A-41; OD-121: drawn as
 * the pack draws it): document type (the ORDER_COPY_DOCUMENT_TYPE reference list, OD-123), order / reference
 * number, order date, issuing office and the files; the stored orders below with 👁 View; "👁 View order" in the
 * header opens the latest. Fields are disabled for a role that may not attach (the pack's rbac.c). The file
 * type, size and count are checked again by the document module (SRS-G-30: the limits are settings, CF-13).
 */
export function OrderCopyCard({
  hint,
  canAttach,
  readOnly = !canAttach,
  stored,
  storedError,
  fileHref,
  today,
  attachmentRequired,
  referenceRequired,
  officeRequired = false,
  holds = false,
  onAttach,
}: {
  /** The pack's ORDER_DOCS line for the screen ("DA / HRA / rate circular or arrear order"). */
  hint: string;
  canAttach: boolean;
  /** The role cannot attach here at all: the fields are read-only as drawn (default: not canAttach). */
  readOnly?: boolean;
  stored: StoredOrder[] | undefined;
  storedError: unknown;
  fileHref: (id: number) => string;
  today: string;
  /** Whether the screen's approval route requires an order before submission (approval_workflow_action). */
  attachmentRequired: boolean;
  /** Whether a document type requires the order / reference number (from /v1/document-types). */
  referenceRequired: (type: string) => boolean;
  /** Whether the record's order needs its issuing office / authority (SRS-G-30 on I-20, I-23). */
  officeRequired?: boolean;
  /**
   * The order travels with the record it belongs to (I-23: one order per request, captured with it): the picked file is
   * held by the screen and stored on submission ("stored with the transaction on submit"), one file only.
   */
  holds?: boolean;
  onAttach: (order: OrderCopyInput) => Promise<void>;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const { options } = useReference();
  // As drawn, the type list has no empty entry: the first type of the list is preselected.
  const firstType = options('ORDER_COPY_DOCUMENT_TYPE')[0]?.value ?? '';
  const [order, setOrder] = useState<OrderCopyInput>({
    documentType: '',
    reference: '',
    orderDate: '',
    issuingOffice: '',
    files: [],
  });
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const rows = Array.isArray(stored) ? stored : [];
  const latest = rows.at(-1);
  const documentType = order.documentType || firstType;
  const [notice, setNotice] = useState<string | null>(null);

  /** As drawn, picking the files stores them with the particulars entered above (no separate button). */
  async function pick(input: HTMLInputElement) {
    const files = Array.from(input.files ?? []);
    const next = { ...order, documentType, files };
    setError(null);
    setNotice(null);
    if (files.length === 0) return;
    if (!canAttach) {
      setNotice(t('wf.ord.viewOnly'));
      input.value = '';
      return;
    }
    if (holds && files.length > 1) {
      setNotice(t('wf.ord.oneFile'));
      input.value = '';
      return;
    }
    if (!orderCopyReady(next, today, referenceRequired, officeRequired)) {
      setNotice(t('wf.ord.particularsFirst'));
      input.value = '';
      return;
    }
    setBusy(true);
    setOrder(next);
    try {
      await onAttach(next);
      setOrder({ documentType, reference: '', orderDate: '', issuingOffice: '', files: [] });
      setNotice(
        holds ? t('wf.ord.held', [files.map((f) => f.name).join(', ')]) : t('wf.ord.stored', [files.length]),
      );
    } catch (cause) {
      setError(cause);
    } finally {
      setBusy(false);
      input.value = '';
    }
  }

  return (
    <div className="card fl-mt" data-order-card="">
      <div className="hd">
        <h3>{t('wf.ord.title')}</h3>
        {/* i18n-ignore: requirement references, not labels */}
        <span className="frs">§6.4.3 · A-41</span>
        <span className="sp" />
        {latest ? (
          <a className="btn sm" href={fileHref(latest.id)}>
            {t('wf.ord.viewOrder')}
          </a>
        ) : (
          // Drawn enabled (the pack opens the viewer); with no stored order it says so.
          <button
            type="button"
            className="btn sm"
            data-guard={t('ord.none')}
            onClick={() => toast(t('ord.none'))}
          >
            {t('wf.ord.viewOrder')}
          </button>
        )}
      </div>
      <div className="bd">
        <Problem error={error ?? storedError} />
        <div className="rf-grid">
          <div className="rf-field">
            <label>
              {t('wf.ord.type')} <span className="req">*</span>
            </label>
            <select
              disabled={readOnly}
              value={documentType}
              onChange={(e) => setOrder({ ...order, documentType: e.target.value })}
            >
              <ReferenceOptions list="ORDER_COPY_DOCUMENT_TYPE" />
            </select>
            <span className="hint">{hint}</span>
          </div>
          <div className="rf-field">
            <label>
              {t('wf.ord.reference')} <span className="req">*</span>
            </label>
            <input
              disabled={readOnly}
              maxLength={60}
              placeholder={t('wf.ord.referencePlaceholder')}
              value={order.reference}
              onChange={(e) => setOrder({ ...order, reference: e.target.value })}
            />
            <span className="hint">{t('wf.ord.referenceHint')}</span>
          </div>
          <div className="rf-field">
            <label>
              {t('wf.ord.date')} <span className="req">*</span>
            </label>
            <input
              type="date"
              disabled={readOnly}
              max={today}
              value={order.orderDate}
              onChange={(e) => setOrder({ ...order, orderDate: e.target.value })}
            />
          </div>
          <div className="rf-field">
            <label>{t('wf.ord.office')}</label>
            <input
              disabled={readOnly}
              maxLength={200}
              placeholder={t('wf.ord.officePlaceholder')}
              value={order.issuingOffice}
              onChange={(e) => setOrder({ ...order, issuingOffice: e.target.value })}
            />
          </div>
          <div className="rf-field span2">
            <label>
              {t('wf.ord.file')} <span className="req">*</span>
            </label>
            <input
              type="file"
              multiple
              accept={ACCEPT}
              disabled={readOnly || busy}
              onChange={(e) => void pick(e.target)}
            />
            <div className="sub2 fl-mt-6" role="status">
              {notice ??
                (!canAttach
                  ? t('wf.ord.viewOnly')
                  : order.files.length > 0
                    ? order.files.map((f) => `📄 ${f.name}`).join(' · ')
                    : rows.length > 0
                      ? t('ord.existing', [rows.length])
                      : t(attachmentRequired ? 'wf.ord.noneBlocked' : 'wf.ord.noneYet'))}
            </div>
          </div>
        </div>
        <table className="t fl-mt-10">
          <thead>
            <tr>
              <th>{t('ord.stored')}</th>
              <th>{t('wf.ord.col.type')}</th>
              <th>{t('wf.ord.col.reference')}</th>
              <th>{t('ord.uploadedBy')}</th>
              <th>{t('ord.on')}</th>
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
                  <a className="btn sm" href={fileHref(o.id)}>
                    {t('wf.view')}
                  </a>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
