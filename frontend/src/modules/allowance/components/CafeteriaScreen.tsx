'use client';

import { useState } from 'react';
import { monthName, monthShort } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { finYearOfDay, businessToday } from '@/shared/time';
import { amount, Empty, ExportButton, ServerPagination } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { Act, Explained, Panel, Tbl } from '@/shared/ui/wireframe';
import type { CafeteriaSelection } from '../api/claimsApi';
import { cafeteriaExportUrl, cafeteriaPage, claimKeys } from '../orchestration/claimQueries';
import { CafeteriaAllowance } from './CafeteriaAllowance';

/** Pill tone of a selection's status (SRS I-16: Applied / Arrear raised / No selection in HRMS). */
function tone(status: string): string {
  if (status === 'APPLIED') return 'p-ok';
  if (status === 'ARREAR_RAISED') return 'p-warn';
  return 'p-gray';
}

/**
 * I-16 Cafeteria Allowance (from HRMS) as Wireframes v6.1 draws it (OD-121; SRS-11-16, BR-INT-011, BR-CYC-010, SRS
 * I-16 field table): the selections received from HRMS for the financial year, read-only, each with its pay
 * effect: paid from its exact start date, an arrear where it was received after the freeze; a member with no
 * selection reads "No selection in HRMS". The EIS and year filters, each version's start date, end date and
 * monthly amounts stay in the closed section behind the drawn History (WF-1406: the SRS's Start Date is not
 * drawn). Selections reach Payroll through I-21, never through this screen.
 */
export function CafeteriaScreen() {
  const { t } = useI18n();
  const me = useMe();
  const [history, setHistory] = useState(false);
  const finYear = finYearOfDay(businessToday());
  if (!can(me.data, 'I-16', 'VIEW')) return null;

  function payEffect(s: CafeteriaSelection): string {
    if (s.status === 'NO_SELECTION') return t('i16w.effect.none');
    if (s.status === 'ARREAR_RAISED') {
      return t('i16w.effect.arrear', [monthShort(s.arrearFrom), monthShort(s.arrearTo)]);
    }
    if (s.status === 'APPLIED' && s.startDate) {
      const from = monthName(s.startDate.slice(0, 7).replace('-', ''));
      return t('i16w.effect.paid', [amount(s.payEffect), from]);
    }
    return amount(s.payEffect);
  }

  function status(s: CafeteriaSelection): string {
    if (s.status === 'ARREAR_RAISED' && s.arrearIn) {
      return t('i16w.st.arrearIn', [monthShort(s.arrearIn)]);
    }
    return t(`i16.st.${s.status}`);
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('wf.historyAct')} onClick={() => setHistory(true)} />
      </div>
      <Tbl
        title={t('i16w.title', [finYear])}
        acts={
          can(me.data, 'I-16', 'EXPORT') ? (
            <ExportButton href={cafeteriaExportUrl('', finYear)} what={t('i16.title')} className="btn sm" />
          ) : (
            <Explained label={t('i16w.export')} kind="sm" why={t('i16w.why.export')} />
          )
        }
        cols={[
          { t: t('i16w.col.eis'), s: 'HRMS' },
          { t: t('i16w.col.name'), s: 'HRMS' },
          { t: t('i16.category'), s: 'HRMS' },
          { t: t('i16w.col.components'), s: 'HRMS' },
          { t: t('i16.reference'), s: 'INTF' },
          { t: t('i16w.col.effect'), s: 'CALC' },
          { t: t('i16.status'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={[...claimKeys.cafeteria('', finYear), '#drawn']}
          fetchPage={cafeteriaPage('', finYear)}
          empty={<Empty text={t('i16.none')} />}
        >
          {(rows) =>
            rows.map((s) => (
              <tr key={s.id ?? `none-${s.eis}`} data-reference={s.eis}>
                <td>{s.eis}</td>
                <td>{s.name}</td>
                <td>{s.category ?? '—'}</td>
                <td>{s.components.map((c) => c.name).join(' · ') || '—'}</td>
                <td>{s.hrmsReference ?? '—'}</td>
                <td>{payEffect(s)}</td>
                <td>
                  <span className={`pill ${tone(s.status)}`}>{status(s)}</span>
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="fl-mt">
        <Panel
          title={t('i16w.panel')}
          items={[
            { icon: '⬇', tone: 'info', title: t('i16w.p1'), src: 'HRMS', text: t('i16w.p1Sub') },
            { icon: '🧮', tone: 'info', title: t('i16w.p2'), src: 'CALC', text: t('i16w.p2Sub') },
            { icon: '💸', tone: 'gray', title: t('i16w.p3'), src: 'CALC', text: t('i16w.p3Sub') },
          ]}
        />
      </div>
      <RegisterSection id="i16-register" label={t('i16w.register')} open={history} onToggle={setHistory}>
        {history && <CafeteriaAllowance />}
      </RegisterSection>
    </div>
  );
}
