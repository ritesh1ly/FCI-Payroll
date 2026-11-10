'use client';

import { useI18n } from '@/shared/i18n';
import { date, Problem } from '@/shared/ui';
import { mdmLinks, useCurrentCirculars } from '../orchestration/mdmQueries';

/** At most this many circulars on the home card; M-25 lists them all. */
const HOME_ROWS = 5;

/**
 * SRS-G-38 (UI_BMAD_g1 #40, US-01-17 AC6): "show current circulars on the home page for the user's
 * office and payroll". The circulars in force today that apply to the session's office (the server
 * filters them), newest first, with the copy to open. Which ones count as "new" is open (Q-04-5), so
 * none is highlighted.
 */
export function CurrentCircularsHome() {
  const { t } = useI18n();
  const current = useCurrentCirculars();
  const rows = (current.data ?? []).slice(0, HOME_ROWS);
  return (
    <section className="dash-circulars" aria-labelledby="dash-circulars-title">
      <h2 id="dash-circulars-title" className="dash-sub">
        {t('dash.circulars')}
      </h2>
      <Problem error={current.error} />
      {current.data && rows.length === 0 && <p className="fl-hint">{t('dash.circularsNone')}</p>}
      {rows.length > 0 && (
        <ul className="dash-circular-list">
          {rows.map((c) => (
            <li key={c.id}>
              <b>{c.number}</b> · {date(c.date)} · {c.subject}
              {c.documentId !== null && (
                <>
                  {' '}
                  <a href={mdmLinks.circularDocument(c.id)} target="_blank" rel="noopener noreferrer">
                    {t('dash.circularOpen')}
                  </a>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {(current.data?.length ?? 0) > HOME_ROWS && (
        <p className="sub2">{t('dash.circularsMore', [(current.data?.length ?? 0) - HOME_ROWS])}</p>
      )}
    </section>
  );
}
