'use client';

import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, Empty, Problem, ServerPagination, dateTime } from '@/shared/ui';
import { esevaKeys, noticesPage, useMarkRead } from '../orchestration/esevaQueries';

/**
 * E-01 notices (plan 14 phase 14e; SRS-17-12, KB ES-7): each status of the member's self-service
 * submissions (submitted, in approval, actioned, rejected), newest first; the member marks one read.
 * The same news goes to the registered e-mail; a failed e-mail never hides it here.
 */
export function MyNotifications() {
  const { t } = useI18n();
  const me = useMe();
  const read = useMarkRead();
  if (!can(me.data, 'E-01', 'VIEW')) return null;
  return (
    <Card title={t('e01.notices')}>
      {/* SRS-17-12: a failed mark-read is shown, not lost (review 14-2). */}
      <Problem error={read.error} />
      <ServerPagination
        queryKey={esevaKeys.notices}
        fetchPage={noticesPage}
        empty={<Empty text={t('e01.noNotices')} />}
      >
        {(rows) => (
          <ul className="fl-list" aria-label={t('e01.notices')}>
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
