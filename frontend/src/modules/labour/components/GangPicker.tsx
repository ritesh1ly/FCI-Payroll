'use client';

import { useDeferredValue, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { Src } from '@/shared/ui';
import type { GangRow, GangSearch } from '../api/labourApi';
import { GANG_PICKER_SIZE, useGangSearch, useTransferTargets } from '../orchestration/labourQueries';

/**
 * A gang picker searched on the server (I-01 l.4541 "gangs at the selected depot", I-02 Gang Name,
 * M-09 target gang; SRS-2-46 the Ancillary Pool is a gang like any other here): a code or name text
 * narrows the matches within the session's depots and the given criteria; at most
 * {@link GANG_PICKER_SIZE} are listed at once and a longer list says so, so no gang is out of reach
 * however many the scope holds.
 */
export function GangPicker({
  value,
  onChange,
  search,
  exclude,
  label,
  source,
  transferTarget = false,
}: {
  value: number | null;
  onChange: (gang: GangRow | null) => void;
  search: Omit<GangSearch, 'text'>;
  /** A gang never offered (the member's own gang on a transfer). */
  exclude?: number;
  label: string;
  source?: 'ENT';
  /** SRS-2-6 / OD-88: a transfer's receiving gang, searched in every office (depot shown). */
  transferTarget?: boolean;
}) {
  const { t } = useI18n();
  const [text, setText] = useState('');
  const [chosen, setChosen] = useState<GangRow | null>(null);
  const deferred = useDeferredValue(text.trim());
  const local = useGangSearch({ ...search, text: deferred || undefined }, !transferTarget);
  const targets = useTransferTargets(search.payrollType, deferred, transferTarget);
  const found = transferTarget ? targets : local;
  const matches = (found.data?.items ?? []).filter((g) => g.gangId !== exclude);
  // The chosen gang stays listed while the text narrows to others.
  const listed =
    chosen && chosen.gangId === value && !matches.some((g) => g.gangId === chosen.gangId)
      ? [chosen, ...matches]
      : matches;
  const total = found.data?.total ?? 0;
  return (
    <>
      <label>
        {t('m09.gangSearch')} <input maxLength={150} value={text} onChange={(e) => setText(e.target.value)} />
      </label>
      <label>
        {label}
        {source && <Src s={source} />}{' '}
        <select
          value={value ?? ''}
          onChange={(e) => {
            const next = listed.find((g) => g.gangId === Number(e.target.value)) ?? null;
            setChosen(next);
            onChange(next);
          }}
        >
          <option value="">—</option>
          {listed.map((g) => (
            <option key={g.gangId} value={g.gangId}>
              {g.gangCode} - {g.gangName}
              {transferTarget ? ` · ${g.depot}` : ''}
              {g.gangType === 'ANCILLARY_POOL' ? ` (${t('m09.type.ANCILLARY_POOL')})` : ''}
            </option>
          ))}
        </select>
      </label>
      {total > GANG_PICKER_SIZE && (
        <span className="sub2" role="note">
          {t('m09.gangMore', [GANG_PICKER_SIZE, total])}
        </span>
      )}
    </>
  );
}
