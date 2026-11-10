'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { date, ServerPagination } from '@/shared/ui';
import { Act, Locked, RowActs, Tbl, ViewDialog } from '@/shared/ui/wireframe';
import type { ParameterRow } from '../api/mastersApi';
import { drawnParameterRows, parameterValues } from '../business/masterRules';
import { masterKeys, masterPages } from '../orchestration/masterQueries';
import { RegisterSection, useApproveRow, useMasterRights } from './MasterScreenParts';
import { ParameterRegister } from './OtherParameters';

/** The row's basis pill as the SRS table states it. */
const BASIS_TONE: Record<string, string> = {
  BENEVOLENT_FUND: 'p-warn',
  CSSS_AMOUNT: 'p-warn',
  MHS_CONTRIBUTION: 'p-info',
};

/**
 * M-23 Other Parameters as Wireframes v6.1 draws it (OD-121; SRS M-23 table, SRS-G-8, SRS-11-15, DDL
 * other_parameter): one row per parameter with what it applies to and its basis; a rate and its
 * ceiling are drawn as one row and stay two parameters. A value the sources leave to the current
 * system stays unseeded and says so (v4.0: an unseeded ceiling silently over-pays). The MHS row holds
 * no value: the amount is received from HRMS and deducted as received (FRS §2.3, R-SRC). The version
 * editor and history stay in the closed register; ✎ Edit opens the editor of the row.
 */
export function OtherParameters() {
  const { t } = useI18n();
  const rights = useMasterRights('M-23');
  const approve = useApproveRow();
  const [register, setRegister] = useState(false);
  const [editing, setEditing] = useState<ParameterRow | null>(null);
  const [viewing, setViewing] = useState<ParameterRow | null>(null);

  function value(row: ParameterRow, ceiling: ParameterRow | null) {
    if (row.hrmsOwned) return t('m23.rule.MHS_CONTRIBUTION');
    const shown = [row.current, ceiling?.current].flatMap((v) => (v ? parameterValues(v) : []));
    return (
      <>
        {shown.length > 0 ? shown.join(' · ') : <span className="pill p-bad">{t('mst.notSeeded')}</span>}
        {row.current && !row.current.confirmed && <span className="pill p-warn">{t('mst.unconfirmed')}</span>}
        {RULE_TEXT.has(row.code) && <div className="sub2">{t(`m23.rule.${row.code}`)}</div>}
      </>
    );
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('wf.historyAct')} onClick={() => setRegister(true)} />
        {rights.drawn ? (
          <Act label={t('m23.newVersion')} kind="pri" onClick={() => setRegister(true)} />
        ) : (
          <Locked label={t('m23.newVersion')} why={t('m22.why.adminOnly')} />
        )}
      </div>
      <Tbl
        title={t('m23.title')}
        acts={<Act label={t('mst.history')} kind="sm" onClick={() => setRegister(true)} />}
        cols={[
          { t: t('m23.col.parameter'), s: 'ENT' },
          { t: t('m23.col.value'), s: 'RULE' },
          { t: t('m23.col.applies'), s: 'RULE' },
          { t: t('m23.col.basis'), s: 'SYS' },
          { t: t('m04.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={[...masterKeys.parameters, '#drawn']}
          fetchPage={masterPages.parameters}
          personal={false}
        >
          {(rows) =>
            drawnParameterRows(rows).map(({ row, ceiling }) => {
              const waiting = row.pending ?? ceiling?.pending ?? null;
              return (
                <tr key={row.code}>
                  <td>{t(`m23.name.${row.code}`)}</td>
                  <td>{value(row, ceiling)}</td>
                  <td>{t(`m23.applies.${row.code}`)}</td>
                  <td>
                    <span className={`pill ${BASIS_TONE[row.code] ?? 'p-ok'}`}>
                      {t(BASIS_TONE[row.code] ? `m23.basis.${row.code}` : 'm22.carriedForward')}
                    </span>
                  </td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${row.name}`}
                      editName={`${t('common.edit')} ${row.name}`}
                      onView={() => setViewing(row)}
                      onEdit={
                        rights.drawn && !row.hrmsOwned && !row.pending
                          ? () => {
                              setRegister(true);
                              if (rights.mayCreate) setEditing(row);
                            }
                          : undefined
                      }
                      editWhy={
                        row.hrmsOwned
                          ? t('m23.why.hrms')
                          : rights.drawn
                            ? t('mst.why.pending')
                            : t('m04.why.adminOnly')
                      }
                      onApprove={rights.checker ? () => approve(waiting !== null) : undefined}
                    />
                  </td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
      <RegisterSection
        id="m23-register"
        label={t('mst.register.M-23')}
        open={register}
        onToggle={setRegister}
      >
        <ParameterRegister edit={editing} onEditing={() => setEditing(null)} />
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-23')}
          onClose={() => setViewing(null)}
          actions={
            viewing.hrmsOwned ? undefined : (
              <HistoryButton entity="other_parameter" recordKey={viewing.code} />
            )
          }
          rows={[
            { k: t('m23.col.parameter'), v: t(`m23.name.${viewing.code}`), s: 'ENT' },
            { k: t('m23.col.value'), v: value(viewing, null), s: 'RULE' },
            { k: t('m23.col.applies'), v: t(`m23.applies.${viewing.code}`), s: 'RULE' },
            {
              k: t('m04.col.from'),
              v: viewing.current ? date(viewing.current.effectiveFrom) : '—',
              s: 'ENT',
            },
          ]}
        />
      )}
    </div>
  );
}

/** The rule text the SRS states beside the value. */
const RULE_TEXT = new Set(['NPA_PCT', 'PLI_CEILING', 'BENEVOLENT_FUND', 'CSSS_AMOUNT']);
