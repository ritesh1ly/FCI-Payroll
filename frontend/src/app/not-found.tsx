'use client';

import Link from 'next/link';
import { useI18n } from '@/shared/i18n';

/** Replaces the framework 404 (its inline styles would be blocked by the CSP). */
export default function NotFound() {
  const { t } = useI18n();
  return (
    <main className="denied">
      <div className="big">🧭</div>
      <h2>{t('notFound.title')}</h2>
      <p>
        <Link href="/">{t('notFound.home')}</Link>
      </p>
    </main>
  );
}
