'use client';

import { useEffect } from 'react';
import { useI18n } from '@/shared/i18n';
import { log } from '@/shared/log';

/**
 * The error boundary of every route: a render error is logged (masked, with its digest so the
 * server log can be matched) and the user is told, with a retry, instead of a blank page.
 */
export default function RouteError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  const { t } = useI18n();
  useEffect(() => {
    log.exception('ui.render_error', error, { digest: error.digest, path: window.location.pathname });
  }, [error]);
  return (
    <div className="wireframe-body-container" role="alert">
      <p>{t('error.unexpected')}</p>
      <button type="button" className="btn" onClick={reset}>
        {t('common.retry')}
      </button>
    </div>
  );
}
