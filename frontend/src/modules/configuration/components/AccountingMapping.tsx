'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { date, ServerPagination } from '@/shared/ui';
import { Act, Locked, RowActs, Tbl } from '@/shared/ui/wireframe';
import type { AccountingMaster } from '../api/mastersApi';
import { masterKeys, masterPages } from '../orchestration/masterQueries';
import { GlRegister } from './GlMapping';
import { RegisterSection, useApproveRow, useMasterRights } from './MasterScreenParts';

const NATURAL_ACCOUNT = 'NATURAL_ACCOUNT';

/** The basis pill of a master as the wireframe draws it. */
const BASIS_TONE: Record<string, string> = { BANK: 'p-warn', CITY_TYPE: 'p-warn' };

/**
 * M-24 Accounting & Bank Mapping as Wireframes v6.1 and the SRS table list it (OD-121; SRS-G-7,
 * SRS-22-7, BR-MIS-006, FRS §6.7.1-§6.7.4): the seven mapping masters with their consequence, basis,
 * effective date and state, read live. Finance is the Maker (GUARD m24, SRS-G-7): "＋ New Mapping" and
 * ✎ Edit of the natural accounts open the per-pay-code register, a dated proposal for the Payroll
 * Checker; 👁 View opens a master's records, read only. The other six masters are kept where the SRS
 * says (M-07, M-16, FAP Finance, APPS, EBS), so their Edit is disabled with that reason.
 */
export function GlMapping() {
  const { t } = useI18n();
  const rights = useMasterRights('M-24');
  const approve = useApproveRow();
  const [register, setRegister] = useState(false);
  const [viewing, setViewing] = useState<string | null>(null);

  function view(code: string) {
    if (code === NATURAL_ACCOUNT) setRegister(true);
    else setViewing(code);
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('wf.historyAct')} onClick={() => setRegister(true)} />
        {rights.mayCreate ? (
          <Act label={t('m24.newMapping')} kind="pri" onClick={() => setRegister(true)} />
        ) : (
          <Locked label={t('m24.newMapping')} why={t('m24.why.financeMaker')} />
        )}
      </div>
      <Tbl
        title={t('m24.title')}
        acts={<Act label={t('mst.history')} kind="sm" onClick={() => setRegister(true)} />}
        cols={[
          { t: t('m22.col.master'), s: 'ENT' },
          { t: t('m24.col.maps'), s: 'RULE' },
          { t: t('m24.col.wrong'), s: 'SYS' },
          { t: t('m23.col.basis'), s: 'SYS' },
          { t: t('m04.col.from'), s: 'ENT' },
          { t: t('m04.col.status'), s: 'SYS' },
          { t: t('m04.col.actions'), s: 'SYS' },
        ]}
        after={
          <div className="bd">
            <div className="oq">
              <b>{t('m24.precedence.lead')}</b> {t('m24.precedence.text')}
            </div>
          </div>
        }
      >
        <ServerPagination
          queryKey={masterKeys.accounting}
          fetchPage={masterPages.accounting}
          personal={false}
        >
          {(rows) =>
            rows.map((m) => (
              <tr key={m.code}>
                <td>
                  <b>{t(`m24.master.${m.code}`)}</b>
                </td>
                <td>{t(`m24.maps.${m.code}`)}</td>
                <td>{t(`m24.wrong.${m.code}`)}</td>
                <td>
                  <span className={`pill ${BASIS_TONE[m.code] ?? 'p-ok'}`}>{t(`m24.basis.${m.code}`)}</span>
                </td>
                <td>{m.effectiveFrom ? date(m.effectiveFrom) : '—'}</td>
                <td>
                  <StatusPill master={m} />
                </td>
                <td>
                  <RowActs
                    viewName={`${t('common.view')} ${t(`m24.master.${m.code}`)}`}
                    editName={`${t('common.edit')} ${t(`m24.master.${m.code}`)}`}
                    onView={() => view(m.code)}
                    onEdit={
                      m.code === NATURAL_ACCOUNT && rights.mayCreate ? () => setRegister(true) : undefined
                    }
                    editWhy={editReason(t, m.code)}
                    onApprove={rights.checker ? () => approve(m.pending) : undefined}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      {viewing && (
        <div className="fl-mt">
          <Tbl
            title={t(`m24.master.${viewing}`)}
            acts={<Act label={t('common.close')} kind="sm" onClick={() => setViewing(null)} />}
            cols={[
              { t: t('m24.rec.key') },
              { t: t('m24.rec.name') },
              { t: t('m24.rec.detail') },
              { t: t('m04.col.from') },
              { t: t('m04.col.to') },
            ]}
          >
            <ServerPagination
              queryKey={masterKeys.accountingRecords(viewing)}
              fetchPage={masterPages.accountingRecords(viewing)}
              personal={false}
              empty={<p className="fl-hint">{t('m24.rec.none')}</p>}
            >
              {(records) =>
                records.map((r) => (
                  <tr key={r.key}>
                    <td>
                      <b>{r.key}</b>
                    </td>
                    <td>{r.name ?? '—'}</td>
                    <td>{r.detail ?? '—'}</td>
                    <td>{r.effectiveFrom ? date(r.effectiveFrom) : '—'}</td>
                    <td>{r.effectiveTo ? date(r.effectiveTo) : '—'}</td>
                  </tr>
                ))
              }
            </ServerPagination>
          </Tbl>
        </div>
      )}
      <RegisterSection
        id="m24-register"
        label={t('mst.register.M-24')}
        open={register}
        onToggle={setRegister}
      >
        <GlRegister />
      </RegisterSection>
    </div>
  );
}

function editReason(t: (key: string) => string, code: string): string {
  return t(code === NATURAL_ACCOUNT ? 'm24.why.financeMaker' : `m24.why.${code}`);
}

function StatusPill({ master }: { master: AccountingMaster }) {
  const { t } = useI18n();
  return master.records > 0 ? (
    <span className="pill p-ok">{t('m04.status.ACTIVE')}</span>
  ) : (
    <span className="pill p-bad">{t('mst.notSeeded')}</span>
  );
}
