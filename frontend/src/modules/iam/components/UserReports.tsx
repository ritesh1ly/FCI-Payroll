'use client';

import { useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, dateTime, Empty, ExportButton, Problem, ServerPagination, StatusPill, Src } from '@/shared/ui';
import type { ActiveUserReport as Report, UnitReadiness } from '../api/adminApi';
import {
  activeUsersExportUrl,
  adminKeys,
  fetchActiveUserPage,
  useReadiness,
} from '../orchestration/adminQueries';

/**
 * F-3 Active User Report (SRS-G-50): user ID, role, unit, access group and last sign-in, paged on
 * the server; users inactive beyond the A-07 setting are flagged, and the screen says so when the
 * setting is not configured (no guessed threshold). Exported as CSV with A-01 EXPORT.
 */
export function ActiveUserReport() {
  const { t } = useI18n();
  const me = useMe();
  const [query, setQuery] = useState('');
  const [inactiveOnly, setInactiveOnly] = useState(false);
  const [total, setTotal] = useState(0);
  const [threshold, setThreshold] = useState<number | null | undefined>(undefined);
  const fetchPage = useCallback(
    async (page: number, size: number) => {
      const report: Report = await fetchActiveUserPage(query, inactiveOnly, page, size);
      setThreshold(report.inactiveAfterDays);
      return report;
    },
    [query, inactiveOnly],
  );
  return (
    <>
      <div className="toolbar">
        <div className="lsearch">
          🔎
          <input
            aria-label={t('a01.search')}
            placeholder={t('a01.search')}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            maxLength={60}
          />
        </div>
        <label className="sub2">
          <input
            type="checkbox"
            checked={inactiveOnly}
            disabled={threshold === null}
            onChange={(e) => setInactiveOnly(e.target.checked)}
          />{' '}
          {t('a01.report.inactiveOnly')}
        </label>
        {can(me.data, 'A-01', 'EXPORT') && (
          <ExportButton href={activeUsersExportUrl(query, inactiveOnly)} what={t('a01.tab.report')} />
        )}
      </div>
      {threshold === null && <p className="fl-banner">{t('a01.report.notConfigured')}</p>}
      {typeof threshold === 'number' && <p className="fl-hint">{t('a01.report.threshold', [threshold])}</p>}
      <Card title={t('a01.report.title', [total])}>
        <ServerPagination
          queryKey={[...adminKeys.users, 'report', query, inactiveOnly]}
          fetchPage={fetchPage}
          onTotal={setTotal}
          empty={<Empty text={t('a01.none')} />}
        >
          {(rows) => (
            <div className="wrap">
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('a01.loginId')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('m01.status')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('a01.lastLogin')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('a01.report.access')}
                      <Src s="SYS" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((user) => (
                    <tr key={user.userId}>
                      <td>
                        <b>{user.loginId}</b>
                        <div className="sub2">{user.displayName}</div>
                      </td>
                      <td>
                        <StatusPill status={user.status} />
                        {user.inactive === true && (
                          <span className="pill p-bad">{t('a01.report.inactive')}</span>
                        )}
                      </td>
                      <td>
                        {user.lastLoginAt ? (
                          <>
                            {dateTime(user.lastLoginAt)}
                            <div className="sub2">{t('a01.report.days', [user.daysSinceLogin ?? 0])}</div>
                          </>
                        ) : (
                          <span className="sub2">{t('a01.report.never')}</span>
                        )}
                      </td>
                      <td>
                        {user.access.length === 0 ? (
                          <span className="sub2">{t('a01.report.noAccess')}</span>
                        ) : (
                          user.access.map((a) => (
                            <div key={a.groupCode}>
                              {a.groupCode} · {t(`role.${a.roleCode}`)} ·{' '}
                              {a.unit === 'ALL' ? t('a01.report.allOffices') : (a.unit ?? '—')}
                            </div>
                          ))
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </ServerPagination>
      </Card>
    </>
  );
}

const TONE: Record<UnitReadiness['readiness'], string> = {
  READY: 'p-ok',
  COVERED_FROM_ABOVE: 'p-warn',
  NOT_READY: 'p-bad',
};

/**
 * F-7 readiness check (SRS-G-50, SRS-22-11): every unit that runs payroll has a Maker and a
 * separate Checker, its own or reaching it from above; a Divisional Office's own staff against the
 * A-07 minimum when FCI has configured it.
 */
export function ReadinessCheck() {
  const { t } = useI18n();
  const report = useReadiness(true);
  const units = report.data?.units ?? [];
  const minimum = report.data?.doMinimumStaff;
  return (
    <>
      <p className="fl-hint">{t('a01.readiness.hint')}</p>
      {report.isSuccess &&
        (minimum === null ? (
          <p className="fl-banner">{t('a01.readiness.minimumNotConfigured')}</p>
        ) : (
          <p className="fl-hint">{t('a01.readiness.minimum', [minimum])}</p>
        ))}
      <Problem error={report.error} />
      <Card title={t('a01.readiness.title', [units.length])}>
        {report.isSuccess && units.length === 0 && <Empty text={t('a01.readiness.none')} />}
        {units.length > 0 && (
          <div className="wrap">
            <table className="t">
              <thead>
                <tr>
                  <th>
                    {t('a01.readiness.unit')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('a01.readiness.state')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('a01.readiness.makers')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('a01.readiness.checkers')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('a01.readiness.ownStaff')}
                    <Src s="SYS" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {units.map((unit) => (
                  <tr key={unit.locationId}>
                    <td>
                      <b>{unit.label}</b>
                    </td>
                    <td>
                      <span className={`pill ${TONE[unit.readiness]}`}>
                        {t(`a01.readiness.${unit.readiness}`)}
                      </span>
                    </td>
                    <td>{unit.makers.join(', ') || '—'}</td>
                    <td>{unit.checkers.join(', ') || '—'}</td>
                    <td>
                      {unit.ownStaff}
                      {unit.meetsMinimum === false && (
                        <span className="pill p-bad">{t('a01.readiness.belowMinimum')}</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
