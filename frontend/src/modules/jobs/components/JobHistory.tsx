'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions, useReference } from '@/shared/reference';
import { type JobFilter, type JobStatus, type SortField } from '../api/types';
import { formatDuration } from '../business/jobRules';
import { useJobList } from '../orchestration/jobQueries';
import { Card, dateTime, Empty, Pager, Problem, Src, usePageSizes } from '@/shared/ui';
import { JobStatusBadge } from './JobStatusBadge';

/**
 * C-13 Job Monitor / History: server-side paging, filtering and sorting over every job request the
 * session may see; refreshed every 5 s while a job on the page is still pending or running.
 */
export function JobHistory({ onOpen }: { onOpen: (id: number) => void }) {
  const { t } = useI18n();
  const reference = useReference();
  const { defaultSize } = usePageSizes();
  const [filter, setFilter] = useState<JobFilter>({
    page: 0,
    size: defaultSize,
    sort: 'requestedAt',
    direction: 'desc',
    status: '',
    jobType: '',
  });
  const list = useJobList(filter);
  const page = list.data;

  function update(change: Partial<JobFilter>) {
    setFilter((f) => ({ ...f, page: 0, ...change }));
  }

  function sortBy(field: SortField) {
    setFilter((f) => ({
      ...f,
      page: 0,
      sort: field,
      direction: f.sort === field && f.direction === 'desc' ? 'asc' : 'desc',
    }));
  }

  function header(field: SortField, label: string) {
    const active = filter.sort === field;
    return (
      <th aria-sort={active ? (filter.direction === 'asc' ? 'ascending' : 'descending') : 'none'}>
        <button type="button" className="sort" onClick={() => sortBy(field)}>
          {label} {active ? (filter.direction === 'asc' ? '▲' : '▼') : ''}
        </button>
        <Src s="SYS" />
      </th>
    );
  }

  return (
    <>
      <Card title={t('c13.filter.title')}>
        <div className="bd rf-grid">
          <div className="rf-field">
            <label htmlFor="jf-status">{t('c13.col.status')}</label>
            <select
              id="jf-status"
              value={filter.status}
              onChange={(e) => update({ status: e.target.value as JobStatus | '' })}
            >
              <option value="">{t('c13.filter.all')}</option>
              <ReferenceOptions list="JOB_STATUS" />
            </select>
          </div>
          <div className="rf-field">
            <label htmlFor="jf-type">{t('c13.jobType')}</label>
            <select id="jf-type" value={filter.jobType} onChange={(e) => update({ jobType: e.target.value })}>
              <option value="">{t('c13.filter.all')}</option>
              <ReferenceOptions list="JOB_TYPE" />
            </select>
          </div>
          <div className="rf-field">
            <label htmlFor="jf-from">{t('c13.filter.from')}</label>
            <input
              id="jf-from"
              type="date"
              value={filter.from ?? ''}
              onChange={(e) => update({ from: e.target.value })}
            />
          </div>
          <div className="rf-field">
            <label htmlFor="jf-to">{t('c13.filter.to')}</label>
            <input
              id="jf-to"
              type="date"
              value={filter.to ?? ''}
              onChange={(e) => update({ to: e.target.value })}
            />
          </div>
          <div className="rf-field">
            <label htmlFor="jf-by">{t('c13.col.requestedBy')}</label>
            <input
              id="jf-by"
              maxLength={60}
              value={filter.requestedBy ?? ''}
              onChange={(e) => update({ requestedBy: e.target.value })}
            />
          </div>
        </div>
      </Card>
      <Problem error={list.error} />
      <Card title={t('c13.history.title', [page?.total ?? 0])}>
        <div className="wrap" aria-busy={list.isFetching}>
          {list.isLoading ? (
            <p className="bd sub2">{t('c13.loading')}</p>
          ) : (
            <table className="t">
              <thead>
                <tr>
                  {header('id', t('c13.col.id'))}
                  {header('jobType', t('c13.jobType'))}
                  <th>
                    {t('c13.col.params')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('c13.col.requestedBy')}
                    <Src s="SYS" />
                  </th>
                  {header('status', t('c13.col.status'))}
                  {header('startedAt', t('c13.col.start'))}
                  {header('finishedAt', t('c13.col.end'))}
                  <th>
                    {t('c13.col.duration')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c13.col.counts')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c13.col.error')}
                    <Src s="SYS" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {(page?.items ?? []).map((job) => (
                  <tr key={job.id}>
                    <td>
                      <button
                        type="button"
                        className="link"
                        onClick={() => onOpen(job.id)}
                        aria-label={t('c13.open', [job.id])}
                      >
                        <b>{job.id}</b>
                      </button>
                      <div className="sub2">{t(`c13.source.${job.source}`)}</div>
                    </td>
                    <td>{reference.label('JOB_TYPE', job.jobType)}</td>
                    <td>
                      {Object.entries(job.params)
                        .filter(([k]) => k !== 'locationId')
                        .map(([k, v]) => `${t(`c13.param.${k}`)} ${v}`)
                        .join(' · ')}
                      {job.locationLabel && <div className="sub2">{job.locationLabel}</div>}
                    </td>
                    <td>{job.requestedBy}</td>
                    <td>
                      <JobStatusBadge status={job.status} />
                      {job.pendingCommand && <div className="sub2">{t('c13.commandPending')}</div>}
                    </td>
                    <td>{dateTime(job.startedAt)}</td>
                    <td>{dateTime(job.finishedAt)}</td>
                    <td>{formatDuration(job.durationSeconds)}</td>
                    <td>{t('c13.counts', [job.readCount, job.writeCount, job.skipCount])}</td>
                    <td className="sub2">{job.errorMessage ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {list.isSuccess && (page?.items.length ?? 0) === 0 && <Empty text={t('c13.history.none')} />}
        </div>
        <Pager
          page={filter.page}
          size={filter.size}
          total={page?.total ?? 0}
          busy={list.isFetching}
          onPage={(p) => setFilter((f) => ({ ...f, page: p }))}
          onSize={(s) => update({ size: s })}
        />
      </Card>
    </>
  );
}
