'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, Problem } from '@/shared/ui';
import { useAcknowledge, useNotice } from '../orchestration/esevaQueries';

/**
 * E-01 privacy notice (plan 14 phase 14a; SRS-SEC-9, BR-SEC-005; D14-3): offered at the member's first
 * eSeva sign-in, until the member acknowledges it. It names the personal data held and its purpose
 * and says that HRMS data is corrected in HRMS. Nothing for a staff user (no own eSeva record).
 * Not drawn (WF-1510): it stands open above the drawn portal until the member acknowledges it, never in
 * a closed section, so no member uses eSeva without having seen it (SRS-SEC-9 "show a privacy notice at
 * first eSeva login"); a notice that cannot be loaded is shown as the problem, so it is never skipped silently.
 */
export function PrivacyNotice() {
  const { t } = useI18n();
  const me = useMe();
  const member = can(me.data, 'E-01', 'VIEW');
  const notice = useNotice(member);
  const acknowledge = useAcknowledge();
  const [error, setError] = useState<unknown>(null);
  if (!member) return null;
  // SRS-SEC-9: a notice that cannot be loaded is never skipped silently - the card says so and offers
  // to load it again, so the member still sees and acknowledges it (review 14-1).
  if (notice.isError) {
    return (
      <Card title={t('eseva.privacy.title')}>
        <Problem error={notice.error} />
        <button
          type="button"
          className="btn"
          disabled={notice.isFetching}
          onClick={() => void notice.refetch()}
        >
          {t('common.retry')}
        </button>
      </Card>
    );
  }
  if (!notice.data || notice.data.acknowledged) return null;

  async function accept() {
    setError(null);
    try {
      await acknowledge.mutateAsync();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div data-testid="e01-privacy">
      <Card title={t('eseva.privacy.title')}>
        <p>{t('eseva.privacy.text')}</p>
        <Problem error={error} />
        <button
          type="button"
          className="btn primary"
          disabled={acknowledge.isPending}
          onClick={() => void accept()}
        >
          {t('action.eseva.acknowledge')}
        </button>
      </Card>
    </div>
  );
}
