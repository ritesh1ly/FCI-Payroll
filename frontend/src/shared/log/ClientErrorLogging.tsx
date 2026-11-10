'use client';

import { useEffect } from 'react';
import { log } from './logger';

/**
 * Logs every exception nothing else caught: an uncaught error and an unhandled promise rejection
 * in the browser (masked, with the page path as a template). Mounted once in the providers.
 */
export function ClientErrorLogging() {
  useEffect(() => {
    const onError = (e: ErrorEvent) =>
      log.exception('ui.uncaught_error', e.error ?? e.message, { path: window.location.pathname });
    const onRejection = (e: PromiseRejectionEvent) =>
      log.exception('ui.unhandled_rejection', e.reason, { path: window.location.pathname });
    window.addEventListener('error', onError);
    window.addEventListener('unhandledrejection', onRejection);
    return () => {
      window.removeEventListener('error', onError);
      window.removeEventListener('unhandledrejection', onRejection);
    };
  }, []);
  return null;
}
