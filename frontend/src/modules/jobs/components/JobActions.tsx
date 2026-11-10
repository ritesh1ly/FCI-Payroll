'use client';

import { useState } from 'react';
import { errorMessage, ReasonDialog, useToast } from '@/shared/ui';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import type { Job, JobCommand } from '../api/types';
import { commandBlocker, commandEnabled, JOB_COMMANDS, type JobAccess } from '../business/jobRules';
import { useAbandonJob, useHaltState, useJobCommand } from '../orchestration/jobQueries';

/**
 * Pause / Resume / Stop / Restart of one job, and Abandon (plan 05j: a proposal a Payroll Checker
 * decides, for a failed, stopped or paused job that nobody wants to continue). A button is enabled
 * only when the session may control jobs, nothing is pending on the job and the server lists the
 * command as valid for the job's state; while batch processing is halted the server withholds
 * Resume and Restart. Disabled buttons say why. Every command asks for a reason in a confirmation
 * dialog; Abandon states its consequence.
 */
export function JobActions({ job, access }: { job: Job; access: JobAccess }) {
  const { t } = useI18n();
  const reference = useReference();
  const toast = useToast();
  const command = useJobCommand(job);
  const abandon = useAbandonJob(job);
  const halt = useHaltState();
  const halted = halt.data?.engaged === true;
  const [asking, setAsking] = useState<JobCommand | null>(null);

  async function send(next: JobCommand, reason: string) {
    try {
      if (next === 'ABANDON') {
        await abandon.mutateAsync(reason);
        toast(t('c13.abandon.requested', [job.id]));
      } else {
        await command.mutateAsync({ command: next, reason });
        toast(t('c13.commandSent', [t(`c13.command.${next}`), job.id]));
      }
    } catch (cause) {
      toast(errorMessage(t, cause));
    }
  }

  function why(candidate: JobCommand): string | undefined {
    const blocker = commandBlocker(job, candidate, access, halted);
    if (blocker === null) return undefined;
    return blocker === 'state'
      ? t('c13.why.state', [reference.label('JOB_STATUS', job.status)])
      : t(`c13.why.${blocker}`);
  }

  function button(candidate: JobCommand, className: string) {
    const enabled = commandEnabled(job, candidate, access);
    return (
      <button
        key={candidate}
        type="button"
        className={className}
        disabled={!enabled}
        title={enabled ? undefined : why(candidate)}
        onClick={() => setAsking(candidate)}
      >
        {t(`c13.command.${candidate}`)}
      </button>
    );
  }

  return (
    <div className="acts" role="group" aria-label={t('c13.actionsFor', [job.id])}>
      {JOB_COMMANDS.map((candidate) => button(candidate, `btn${candidate === 'STOP' ? ' d' : ''}`))}
      {button('ABANDON', 'btn d')}
      {asking && (
        <ReasonDialog
          title={
            asking === 'ABANDON'
              ? t('c13.abandon.confirm', [job.id])
              : t('c13.confirmCommand', [t(`c13.command.${asking}`), job.id])
          }
          note={asking === 'ABANDON' ? t('c13.abandon.note') : undefined}
          confirmLabel={t(`c13.command.${asking}`)}
          onCancel={() => setAsking(null)}
          onConfirm={(reason) => {
            const chosen = asking;
            setAsking(null);
            void send(chosen, reason);
          }}
        />
      )}
    </div>
  );
}
