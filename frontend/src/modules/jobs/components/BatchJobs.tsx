'use client';

import { useEffect, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { jobAccess } from '../business/jobRules';
import { useMe } from '@/shared/session';
import { dateTime, errorMessage, ReasonDialog, useToast } from '@/shared/ui';
import { useHaltCommand, useHaltState } from '../orchestration/jobQueries';
import { JobDetailView } from './JobDetailView';
import { JobHistory } from './JobHistory';
import { SubmitJobForm } from './SubmitJobForm';

type View = { kind: 'monitor' } | { kind: 'submit' } | { kind: 'detail'; id: number };
type HaltAction = 'HALT' | 'RELEASE';

/**
 * C-13 Batch Jobs (Payroll Cycle Control group, linked from C-01/C-02): submit, monitor and control
 * batch jobs. The route is guarded on the server (page redirect without C-13 VIEW) and by the
 * permission-filtered menu; this guard is the last line in the browser, and each action is checked
 * again (Submit = C-13 CREATE, commands = C-13 EDIT, the kill switch = C-13 HALT at Headquarters).
 * The backend authorises every call.
 *
 * <p>The kill switch (plan 05j): while batch processing is halted every viewer sees the banner (since
 * when, by whom, why; a release awaiting its approval). The System Administrator at Headquarters
 * halts at once with a reason, and asks for the release, which a Payroll Checker at HQ decides.
 */
export function BatchJobs() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const access = jobAccess(me.data);
  const halt = useHaltState();
  const haltCommand = useHaltCommand();
  const [view, setView] = useState<View>({ kind: 'monitor' });
  // A screen that asks for a job (C-10 / C-11 "Run in background") opens the submit form.
  useEffect(() => {
    if (new URLSearchParams(window.location.search).has('job')) setView({ kind: 'submit' });
  }, []);
  const [asking, setAsking] = useState<HaltAction | null>(null);

  if (!access.view) {
    return (
      <div className="denied" role="alert">
        <div className="big">🔒</div>
        <h2>{t('denied.title')}</h2>
        <p>{t('denied.text', ['C-13'])}</p>
      </div>
    );
  }

  const engaged = halt.data?.engaged === true;
  const atHeadquarters = me.data?.atHeadquarters === true;

  async function sendHalt(action: HaltAction, reason: string) {
    try {
      await haltCommand.mutateAsync({ action, reason });
      toast(t(action === 'HALT' ? 'c13.halt.done' : 'c13.halt.releaseRequested'));
    } catch (cause) {
      toast(errorMessage(t, cause));
    }
  }

  return (
    <div className="wireframe-body-container">
      {engaged && halt.data && (
        <p className="fl-error" role="alert" data-testid="batch-halt">
          <b>{t('c13.halt.title')}</b>{' '}
          {t('c13.halt.banner', [dateTime(halt.data.haltedAt), halt.data.haltedBy, halt.data.reason])}
          {halt.data.releaseApprovalId !== null && (
            <>
              {' '}
              <span className="sub2">
                {t('c13.halt.releasePending', [halt.data.releaseRequestedBy, halt.data.releaseApprovalId])}
              </span>
            </>
          )}
        </p>
      )}
      <div className="toolbar" role="tablist" aria-label={t('c13.tabs')}>
        <button
          type="button"
          role="tab"
          aria-selected={view.kind !== 'submit'}
          className={`btn${view.kind !== 'submit' ? ' pri' : ''}`}
          onClick={() => setView({ kind: 'monitor' })}
        >
          {t('c13.tab.monitor')}
        </button>
        {access.submit && (
          <button
            type="button"
            role="tab"
            aria-selected={view.kind === 'submit'}
            className={`btn${view.kind === 'submit' ? ' pri' : ''}`}
            onClick={() => setView({ kind: 'submit' })}
          >
            {t('c13.tab.submit')}
          </button>
        )}
        <span className="sub2">{t('c13.hint')}</span>
        {access.halt && !engaged && (
          <button
            type="button"
            className="btn d"
            disabled={!atHeadquarters}
            title={atHeadquarters ? undefined : t('error.AUTHZ-403-HQ-ONLY')}
            onClick={() => setAsking('HALT')}
          >
            {t('c13.halt.button')}
          </button>
        )}
        {access.halt && engaged && halt.data?.releaseApprovalId === null && (
          <button
            type="button"
            className="btn"
            disabled={!atHeadquarters}
            title={atHeadquarters ? undefined : t('error.AUTHZ-403-HQ-ONLY')}
            onClick={() => setAsking('RELEASE')}
          >
            {t('c13.halt.release')}
          </button>
        )}
      </div>
      {view.kind === 'submit' && access.submit && (
        <SubmitJobForm onSubmitted={(id) => setView({ kind: 'detail', id })} />
      )}
      {view.kind === 'monitor' && <JobHistory onOpen={(id) => setView({ kind: 'detail', id })} />}
      {view.kind === 'detail' && (
        <JobDetailView id={view.id} access={access} onBack={() => setView({ kind: 'monitor' })} />
      )}
      {asking && (
        <ReasonDialog
          title={t(asking === 'HALT' ? 'c13.halt.confirm' : 'c13.halt.releaseConfirm')}
          note={t(asking === 'HALT' ? 'c13.halt.note' : 'c13.halt.releaseNote')}
          confirmLabel={t(asking === 'HALT' ? 'c13.halt.button' : 'c13.halt.release')}
          onCancel={() => setAsking(null)}
          onConfirm={(reason) => {
            const action = asking;
            setAsking(null);
            void sendHalt(action, reason);
          }}
        />
      )}
    </div>
  );
}
