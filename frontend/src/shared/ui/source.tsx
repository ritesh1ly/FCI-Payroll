'use client';

import { useI18n } from '@/shared/i18n';

/**
 * Field-source markers of R-SRC (KB 02-cross-cutting/field-source.md, SRS §6.8.3): every field on every
 * screen has exactly one source, which decides who may change it and where.
 *   ENT entered in Payroll by the authorised role · CALC derived by an engine · HRMS owned in HRMS ·
 *   RULE resolved from the rule engine and its masters · SYS identifier, status, timestamp, workflow state ·
 *   ESS submitted by the member in eSeva · INTF landed through an interface.
 * Used on every screen's column headers and fields (layout after Wireframes v6.1).
 */
export type Source = 'ENT' | 'CALC' | 'HRMS' | 'RULE' | 'SYS' | 'ESS' | 'INTF';

/**
 * The one-letter source badge with its explanation as a tooltip. The letter is hidden from assistive
 * technology so a column header is read by its name alone ("Owner", not "Owner E").
 */
export function Src({ s }: { s: Source }) {
  const { t } = useI18n();
  return (
    <span className={`src s-${s}`} title={t(`src.${s}.title`)} aria-hidden="true">
      {t(`src.${s}`)}
    </span>
  );
}

/** A column header with its field-source badge. */
export function Th({ label, s, num }: { label: string; s?: Source; num?: boolean }) {
  return (
    <th className={num ? 'num' : undefined}>
      {label}
      {s && <Src s={s} />}
    </th>
  );
}
