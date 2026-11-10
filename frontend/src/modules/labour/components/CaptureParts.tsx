'use client';

import { useI18n } from '@/shared/i18n';
import type { AnnaCounts, GangOption } from '../api/captureApi';
import type { PayrollType } from '../api/labourApi';

const TYPES: PayrollType[] = ['DEPT_LABOUR', 'DPS_LABOUR', 'NWNP_LABOUR'];

/** The drawn payroll-type selector (TYPESEL): the view's code set, rules and lists change with it. */
export function TypeSelect({
  value,
  onChange,
}: {
  value: PayrollType;
  onChange: (type: PayrollType) => void;
}) {
  const { t } = useI18n();
  return (
    <select
      aria-label={t('i01w.typeOf')}
      value={value}
      onChange={(e) => onChange(e.target.value as PayrollType)}
    >
      {TYPES.map((p) => (
        <option key={p} value={p}>
          {t(`i01w.type.${p}`)}
        </option>
      ))}
    </select>
  );
}

/** "All gangs" and the gangs of the type (OD-123: from the master, code only as drawn). */
export function GangFilter({
  gangs,
  value,
  onChange,
}: {
  gangs: GangOption[] | undefined;
  value: number | null;
  onChange: (gang: number | null) => void;
}) {
  const { t } = useI18n();
  return (
    <select
      aria-label={t('i01w.allGangs')}
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}
    >
      <option value="">{t('i01w.allGangs')}</option>
      {(gangs ?? []).map((g) => (
        <option key={g.id} value={g.id}>
          {g.code}
        </option>
      ))}
    </select>
  );
}

/** The Anna Darpan confirmation strip (SRS-2-27), with the counts of what Payroll holds. */
export function AnnaStrip({ anna }: { anna: AnnaCounts | undefined }) {
  const { t } = useI18n();
  const counts = anna ?? { received: 0, confirmed: 0, returned: 0 };
  return (
    <div className="keep">
      <div className="anno on">
        <b>{t('i01w.anna.lead')}</b> {t('i01w.anna.text1')} <b>{t('i01w.anna.confirms')}</b>{' '}
        {t('i01w.anna.text2')}{' '}
        <span className="pill p-info">
          {t('i01w.anna.counts', [counts.received, counts.confirmed, counts.returned])}
        </span>
      </div>
    </div>
  );
}
