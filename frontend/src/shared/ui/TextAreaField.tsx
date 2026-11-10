'use client';

import { type ChangeEvent, type ReactNode, type TextareaHTMLAttributes, useId, useState } from 'react';
import { useI18n } from '@/shared/i18n';

/**
 * R-ASCII: the database character set is US7ASCII, so free text is printable ASCII (space to
 * tilde) with tab and line breaks, exactly what the server's `@PrintableAscii` accepts.
 */
export const PLAIN_TEXT = /^[\x20-\x7E\t\r\n]*$/;

/** True when {@code text} is plain text the server keeps as typed (R-ASCII). */
export function isPlainText(text: string): boolean {
  return PLAIN_TEXT.test(text);
}

export type TextAreaFieldProps = Omit<
  TextareaHTMLAttributes<HTMLTextAreaElement>,
  'maxLength' | 'value' | 'defaultValue'
> & {
  /** The column's length (target DDL v4.0) or the API's lower cap: required, never open-ended. */
  maxLength: number;
  /** Controlled text; leave out for a form read through FormData (then defaultValue applies). */
  value?: string;
  defaultValue?: string;
  /**
   * R-ASCII applies (default). False only for text stored in the national character set, the
   * M-26 label text (label_master.label_text NVARCHAR2): Hindi is allowed there, so no hint.
   */
  asciiOnly?: boolean;
};

/**
 * The long free-text control of every screen (reason, remark, description, address, subject):
 * a textarea of three rows capped at the column length, with the characters used and the plain
 * text hint below it (R-ASCII; the same rules as the shared ReasonDialog). The hint and the count
 * are drawn by the stylesheet from data attributes (fcilekha.css .fl-ta-meta), so they never
 * become part of a wrapping label's text: the field keeps its own name ("Reason"), and they reach
 * a screen reader as the field's description (aria-describedby). Text that is not plain ASCII
 * marks the field invalid; the server refuses it in any case.
 */
export function TextAreaField({
  maxLength,
  value,
  defaultValue,
  rows = 3,
  onChange,
  'aria-describedby': describedBy,
  'aria-invalid': invalid,
  asciiOnly = true,
  ...rest
}: TextAreaFieldProps) {
  const { t } = useI18n();
  const metaId = useId();
  const [own, setOwn] = useState(defaultValue ?? '');
  const text = value ?? own;
  const plain = !asciiOnly || isPlainText(text);
  function change(event: ChangeEvent<HTMLTextAreaElement>) {
    if (value === undefined) setOwn(event.target.value);
    onChange?.(event);
  }
  return (
    <>
      <textarea
        {...rest}
        rows={rows}
        maxLength={maxLength}
        {...(value === undefined ? { defaultValue } : { value })}
        onChange={change}
        aria-invalid={plain ? invalid : true}
        aria-describedby={describedBy ? `${describedBy} ${metaId}` : metaId}
      />
      <span
        id={metaId}
        className={`fl-ta-meta${plain ? '' : ' fl-bad'}`}
        aria-hidden="true"
        data-hint={asciiOnly ? t(plain ? 'common.plainTextHint' : 'common.plainTextOnly') : ''}
        data-count={t('common.charCount', [text.length, maxLength])}
      />
    </>
  );
}

/**
 * A reason box in the label-wraps-field layout (`fl-field`): the label (Reason unless given) and
 * the shared TextAreaField. Every maker submission carries one (BR-AUDIT-003).
 */
export function ReasonField({
  label,
  className = 'fl-field',
  ...field
}: TextAreaFieldProps & { label?: ReactNode; className?: string }) {
  const { t } = useI18n();
  return (
    <label className={className}>
      {label ?? t('common.reason')}
      <TextAreaField {...field} />
    </label>
  );
}
