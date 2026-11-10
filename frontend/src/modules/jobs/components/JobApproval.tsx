'use client';

import { useState } from 'react';
import { Card, errorMessage, ReasonDialog, useToast } from '@/shared/ui';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import type { Decision, Job } from '../api/types';
import { useJobDecision } from '../orchestration/jobQueries';

/**
 * Maker-checker of a submitted job. While the job is AWAITING_APPROVAL it is in the Payroll
 * Checker's inbox (W-01) and here: a checker of the unit who is not the maker approves it (it enters
 * the batch queue) or rejects / returns it with a reason. Everyone else sees who it is waiting for.
 * The server decides (separation of duties, role, scope).
 */
export function JobApproval({ job }: { job: Job }) {
  const { t } = useI18n();
  const toast = useToast();
  const decision = useJobDecision(job);
  const [asking, setAsking] = useState<Decision | null>(null);

  if (job.status !== 'AWAITING_APPROVAL' || job.approvalId === null) return null;
  const approvalId = job.approvalId;

  async function decide(choice: Decision, reason: string) {
    try {
      await decision.mutateAsync({ approvalId, decision: choice, reason });
      toast(t(choice === 'APPROVE' ? 'c13.approval.approved' : 'c13.approval.rejected', [job.id]));
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      toast(errorMessage(t, cause));
    }
  }

  return (
    <Card title={t('c13.approval.title')}>
      <div className="bd">
        <p className="sub2">
          {t(job.canDecide ? 'c13.approval.yours' : 'c13.approval.waiting', [job.requestedBy])}
        </p>
        {job.canDecide && (
          <div className="rf-actbar">
            <button type="button" className="btn d" onClick={() => setAsking('REJECT')}>
              {t('c13.approval.reject')}
            </button>
            <button type="button" className="btn pri" onClick={() => setAsking('APPROVE')}>
              {t('c13.approval.approve')}
            </button>
          </div>
        )}
      </div>
      {asking && (
        <ReasonDialog
          title={t(asking === 'APPROVE' ? 'c13.approval.confirmApprove' : 'c13.approval.confirmReject', [
            job.id,
          ])}
          confirmLabel={t(asking === 'APPROVE' ? 'c13.approval.approve' : 'c13.approval.reject')}
          required={asking !== 'APPROVE'}
          onCancel={() => setAsking(null)}
          onConfirm={(reason) => {
            const chosen = asking;
            setAsking(null);
            void decide(chosen, reason);
          }}
        />
      )}
    </Card>
  );
}
