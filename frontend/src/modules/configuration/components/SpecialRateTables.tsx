'use client';

import { useMemo, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { date, ExportButton, ServerPagination } from '@/shared/ui';
import { Act, Explained, Locked, RowActs, Tbl, Toolbar, ViewDialog } from '@/shared/ui/wireframe';
import type { RateFilter, RateMaster } from '../api/mastersApi';
import { masterBase, summarise } from '../business/masterRules';
import { masterKeys, masterPages, specialRatesExportUrl } from '../orchestration/masterQueries';
import { RegisterSection, SimulateAct, useApproveRow, useMasterRights } from './MasterScreenParts';
import { RateMasters } from './RateMasters';

/**
 * M-22 Special Compensatory & Allowance Rate Masters as Wireframes v6.1 draws it (OD-121; SRS l.2818,
 * SRS-11-15, SRS-G-38, DDL rate_set): the pattern and allowance type filters, kept separately for IDA
 * and CDA (the SRS "separate tabs"), the six Special Compensatory masters and the further rate masters,
 * read from the rate sets. Values stay a setting: a master without a version in force says so. The
 * version editor (a dated proposal linked to its Active circular) and every version stay in the closed
 * register; ✎ Edit opens the editor of the row.
 */
export function SpecialRates() {
  const { t } = useI18n();
  const rights = useMasterRights('M-22');
  const approve = useApproveRow();
  const [pattern, setPattern] = useState('IDA');
  const [group, setGroup] = useState('');
  const [q, setQ] = useState('');
  const [register, setRegister] = useState(false);
  const [editing, setEditing] = useState<RateMaster | null>(null);
  const [viewing, setViewing] = useState<RateMaster | null>(null);
  const filter = useMemo<RateFilter>(() => ({ pattern, group, q }), [pattern, group, q]);

  /** The row's Edit: the version editor for the Administrator, the reason for a role that cannot. */
  function edit(row: RateMaster) {
    setRegister(true);
    if (rights.mayCreate && !row.pending) setEditing(row);
  }

  const actions = (m: RateMaster) => (
    <RowActs
      viewName={`${t('common.view')} ${m.name}`}
      editName={`${t('common.edit')} ${m.name}`}
      onView={() => setViewing(m)}
      onEdit={rights.drawn && !m.pending ? () => edit(m) : undefined}
      editWhy={rights.drawn ? t('mst.why.pending') : t('m04.why.adminOnly')}
      onApprove={rights.checker ? () => approve(m.pending !== null) : undefined}
    />
  );

  const addVersion = rights.drawn ? (
    <Act label={t('m22.addVersion')} kind="sm" onClick={() => setRegister(true)} />
  ) : (
    <Explained label={t('m22.addVersion')} kind="sm" why={t('m22.why.adminOnly')} />
  );

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('wf.historyAct')} onClick={() => setRegister(true)} />
        <SimulateAct label={t('m22.simulate')} />
        {rights.drawn ? (
          <Act label={t('m04.newVersion')} kind="pri" onClick={() => setRegister(true)} />
        ) : (
          <Locked label={t('m04.newVersion')} why={t('m22.why.adminOnly')} />
        )}
      </div>
      <Toolbar
        search={q}
        onSearch={setQ}
        exportControl={
          rights.mayExport ? (
            <ExportButton
              href={specialRatesExportUrl(filter)}
              what={t('screen.M-22')}
              label={t('wf.export')}
            />
          ) : (
            <Explained label={t('wf.export')} why={t('m22.why.export')} />
          )
        }
      >
        <select
          aria-label={t('m22.filter.pattern')}
          value={pattern}
          onChange={(e) => setPattern(e.target.value)}
        >
          <ReferenceOptions list="RATE_MASTER_PATTERN" />
        </select>
        <select aria-label={t('m22.filter.group')} value={group} onChange={(e) => setGroup(e.target.value)}>
          <option value="">{t('m22.filter.all')}</option>
          <ReferenceOptions list="RATE_MASTER_GROUP" />
        </select>
      </Toolbar>
      {group !== 'OTHER_RATE_MASTER' && (
        <Tbl
          title={t('m22.special.title')}
          acts={
            <>
              <Act label={t('mst.history')} kind="sm" onClick={() => setRegister(true)} />
              {addVersion}
            </>
          }
          cols={[
            { t: t('m22.col.allowance'), s: 'ENT' },
            { t: t('m22.col.payCode'), s: 'RULE' },
            { t: t('m22.col.key'), s: 'RULE' },
            { t: t('m22.col.pattern'), s: 'RULE' },
            { t: t('m22.col.basis'), s: 'RULE' },
            { t: t('m04.col.status'), s: 'SYS' },
            { t: t('m04.col.actions'), s: 'SYS' },
          ]}
          after={
            <div className="bd">
              <div className="oq">
                <b>{t('m22.uplift.lead')}</b> {t('m22.uplift.text')}
              </div>
            </div>
          }
        >
          <ServerPagination
            queryKey={masterKeys.specialRates({ ...filter, group: 'SPECIAL_COMPENSATORY' })}
            fetchPage={masterPages.specialRates({ ...filter, group: 'SPECIAL_COMPENSATORY' })}
            personal={false}
            empty={<p className="fl-hint">{t('m22.none')}</p>}
          >
            {(rows) =>
              rows.map((m) => {
                const base = masterBase(m.code);
                return (
                  <tr key={m.code}>
                    <td>
                      <b>{t(`m22.name.${base}`)}</b>
                    </td>
                    <td>{m.payCode ?? <span className="pill p-ok">{t('m22.carriedForward')}</span>}</td>
                    <td>{t(`m22.key.${base}`)}</td>
                    <td>{t('m22.bothTabs')}</td>
                    <td>{t('m22.flatPerSlab')}</td>
                    <td>
                      <SeedPill master={m} />
                    </td>
                    <td>{actions(m)}</td>
                  </tr>
                );
              })
            }
          </ServerPagination>
        </Tbl>
      )}
      {group !== 'SPECIAL_COMPENSATORY' && (
        <div className="fl-mt">
          <Tbl
            title={t('m22.further.title')}
            acts={<Act label={t('mst.history')} kind="sm" onClick={() => setRegister(true)} />}
            cols={[
              { t: t('m22.col.master'), s: 'ENT' },
              { t: t('m22.col.keyStructure'), s: 'RULE' },
              { t: t('m22.col.drives'), s: 'RULE' },
              { t: t('m04.col.status'), s: 'SYS' },
              { t: t('m04.col.actions'), s: 'SYS' },
            ]}
          >
            <ServerPagination
              queryKey={masterKeys.specialRates({ ...filter, group: 'OTHER_RATE_MASTER' })}
              fetchPage={masterPages.specialRates({ ...filter, group: 'OTHER_RATE_MASTER' })}
              personal={false}
              empty={<p className="fl-hint">{t('m22.none')}</p>}
            >
              {(rows) =>
                rows.map((m) => {
                  const base = masterBase(m.code);
                  return (
                    <tr key={m.code}>
                      <td>
                        <b>{t(`m22.name.${base}`)}</b>
                      </td>
                      <td>{t(`m22.key.${base}`)}</td>
                      <td>{t(`m22.drives.${base}`)}</td>
                      <td>
                        {m.current ? (
                          <span className="pill p-ok">{t('m22.seeded')}</span>
                        ) : (
                          <span className="pill p-bad">{t(`m22.gap.${base}`)}</span>
                        )}
                      </td>
                      <td>{actions(m)}</td>
                    </tr>
                  );
                })
              }
            </ServerPagination>
          </Tbl>
        </div>
      )}
      <RegisterSection
        id="m22-register"
        label={t('mst.register.M-22')}
        open={register}
        onToggle={setRegister}
      >
        <RateMasters screen="M-22" edit={editing} onEditing={() => setEditing(null)} />
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-22')}
          onClose={() => setViewing(null)}
          actions={<HistoryButton entity="rate_set" recordKey={viewing.code} />}
          rows={[
            { k: t('m22.col.allowance'), v: viewing.name, s: 'ENT' },
            { k: t('m22.col.payCode'), v: viewing.payCode ?? t('m22.carriedForward'), s: 'RULE' },
            {
              k: t('mst.inForce'),
              v: viewing.current ? summarise(viewing.current.lines) : t('mst.notSeeded'),
              s: 'RULE',
            },
            {
              k: t('m04.col.from'),
              v: viewing.current ? date(viewing.current.effectiveFrom) : '—',
              s: 'ENT',
            },
            {
              k: t('mst.pending'),
              v: viewing.pending
                ? t('mst.pendingVersion', [viewing.pending.versionNo, date(viewing.pending.effectiveFrom)])
                : '—',
              s: 'SYS',
            },
          ]}
        />
      )}
    </div>
  );
}

function SeedPill({ master }: { master: RateMaster }) {
  const { t } = useI18n();
  return master.current ? (
    <span className="pill p-ok">{t('m22.seeded')}</span>
  ) : (
    <span className="pill p-bad">{t('mst.notSeeded')}</span>
  );
}
