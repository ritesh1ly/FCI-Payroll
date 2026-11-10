'use client';

import { useEffect, useRef, useState } from 'react';
import { API_BASE, ApiError, apiFile, fileName } from '@/shared/api';

/** The file name of a download from its Content-Disposition, else the fallback. */
export function downloadName(disposition: string | null, fallback: string): string {
  return fileName(disposition) ?? fallback;
}

/** Hands a fetched file to the browser as a download under its server name, else the fallback. */
export function saveFile(blob: Blob, name: string): void {
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(link.href), 60_000);
}

/**
 * Fetches a file with the session cookie and hands it to the browser as a download (SRS-G-13,
 * OD-144, OD-149: lists, reports, the PDFs and the ZIPs). A refusal (no permission, outside the
 * scope, too many rows, nothing to print, busy) is thrown as an ApiError for the caller to show,
 * never saved as a file of error text; a call the session's gates refuse is never sent (OD-130).
 * The server names the file (Content-Disposition); {@code fallback} only when it does not.
 */
export async function downloadFile(url: string, fallback: string): Promise<void> {
  const path = url.startsWith(API_BASE) ? url.slice(API_BASE.length) : url;
  const file = await apiFile(path);
  saveFile(file.blob, file.name ?? fallback);
}

/**
 * The download of a document (OD-144, OD-149): one at a time (a second press while one runs is
 * ignored, so a double click sends one request), its refusal for the screen to show, and after a
 * 429 / 503 the seconds until it may be tried again (Retry-After), counted down.
 */
export function usePdfDownload() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [retryIn, setRetryIn] = useState(0);
  const running = useRef(false);

  useEffect(() => {
    if (retryIn <= 0) return undefined;
    const timer = window.setTimeout(() => setRetryIn((s) => s - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [retryIn]);

  async function download(path: string, fallback: string): Promise<boolean> {
    if (running.current || retryIn > 0) return false;
    running.current = true;
    setBusy(true);
    setError(null);
    try {
      await downloadFile(`${API_BASE}${path}`, fallback);
      return true;
    } catch (cause) {
      const e = cause instanceof ApiError ? cause : new ApiError(0, {}, undefined);
      setError(e);
      if (e.retryAfterSeconds) setRetryIn(e.retryAfterSeconds);
      return false;
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  return { busy, error, retryIn, download, clear: () => setError(null) };
}
