'use client';

import type { ReactNode } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { useToast } from './Toast';
import { withBase } from './basePath';
import { Act, Explained } from './wireframe';

const SYSTEM_ADMINISTRATOR = 'SYSTEM_ADMINISTRATOR';

/**
 * Who may do what on a rate master as Wireframes v6.1 draws it and the SRS rules it. The pack's
 * Administrator draws the entry objects enabled; the SRS maker is the Rule Administrator (V033_3,
 * Q-04-1) and the System Administrator makes no payroll entry, so that role sees them as drawn and is
 * told why when it presses them (WF-208; rights are never widened to match a drawing).
 */
export function useMasterRights(screen: string) {
  const { t } = useI18n();
  const me = useMe();
  const mayCreate = can(me.data, screen, 'CREATE');
  const sysAdmin = me.data?.roles?.includes(SYSTEM_ADMINISTRATOR) === true;
  const hqBlock = hqOnlyBlock(me.data, screen);
  return {
    mayCreate,
    /** The entry objects are drawn enabled for this role. */
    drawn: mayCreate || sysAdmin,
    checker: can(me.data, screen, 'APPROVE'),
    mayExport: can(me.data, screen, 'EXPORT'),
    /** Why the drawn entry cannot be used by this role, or null when it can. */
    entryWhy: hqBlock ? t(hqBlock) : mayCreate ? null : t('mst.why.notMaker'),
  };
}

/** The closed section that keeps a screen's full register: every function the drawing leaves out. */
export function RegisterSection({
  label,
  open,
  onToggle,
  id,
  children,
}: {
  label: string;
  open: boolean;
  onToggle: (open: boolean) => void;
  id: string;
  children: ReactNode;
}) {
  return (
    <details
      className="fl-mt"
      data-testid={id}
      open={open}
      onToggle={(e) => onToggle((e.currentTarget as HTMLDetailsElement).open)}
    >
      <summary>{label}</summary>
      {children}
    </details>
  );
}

/** ✔ of a row: the decision is taken in W-01; a record with nothing waiting says so. */
export function useApproveRow() {
  const { t } = useI18n();
  const toast = useToast();
  return (pending: boolean) => {
    if (pending) window.location.assign(withBase('/payroll/?s=W-01'));
    else toast(t('mst.nothingWaiting'));
  };
}

/**
 * ✔ of a row of a master the Payroll Checker views and the Rule Approver decides (M-05, M-06: the pack draws ✔ for the
 * Checker, the SRS route names the Rule Approver, WF-249): the approver goes to W-01, the Checker is told who decides.
 * Undefined for any other role: no ✔ is drawn.
 */
export function useRouteApprove(screen: string) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const approve = useApproveRow();
  if (can(me.data, screen, 'APPROVE')) return approve;
  return me.data?.roles?.includes('PAYROLL_CHECKER') ? () => toast(t('mst.why.ruleApprover')) : undefined;
}

/** "🧪 Simulate": the what-if run of C-09 (SRS-19-4); a role without it is told why. */
export function SimulateAct({ label }: { label: string }) {
  const { t } = useI18n();
  const me = useMe();
  return can(me.data, 'C-09', 'VIEW') ? (
    <Act label={label} href={withBase('/payroll/?s=C-09')} />
  ) : (
    <Explained label={label} why={t('mst.why.simulate')} />
  );
}
