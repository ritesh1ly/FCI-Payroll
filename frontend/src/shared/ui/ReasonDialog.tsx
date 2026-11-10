'use client';

import { type FormEvent, type ReactNode, useState } from 'react';
import { useDialogFocus } from './focus';
import { useI18n } from '@/shared/i18n';
import { TextAreaField, isPlainText } from './TextAreaField';
import { Problem } from './primitives';

/** Every maker submission and every reject / return carries a reason (audit trail BR-AUDIT-003). */
export function ReasonDialog({
  title,
  onConfirm,
  onCancel,
  required = true,
  confirmLabel,
  note,
  maxLength = 500,
  error,
  busy = false,
}: {
  title: string;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
  required?: boolean;
  confirmLabel?: string;
  /** What the action does, when its consequence is not obvious from the title (e.g. final). */
  note?: ReactNode;
  /** The reason column's length (default 500, the approval reason; 1000 where the column is). */
  maxLength?: number;
  /**
   * The server's refusal of the action (UI audit S46): shown inside the dialog, which stays open so
   * the typed reason is not lost.
   */
  error?: unknown;
  /** The action is being sent: confirm is disabled (no double submission). */
  busy?: boolean;
}) {
  const { t } = useI18n();
  const [reason, setReason] = useState('');
  const dialog = useDialogFocus<HTMLFormElement>();
  const plain = isPlainText(reason);
  const ready = plain && !(required && reason.trim() === '');
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ready) return;
    onConfirm(reason.trim());
  }
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <form
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="reason-title"
        onSubmit={submit}
      >
        <h2 id="reason-title">{title}</h2>
        {note && <p className="sub2">{note}</p>}
        <label className="fl-field">
          {t('common.reason')}
          <TextAreaField
            name="reason"
            maxLength={maxLength}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            data-autofocus
          />
        </label>
        {!plain && (
          <p className="fl-error" role="alert">
            {t('common.plainTextOnly')}
          </p>
        )}
        {error !== undefined && error !== null && <Problem error={error} />}
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="btn pri" disabled={!ready || busy}>
            {confirmLabel ?? t('common.submit')}
          </button>
        </div>
      </form>
    </div>
  );
}
