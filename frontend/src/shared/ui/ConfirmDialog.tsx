'use client';

import { type ReactNode, useState } from 'react';
import { useDialogFocus } from './focus';
import { useI18n } from '@/shared/i18n';

/** A modal yes/no confirmation (focus trapped, Escape-safe through the shared focus helper). */
export function ConfirmDialog({
  title,
  children,
  confirmLabel,
  busy = false,
  onConfirm,
  onCancel,
}: {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLDivElement>();
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div ref={dialog} className="fl-modal" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
        <h2 id="confirm-title">{title}</h2>
        <div className="bd">{children}</div>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onCancel} data-autofocus>
            {t('common.cancel')}
          </button>
          <button type="button" className="btn pri" disabled={busy} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * SRS-EH-12 (US-GAP-115): "Ask for confirmation before every cancel". `ask(what, onYes)` opens the
 * confirmation naming what is cancelled; only "Yes, cancel it" runs `onYes`, Cancel or Escape leaves
 * everything as it was. Render `dialog` once in the screen.
 */
export function useConfirmCancel(): { ask: (what: string, onYes: () => void) => void; dialog: ReactNode } {
  const { t } = useI18n();
  const [pending, setPending] = useState<{ what: string; onYes: () => void } | null>(null);
  const dialog =
    pending === null ? null : (
      <ConfirmDialog
        title={t('confirm.cancel.title', [pending.what])}
        confirmLabel={t('confirm.cancel.yes')}
        onCancel={() => setPending(null)}
        onConfirm={() => {
          const run = pending.onYes;
          setPending(null);
          run();
        }}
      >
        <p>{t('confirm.cancel.body')}</p>
      </ConfirmDialog>
    );
  return { ask: (what, onYes) => setPending({ what, onYes }), dialog };
}
