'use client';

import { useI18n } from '@/shared/i18n';
import { Card, Empty, Problem, ServerPagination, dateTime } from '@/shared/ui';
import { staffNoticesKey, staffNoticesPage, useMarkStaffRead } from '../orchestration/esevaQueries';

/**
 * The staff user's own notices on the home page (owner 2026-10-28; SRS-15-30, SRS-1-68): a returned
 * cycle of the unit, an I-21 task waiting past the ageing days; newest first, each marked read.
 */
export function StaffNotices() {
  const { t } = useI18n();
  const read = useMarkStaffRead();
  return (
    <Card title={t('dash.notices')}>
      {/* SRS-17-12: a failed mark-read is shown, not lost (review 14-2). */}
      <Problem error={read.error} />
      <ServerPagination
        queryKey={staffNoticesKey}
        fetchPage={staffNoticesPage}
        empty={<Empty text={t('dash.noNotices')} />}
      >
        {(rows) => (
          <ul className="fl-list" aria-label={t('dash.notices')}>
            {rows.map((n) => (
              <li key={n.id} className={n.read ? undefined : 'unread'}>
                <strong>{t(`notice.${n.type}`)}</strong> {n.message ?? ''}{' '}
                <span className="sub2">{dateTime(n.createdAt)}</span>{' '}
                {!n.read && (
                  <button
                    type="button"
                    className="btn"
                    disabled={read.isPending}
                    onClick={() => read.mutate(n.id)}
                  >
                    {t('action.e01.markRead')}
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </ServerPagination>
    </Card>
  );
}
