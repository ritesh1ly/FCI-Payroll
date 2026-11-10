'use client';

import { RuleCatalogue } from '@/modules/configuration';
import { PtStateMaster, SectionMaster } from '@/modules/tax';
import { useI18n } from '@/shared/i18n';

/**
 * M-18 Statutory Rate Seed — EPS / Higher-Pension / PT (SRS-6-47, BR-CPF-017): the statutory rates
 * held as effective-dated rule versions on the rule engine. The Rule Administrator at Headquarters
 * proposes a version from the effective date of its governing order (M-25) and the Rule Approver at
 * Headquarters activates it, never the proposer (the M-08 routes). A rate without an approved version
 * is not configured: no value is assumed (R-RULE). The income-tax rates (slabs, surcharge, cess, the
 * no-PAN rate) and the PT slabs are rules of this register too; the income-tax sections and the PT
 * states are its masters (Module 08, plan 08 D-2).
 */
export function StatutoryRates() {
  const { t } = useI18n();
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sub2">{t('m18.hint')}</span>
      </div>
      <RuleCatalogue register="M-18" />
      <SectionMaster />
      <PtStateMaster />
    </div>
  );
}
