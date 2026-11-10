'use client';

import { type FormEvent, useEffect, useState } from 'react';
import { Card, ConfirmDialog, errorMessage, Problem, useToast, TextAreaField } from '@/shared/ui';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import type { JobDef, JobTypes } from '../api/types';
import { defaultPriority, finYearOf, finYears, priorityChoices, validateParams } from '../business/jobRules';
import { useJobTypes, useSubmitJob } from '../orchestration/jobQueries';

/** Defaults of a job's parameters: periods = current period, unit = the first unit offered. */
function defaults(next: JobDef, period: string, locationId?: number): Record<string, string> {
  const v: Record<string, string> = {};
  for (const p of next.params) {
    if (p.kind === 'PERIOD') v[p.name] = period;
    if (p.kind === 'FIN_YEAR' && period) v[p.name] = finYearOf(period);
    if (p.kind === 'LOCATION' && locationId) v[p.name] = String(locationId);
  }
  return v;
}

/**
 * The job a screen asked for in the address (C-10 / C-11 "Run in background":
 * `?s=C-13&job=TAX_FORM16_PART_B&finYear=2025-26`): its type and the values of its year,
 * quarter and period parameters. The unit is never taken from the address; every value is checked
 * on review as if typed.
 */
export function requestedJob(
  search: string,
  types: readonly JobDef[],
): { def: JobDef; values: Record<string, string> } | null {
  const query = new URLSearchParams(search);
  const def = types.find((d) => d.type === query.get('job'));
  if (!def) return null;
  const values: Record<string, string> = {};
  for (const p of def.params) {
    const v = query.get(p.name);
    if (v && p.kind !== 'LOCATION') values[p.name] = v.slice(0, 20);
  }
  return { def, values };
}

/**
 * C-13 Submit Batch Job: job type, its parameters (unit, periods), priority; validated on the client
 * with the server's rules, confirmed in a dialog, then queued. The batch instance picks it up.
 */
export function SubmitJobForm({ onSubmitted }: { onSubmitted: (id: number) => void }) {
  const { t } = useI18n();
  const reference = useReference();
  const toast = useToast();
  const types = useJobTypes();
  const submitJob = useSubmitJob();
  const [jobType, setJobType] = useState('');
  const [values, setValues] = useState<Record<string, string>>({});
  const [priority, setPriority] = useState(5);
  const [reason, setReason] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);

  const data: JobTypes | undefined = types.data;
  const def: JobDef | undefined = data?.types.find((d) => d.type === jobType) ?? data?.types[0];

  // Once the definitions arrive: preselect the type a screen asked for, else the first one, with
  // sensible defaults.
  useEffect(() => {
    const first = data?.types[0];
    if (data && first && jobType === '') {
      const asked = requestedJob(window.location.search, data.types);
      const chosen = asked?.def ?? first;
      setJobType(chosen.type);
      setPriority(defaultPriority(chosen));
      setValues({
        ...defaults(chosen, data.currentPeriod, data.locations[0]?.id),
        ...asked?.values,
      });
    }
  }, [data, jobType]);

  function review(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!def || !data) return;
    const found = validateParams(def, values, data.currentPeriod);
    if (!reason.trim()) found.reason = 'c13.error.required';
    setErrors(found);
    if (Object.keys(found).length === 0) setConfirming(true);
  }

  async function submit() {
    if (!def) return;
    setBusy(true);
    setFailure(null);
    try {
      const job = await submitJob.mutateAsync({
        jobType: def.type,
        params: values,
        priority,
        reason: reason.trim(),
      });
      setConfirming(false);
      toast(t('c13.submitted', [job.id]));
      onSubmitted(job.id);
    } catch (cause) {
      setConfirming(false);
      setFailure(cause);
      if (!(cause instanceof ApiError)) toast(errorMessage(t, cause));
    } finally {
      setBusy(false);
    }
  }

  const locationLabel = (id: string) => data?.locations.find((l) => String(l.id) === id)?.label ?? id;

  if (types.isLoading) return <p className="sub2">{t('c13.loading')}</p>;
  return (
    <>
      <Problem error={types.error ?? failure} />
      {data && def && (
        <Card title={t('c13.submit.title')}>
          <form className="bd" onSubmit={review} noValidate>
            <div className="rf-grid">
              <div className="rf-field">
                <label htmlFor="job-type">{t('c13.jobType')}</label>
                <select
                  id="job-type"
                  value={def.type}
                  onChange={(e) => {
                    const next = data.types.find((d) => d.type === e.target.value);
                    setJobType(e.target.value);
                    setErrors({});
                    setPriority(defaultPriority(next));
                    if (next) setValues(defaults(next, data.currentPeriod, data.locations[0]?.id));
                  }}
                >
                  {data.types.map((d) => (
                    <option key={d.type} value={d.type}>
                      {reference.label('JOB_TYPE', d.type)}
                    </option>
                  ))}
                </select>
                <span className="fl-hint">{t(`c13.typeHint.${def.type}`)}</span>
              </div>
              {def.params.map((p) => (
                <div className="rf-field" key={p.name}>
                  <label htmlFor={`job-${p.name}`}>
                    {t(`c13.param.${p.name}`)}
                    {p.required ? ' *' : ''}
                  </label>
                  {p.kind === 'LOCATION' ? (
                    <select
                      id={`job-${p.name}`}
                      value={values[p.name] ?? ''}
                      aria-invalid={Boolean(errors[p.name])}
                      aria-describedby={errors[p.name] ? `job-${p.name}-err` : undefined}
                      onChange={(e) => setValues({ ...values, [p.name]: e.target.value })}
                    >
                      <option value="">{t('c13.pickUnit')}</option>
                      {data.locations.map((l) => (
                        <option key={l.id} value={l.id}>
                          {l.label}
                        </option>
                      ))}
                    </select>
                  ) : p.kind === 'FIN_YEAR' || p.kind === 'QUARTER' ? (
                    <select
                      id={`job-${p.name}`}
                      value={values[p.name] ?? ''}
                      aria-invalid={Boolean(errors[p.name])}
                      aria-describedby={errors[p.name] ? `job-${p.name}-err` : undefined}
                      onChange={(e) => setValues({ ...values, [p.name]: e.target.value })}
                    >
                      <option value="">{t('c13.pickValue')}</option>
                      {(p.kind === 'FIN_YEAR' ? finYears(data.currentPeriod) : ['Q1', 'Q2', 'Q3', 'Q4']).map(
                        (o) => (
                          <option key={o} value={o}>
                            {o}
                          </option>
                        ),
                      )}
                    </select>
                  ) : (
                    <input
                      id={`job-${p.name}`}
                      type="month"
                      max={data.currentPeriod}
                      value={values[p.name] ?? ''}
                      aria-invalid={Boolean(errors[p.name])}
                      aria-describedby={errors[p.name] ? `job-${p.name}-err` : undefined}
                      onChange={(e) => setValues({ ...values, [p.name]: e.target.value })}
                    />
                  )}
                  {errors[p.name] && (
                    <span id={`job-${p.name}-err`} className="fl-error" role="alert">
                      {t(errors[p.name] as string)}
                    </span>
                  )}
                </div>
              ))}
              <div className="rf-field">
                <label htmlFor="job-priority">{t('c13.priority')}</label>
                <select
                  id="job-priority"
                  value={priority}
                  aria-describedby="job-priority-hint"
                  onChange={(e) => setPriority(Number(e.target.value))}
                >
                  {priorityChoices(def).map((p) => (
                    <option key={p} value={p}>
                      {t(`c13.priority.${p}`)}
                    </option>
                  ))}
                </select>
                <span id="job-priority-hint" className="hint">
                  {t('c13.priority.hint')}
                </span>
              </div>
              <div className="rf-field span2">
                <label htmlFor="job-reason">{t('common.reason')} *</label>
                <TextAreaField
                  id="job-reason"
                  maxLength={500}
                  value={reason}
                  aria-invalid={Boolean(errors.reason)}
                  aria-describedby={errors.reason ? 'job-reason-err' : undefined}
                  onChange={(e) => setReason(e.target.value)}
                />
                {errors.reason && (
                  <span id="job-reason-err" className="fl-error" role="alert">
                    {t(errors.reason)}
                  </span>
                )}
              </div>
            </div>
            <p className="fl-hint">{t('c13.submit.note')}</p>
            <div className="rf-actbar">
              <button type="submit" className="btn pri">
                {t('c13.submit.review')}
              </button>
            </div>
          </form>
        </Card>
      )}
      {confirming && def && (
        <ConfirmDialog
          title={t('c13.submit.confirmTitle')}
          confirmLabel={t('c13.submit.confirm')}
          busy={busy}
          onCancel={() => setConfirming(false)}
          onConfirm={() => void submit()}
        >
          <dl className="kv">
            <div>
              <dt>{t('c13.jobType')}</dt>
              <dd>{reference.label('JOB_TYPE', def.type)}</dd>
            </div>
            {def.params.map((p) => (
              <div key={p.name}>
                <dt>{t(`c13.param.${p.name}`)}</dt>
                <dd>{p.kind === 'LOCATION' ? locationLabel(values[p.name] ?? '') : values[p.name]}</dd>
              </div>
            ))}
            <div>
              <dt>{t('c13.priority')}</dt>
              <dd>{t(`c13.priority.${priority}`)}</dd>
            </div>
            <div>
              <dt>{t('common.reason')}</dt>
              <dd>{reason}</dd>
            </div>
          </dl>
          <p className="fl-hint">{t('c13.submit.confirmNote')}</p>
        </ConfirmDialog>
      )}
    </>
  );
}
