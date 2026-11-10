'use client';

import { useI18n } from '@/shared/i18n';
import { businessToday } from '@/shared/time';
import type { DocumentType } from '../api/generalApi';
import { isAsciiText } from '../business/generalRules';

/** The order particulars of SRS-G-30 / SRS-DM-13 as a form holds them (the file is separate). */
export interface OrderDraft {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File | null;
}

export const EMPTY_ORDER: OrderDraft = {
  documentType: '',
  reference: '',
  orderDate: '',
  issuingAuthority: '',
  file: null,
};

/** Accepted order files (SRS-G-30: PDF, JPG, PNG; the server checks the bytes again). */
const ACCEPT = '.pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png';

/**
 * Whether the particulars are complete: a type, an order date not in the future, the issuing
 * authority, the reference where the type requires it, and a file.
 */
export function orderComplete(order: OrderDraft, types: DocumentType[], today: string): boolean {
  const type = types.find((t) => t.code === order.documentType);
  return (
    type !== undefined &&
    order.file !== null &&
    order.orderDate !== '' &&
    order.orderDate <= today &&
    isAsciiText(order.issuingAuthority, 200) &&
    (!type.referenceRequired || isAsciiText(order.reference, 60)) &&
    (order.reference === '' || isAsciiText(order.reference, 60))
  );
}

/** Document type, reference, order date, issuing authority and the file (SRS-G-30). */
export function OrderFields({
  idPrefix,
  value,
  types,
  today,
  onChange,
}: {
  idPrefix: string;
  value: OrderDraft;
  types: DocumentType[];
  today: string;
  onChange: (next: OrderDraft) => void;
}) {
  const { t } = useI18n();
  const type = types.find((x) => x.code === value.documentType);
  return (
    <>
      <div className="rf-field">
        <label htmlFor={`${idPrefix}-doctype`}>{t('ord.type')}</label>
        <select
          id={`${idPrefix}-doctype`}
          value={value.documentType}
          onChange={(e) => onChange({ ...value, documentType: e.target.value })}
        >
          <option value="">—</option>
          {types.map((x) => (
            <option key={x.code} value={x.code}>
              {x.name}
            </option>
          ))}
        </select>
      </div>
      <div className="rf-field">
        <label htmlFor={`${idPrefix}-docref`}>
          {t('ord.reference')}
          {type?.referenceRequired ? ' *' : ''}
        </label>
        <input
          id={`${idPrefix}-docref`}
          maxLength={60}
          value={value.reference}
          onChange={(e) => onChange({ ...value, reference: e.target.value })}
        />
      </div>
      <div className="rf-field">
        <label htmlFor={`${idPrefix}-docdate`}>{t('ord.date')}</label>
        <input
          id={`${idPrefix}-docdate`}
          type="date"
          max={today}
          value={value.orderDate}
          onChange={(e) => onChange({ ...value, orderDate: e.target.value })}
        />
      </div>
      <div className="rf-field">
        <label htmlFor={`${idPrefix}-docauth`}>{t('ord.authority')}</label>
        <input
          id={`${idPrefix}-docauth`}
          maxLength={200}
          value={value.issuingAuthority}
          onChange={(e) => onChange({ ...value, issuingAuthority: e.target.value })}
        />
      </div>
      <div className="rf-field">
        <label htmlFor={`${idPrefix}-docfile`}>{t('ord.file')}</label>
        <input
          id={`${idPrefix}-docfile`}
          type="file"
          accept={ACCEPT}
          onChange={(e) => onChange({ ...value, file: e.target.files?.[0] ?? null })}
        />
        <span className="hint">{t('ord.fileHint')}</span>
      </div>
    </>
  );
}

/** Today in India as yyyy-mm-dd (the upper bound of an order date). */
export function todayIso(now = new Date()): string {
  return businessToday(now);
}
