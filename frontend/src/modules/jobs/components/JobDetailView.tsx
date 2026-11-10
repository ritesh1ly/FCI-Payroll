'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { ABANDON_AWAITING_APPROVAL, formatDuration, type JobAccess, progress } from '../business/jobRules';
import { useJobDetail, useSkippedItems } from '../orchestration/jobQueries';
import { Card, dateTime, Empty, Pager, Problem, Src, usePageSizes } from '@/shared/ui';
import { JobActions } from './JobActions';
import { JobApproval } from './JobApproval';
import { JobStatusBadge } from './JobStatusBadge';

/**
 * C-13 Job Detail: the request, each attempt (first run, resumes, restarts) with step-level progress
 * and counts, the failure log of skipped records, the control commands and the audit trail.
 * Refreshed every 3 s while the job is pending or running, or a command is pending.
 */
export function JobDetailView({ id, access, onBack }: { id: number; access: JobAccess; onBack: () => void }) {
  const { t } = useI18n();
  const reference = useReference();
  const { defaultSize } = usePageSizes();
  const [skipPage, setSkipPage] = useState(0);
  const [skipSize, setSkipSize] = useState(defaultSize);
  const detail = useJobDetail(id);
  const skipped = useSkippedItems(id, skipPage, skipSize, (detail.data?.skippedTotal ?? 0) > 0);

  if (detail.isLoading) return <p className="sub2">{t('c13.loading')}</p>;
  if (detail.isError || !detail.data) {
    return (
      <>
        <button type="button" className="btn" onClick={onBack}>
          ← {t('c13.back')}
        </button>
        <Problem error={detail.error} />
      </>
    );
  }
  const { job, attempts, controls, audit, skippedTotal } = detail.data;

  return (
    <>
      <div className="toolbar">
        <button type="button" className="btn" onClick={onBack}>
          ← {t('c13.back')}
        </button>
        <JobActions job={job} access={access} />
      </div>
      <Card title={t('c13.detail.title', [job.id, reference.label('JOB_TYPE', job.jobType)])}>
        <dl className="bd kv">
          <div>
            <dt>{t('c13.col.status')}</dt>
            <dd>
              <JobStatusBadge status={job.status} />{' '}
              {job.pendingCommand && (
                <span className="sub2">
                  {t(
                    job.pendingCommand === ABANDON_AWAITING_APPROVAL
                      ? 'c13.abandonPending'
                      : 'c13.commandPending',
                  )}
                </span>
              )}
            </dd>
          </div>
          <div>
            <dt>{t('c13.col.params')}</dt>
            <dd>
              {Object.entries(job.params)
                .filter(([k]) => k !== 'locationId')
                .map(([k, v]) => `${t(`c13.param.${k}`)} ${v}`)
                .join(' · ')}
              {job.locationLabel ? ` · ${job.locationLabel}` : ''}
            </dd>
          </div>
          <div>
            <dt>{t('c13.col.requestedBy')}</dt>
            <dd>
              {job.requestedBy} · {dateTime(job.requestedAt)} · {t('c13.priority')} {job.priority}
            </dd>
          </div>
          <div>
            <dt>{t('c13.col.start')}</dt>
            <dd>
              {dateTime(job.startedAt)} → {dateTime(job.finishedAt)} ({formatDuration(job.durationSeconds)})
            </dd>
          </div>
          <div>
            <dt>{t('c13.col.counts')}</dt>
            <dd>{t('c13.counts', [job.readCount, job.writeCount, job.skipCount])}</dd>
          </div>
          <div>
            <dt>{t('c13.attempts')}</dt>
            <dd>{job.attemptNo}</dd>
          </div>
          {job.resultSummary && (
            <div>
              <dt>{t('c13.summary')}</dt>
              <dd>{job.resultSummary}</dd>
            </div>
          )}
          {job.errorMessage && (
            <div>
              <dt>{t('c13.col.error')}</dt>
              <dd className="fl-error">{job.errorMessage}</dd>
            </div>
          )}
        </dl>
      </Card>

      <JobApproval job={job} />

      {attempts.length === 0 && <Empty text={t('c13.detail.notStarted')} />}
      {attempts.map((attempt, index) => (
        <Card
          key={attempt.jobExecutionId}
          title={t('c13.attempt', [attempts.length - index, attempt.jobExecutionId, attempt.status])}
        >
          <div className="wrap">
            <table className="t">
              <thead>
                <tr>
                  <th>
                    {t('c13.step')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c13.col.status')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c13.progress')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c13.read')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c13.written')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c13.skipped')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c13.commits')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c13.col.start')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c13.col.end')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c13.exit')}
                    <Src s="SYS" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {attempt.steps.map((step) => {
                  const pct = progress(step);
                  return (
                    <tr key={step.name}>
                      <td>
                        {step.partition ? (
                          <span className="sub2">↳ {step.name.split(':')[1]}</span>
                        ) : (
                          <b>{step.name}</b>
                        )}
                      </td>
                      <td>{t(`c13.step.${step.status}`)}</td>
                      <td>
                        {pct === null ? (
                          '—'
                        ) : (
                          <progress max={100} value={pct} aria-label={t('c13.progressOf', [step.name, pct])}>
                            {pct}%
                          </progress>
                        )}
                      </td>
                      <td>
                        {step.readCount}
                        {step.plannedItems !== null ? ` / ${step.plannedItems}` : ''}
                      </td>
                      <td>{step.writeCount}</td>
                      <td>{step.readSkipCount + step.processSkipCount + step.writeSkipCount}</td>
                      <td>
                        {step.commitCount}
                        {step.rollbackCount > 0 ? ` (${t('c13.rollbacks', [step.rollbackCount])})` : ''}
                      </td>
                      <td>{dateTime(step.startTime)}</td>
                      <td>{dateTime(step.endTime)}</td>
                      <td className="sub2" title={step.exitDescription || undefined}>
                        {step.exitCode}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
      ))}

      <Card title={t('c13.failureLog', [skippedTotal])}>
        {skippedTotal === 0 ? (
          <Empty text={t('c13.failureLog.none')} />
        ) : (
          <>
            <Problem error={skipped.error} />
            <div className="wrap">
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('c13.step')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('c13.phase')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('c13.item')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('c13.col.error')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('c13.at')}
                      <Src s="SYS" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {(skipped.data?.items ?? []).map((s) => (
                    <tr key={s.id}>
                      <td>{s.step}</td>
                      <td>{s.phase}</td>
                      <td>{s.itemKey ?? '—'}</td>
                      <td>
                        {s.errorMessage ?? '—'}
                        <div className="sub2">{s.errorClass}</div>
                      </td>
                      <td>{dateTime(s.at)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager
              page={skipPage}
              size={skipSize}
              total={skippedTotal}
              busy={skipped.isFetching}
              onPage={setSkipPage}
              onSize={(n) => {
                setSkipSize(n);
                setSkipPage(0);
              }}
            />
          </>
        )}
      </Card>

      <Card title={t('c13.commands', [controls.length])}>
        {controls.length === 0 ? (
          <Empty text={t('c13.commands.none')} />
        ) : (
          <div className="wrap">
            <table className="t">
              <thead>
                <tr>
                  <th>
                    {t('c13.command')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('c13.col.status')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c13.col.requestedBy')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('common.reason')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('c13.outcome')}
                    <Src s="SYS" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {controls.map((c) => (
                  <tr key={c.id}>
                    <td>{t(`c13.command.${c.command}`)}</td>
                    <td>{t(`c13.controlStatus.${c.status}`)}</td>
                    <td>
                      {c.requestedBy}
                      <div className="sub2">{dateTime(c.requestedAt)}</div>
                    </td>
                    <td>{c.reason}</td>
                    <td>
                      {c.outcome ?? '—'}
                      {c.appliedBy && <div className="sub2">{c.appliedBy}</div>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card title={t('c13.audit', [audit.length])}>
        <div className="wrap">
          <table className="t">
            <thead>
              <tr>
                <th>
                  {t('c13.at')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('c13.event')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('c13.actor')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('c13.transition')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('c13.detail')}
                  <Src s="SYS" />
                </th>
              </tr>
            </thead>
            <tbody>
              {audit.map((a) => (
                <tr key={a.id}>
                  <td>{dateTime(a.at)}</td>
                  <td>{a.event}</td>
                  <td>{a.actor}</td>
                  <td>
                    {a.fromStatus || a.toStatus ? `${a.fromStatus ?? '—'} → ${a.toStatus ?? '—'}` : '—'}
                  </td>
                  <td className="sub2">{a.detail ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  );
}
