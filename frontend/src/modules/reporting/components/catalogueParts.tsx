'use client';

import { useI18n } from '@/shared/i18n';
import type { CatalogueEntry } from '../api/reportApi';

/** A label by value, or the value itself where the bundle has none (a value the catalogue added later). */
export function valueLabel(t: (key: string) => string, prefix: string, value: string): string {
  const key = `${prefix}.${value}`;
  const label = t(key);
  return label === key ? value : label;
}

/**
 * The state of a catalogue entry as the API gives it (SRS-18-24): produced here, produced on its own screen, or Blocked
 * with the reason. Nothing reads Available unless the API says so; a report with no entry reads a dash.
 */
export function EntryPill({ entry }: { entry: CatalogueEntry | undefined }) {
  const { t } = useI18n();
  if (!entry) return <>—</>;
  if (entry.status === 'AVAILABLE') return <span className="pill p-ok">{t('o04.status.AVAILABLE')}</span>;
  if (entry.status === 'BLOCKED') {
    return (
      <span className="pill p-warn" title={entry.blockedReason ?? undefined}>
        {t('o04.status.BLOCKED')}
      </span>
    );
  }
  return (
    <span className="pill p-info">{t('o04.status.PRODUCED_ELSEWHERE', [entry.producingScreen ?? ''])}</span>
  );
}
