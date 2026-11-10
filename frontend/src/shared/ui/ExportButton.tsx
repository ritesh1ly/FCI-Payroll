'use client';

import { type MouseEvent, useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { useDialogFocus } from './focus';
import { Problem } from './primitives';
import { downloadFile } from './download';

/** The formats every list export offers (SRS-G-13, OD-144); the server writes each from one query. */
export const EXPORT_FORMATS = ['CSV', 'XLSX', 'PDF'] as const;
export type ExportFormat = (typeof EXPORT_FORMATS)[number];

/** The download URL of {@code href} in {@code format} (the API's `format` parameter). */
export function exportHref(href: string, format: ExportFormat): string {
  return `${href}${href.includes('?') ? '&' : '?'}format=${format}`;
}

/**
 * The one export control of every list (SRS-G-13): "Export" asks the user for the format, CSV or
 * Excel or PDF, then downloads it. The server refuses a list larger than one download may carry before
 * any byte is sent, so the file is fetched with the session cookie first: a refusal (too many
 * rows, no permission, outside the scope) is shown in the dialog, never saved as a file of error
 * text (UI audit S13); a file is handed to the browser as a download. The link keeps its address
 * (a middle click or a script still sees where the file comes from).
 */
export function ExportButton({
  href,
  what,
  label,
  className = 'btn',
}: {
  /** The export endpoint with its filter (no format). */
  href: string;
  /** What is exported, for the dialog title (e.g. the screen's name). */
  what: string;
  /** The button's text when a screen has more than one export ("Export" otherwise). */
  label?: string;
  className?: string;
}) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [format, setFormat] = useState<ExportFormat>('XLSX');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const dialog = useDialogFocus<HTMLDivElement>(open);

  function close() {
    setOpen(false);
    setError(null);
  }

  async function download(e: MouseEvent<HTMLAnchorElement>) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await downloadFile(exportHref(href, format), `export.${format.toLowerCase()}`);
      close();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : new ApiError(0, {}, undefined));
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <button type="button" className={className} aria-haspopup="dialog" onClick={() => setOpen(true)}>
        {label ?? t('action.export')}
      </button>
      {open && (
        <div
          className="fl-modal-backdrop"
          role="presentation"
          onKeyDown={(e) => {
            if (e.key === 'Escape') close();
          }}
        >
          <div
            ref={dialog}
            className="fl-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="export-title"
          >
            <h2 id="export-title">{t('export.title', [what])}</h2>
            <fieldset className="fl-field">
              <legend>{t('export.choose')}</legend>
              {EXPORT_FORMATS.map((f) => (
                <label key={f} className="fl-choice">
                  <input
                    type="radio"
                    name="export-format"
                    value={f}
                    checked={format === f}
                    onChange={() => setFormat(f)}
                    data-autofocus={format === f ? true : undefined}
                  />{' '}
                  <b>{t(`export.${f.toLowerCase()}`)}</b>
                  <span className="sub2"> {t(`export.${f.toLowerCase()}Hint`)}</span>
                </label>
              ))}
            </fieldset>
            <Problem error={error} />
            <div className="fl-actions">
              <button type="button" className="btn" onClick={close}>
                {t('common.cancel')}
              </button>
              <a
                className="btn pri"
                href={exportHref(href, format)}
                download
                aria-disabled={busy || undefined}
                onClick={(e) => {
                  if (busy) e.preventDefault();
                  else void download(e);
                }}
              >
                {busy ? t('common.loading') : t('export.download')}
              </a>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
