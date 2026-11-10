'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { withBase } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { Act, Locked, RowActs, Tbl } from '@/shared/ui/wireframe';
import { SavingsDeclarations } from './SavingsDeclarations';

/** The four declaration screens of the Employee Manual (Annexure A.10), by bundle key. */
const SCREENS = ['internal', 'external', 'transport', 'income'] as const;
/** The tax section map of the Employee Manual: no table holds it (WF list). */
const MAP = ['550', '600', '601', '604', '606', '587'] as const;

/**
 * I-18 Savings & Tax Rebate Declarations as Wireframes v6.1 draws it (OD-121; SRS-7-11, SRS-7-20, SRS-7-25,
 * BR-TAX-007, BR-TAX-015): the four declaration screens and the tax section map carried forward from the current
 * system. The entries of each member, their documents, the Checker route and the transport rebate stay whole in the
 * closed register, which lists every member's savings: the eSeva role keeps no access to the screen (WF-075).
 */
export function SavingsScreen() {
  const { t } = useI18n();
  const me = useMe();
  const mayCreate = can(me.data, 'I-18', 'CREATE');
  const checker = can(me.data, 'I-18', 'APPROVE');
  const [open, setOpen] = useState(false);
  const show = () => setOpen(true);
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('txw.i18.history')} onClick={show} />
        {mayCreate ? (
          <Act label={t('txw.i18.add')} kind="pri" onClick={show} />
        ) : (
          <Locked label={t('txw.i18.add')} why={t('txw.why.makerOnly')} />
        )}
      </div>
      <Tbl
        title={t('txw.i18.screens')}
        cols={[
          { t: t('txw.i18.col.screen'), s: 'ENT' },
          { t: t('txw.i18.col.holds'), s: 'RULE' },
          { t: t('txw.i18.col.effect'), s: 'CALC' },
          { t: t('txw.i18.col.basis'), s: 'SYS' },
          { t: t('txw.i18.col.actions'), s: 'SYS' },
        ]}
      >
        {SCREENS.map((s) => (
          <tr key={s} data-reference={s}>
            <td>{t(`txw.i18.s.${s}`)}</td>
            <td>{t(`txw.i18.s.${s}.holds`)}</td>
            <td>{t(`txw.i18.s.${s}.effect`)}</td>
            <td>{t(`txw.i18.s.${s}.basis`)}</td>
            <td>
              <RowActs
                viewName={`${t('common.view')} ${t(`txw.i18.s.${s}`)}`}
                editName={`${t('common.edit')} ${t(`txw.i18.s.${s}`)}`}
                onView={show}
                onEdit={mayCreate ? show : undefined}
                editWhy={t('txw.why.makerInputs')}
                onApprove={checker ? () => window.location.assign(withBase('/payroll/?s=W-01')) : undefined}
              />
            </td>
          </tr>
        ))}
      </Tbl>
      <Tbl
        title={t('txw.i18.map')}
        cols={[
          { t: t('txw.i18.map.code'), s: 'RULE' },
          { t: t('txw.i18.map.instrument'), s: 'RULE' },
          { t: t('txw.i18.map.section'), s: 'RULE' },
          { t: t('txw.i18.map.basis'), s: 'SYS' },
        ]}
      >
        {MAP.map((m) => (
          <tr key={m}>
            <td>{t(`txw.i18.m.${m}.code`)}</td>
            <td>{t(`txw.i18.m.${m}.instrument`)}</td>
            <td>{t(`txw.i18.m.${m}.section`)}</td>
            <td>{t('txw.i18.carried')}</td>
          </tr>
        ))}
      </Tbl>
      <RegisterSection label={t('txw.i18.section')} open={open} onToggle={setOpen} id="i18-register">
        {open && <SavingsDeclarations />}
      </RegisterSection>
    </div>
  );
}
