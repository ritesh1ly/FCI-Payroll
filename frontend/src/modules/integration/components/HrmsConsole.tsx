'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { Problem, TextAreaField } from '@/shared/ui';
import type { HrmsCallResult } from '../api/integrationApi';
import { outcomeTone, parseBody, parseQuery, reasonError, runBlocked } from '../business/consoleRules';
import { ADMIN_ONLY } from '../business/integrationRules';
import { useCallHrmsApi, useHrmsApiOptions } from '../orchestration/integrationQueries';

/**
 * X-03 HRMS API console (SRS-INT-6, SRS-INT-33): the Administrator (X-03 EDIT) calls one configured
 * outbound HRMS API with a query, a body and a stated reason; the backend audits the call and answers
 * with the outcome and the masked data. Every other role sees the card disabled with the reason.
 * A disabled console answers 403 INT-403-CONSOLE-DISABLED, shown as the problem it is.
 */
export function HrmsConsole({ isAdmin }: { isAdmin: boolean }) {
  const { t } = useI18n();
  const apis = useHrmsApiOptions();
  const call = useCallHrmsApi();
  const [code, setCode] = useState('');
  const [query, setQuery] = useState('{}');
  const [body, setBody] = useState('');
  const [reason, setReason] = useState('');
  const [result, setResult] = useState<HrmsCallResult | null>(null);
  const [error, setError] = useState<unknown>(null);

  const q = parseQuery(query);
  const b = parseBody(body);
  const reasonProblem = reason === '' ? null : reasonError(reason);
  const blocked = isAdmin ? runBlocked({ code, query, body, reason }) : ADMIN_ONLY;
  const options = apis.data?.items ?? [];

  async function run() {
    if (!q.ok || !b.ok) return;
    setError(null);
    setResult(null);
    try {
      setResult(await call.mutateAsync({ code, query: q.value, body: b.value, reason: reason.trim() }));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="card fl-mt" data-testid="x03-hrms-console">
      <div className="hd">
        <h3>{t('x03.console.title')}</h3>
        {/* i18n-ignore: requirement references, not labels */}
        <span className="frs">SRS-INT-6</span>
      </div>
      <p className="fl-hint">{t('x03.console.hint')}</p>
      <fieldset disabled={!isAdmin} className="fl-field" title={isAdmin ? undefined : t(ADMIN_ONLY)}>
        <label className="fl-field">
          {t('x03.console.api')}
          <select aria-label={t('x03.console.api')} value={code} onChange={(e) => setCode(e.target.value)}>
            <option value="">{t('x03.console.pick')}</option>
            {options.map((r) => (
              <option key={r.code} value={r.code}>
                {`${t(`x03.hrmsApi.${r.code}`)} (${r.method} ${r.path ?? ''})`}
              </option>
            ))}
          </select>
        </label>
        {apis.data && options.length === 0 && <p className="fl-hint">{t('x03.console.noApis')}</p>}
        <label className="fl-field">
          {t('x03.console.query')}
          <TextAreaField
            name="query"
            aria-label={t('x03.console.query')}
            maxLength={4000}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
        {!q.ok && (
          <p className="fl-error" role="alert">
            {t(q.error)}
          </p>
        )}
        <label className="fl-field">
          {t('x03.console.body')}
          <TextAreaField
            name="body"
            aria-label={t('x03.console.body')}
            maxLength={100000}
            value={body}
            onChange={(e) => setBody(e.target.value)}
          />
        </label>
        {!b.ok && (
          <p className="fl-error" role="alert">
            {t(b.error)}
          </p>
        )}
        <label className="fl-field">
          {t('x03.console.reason')}
          <input
            aria-label={t('x03.console.reason')}
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        {reasonProblem && (
          <p className="fl-error" role="alert">
            {t(reasonProblem)}
          </p>
        )}
        <div className="fl-actions">
          <button
            type="button"
            className={`btn pri${blocked ? ' off' : ''}`}
            aria-label={t('x03.console.run')}
            disabled={blocked !== null || call.isPending}
            title={blocked ? t(blocked) : undefined}
            onClick={() => void run()}
          >
            {call.isPending ? t('x03.console.running') : t('x03.console.run')}
          </button>
        </div>
      </fieldset>
      <Problem error={apis.error ?? error} />
      {result && <ConsoleResult result={result} />}
    </div>
  );
}

/** The answer of one call: the outcome pill, the figures, the problems and the masked data. */
function ConsoleResult({ result }: { result: HrmsCallResult }) {
  const { t } = useI18n();
  return (
    <section aria-label={t('x03.console.result')} data-testid="x03-hrms-console-result">
      <h4>{t('x03.console.result')}</h4>
      <p>
        <span className={`pill ${outcomeTone(result.outcome)}`}>
          {t(`x03.console.outcome.${result.outcome}`)}
        </span>{' '}
        {result.duplicate && <span className="pill p-warn">{t('x03.console.duplicate')}</span>}
      </p>
      <dl className="fl-dl">
        <dt>{t('x03.console.httpStatus')}</dt>
        <dd>{result.httpStatus ?? '—'}</dd>
        <dt>{t('x03.console.errorCode')}</dt>
        <dd>{result.errorCode ?? '—'}</dd>
        <dt>{t('x03.console.message')}</dt>
        <dd>{result.message ?? '—'}</dd>
        <dt>{t('x03.console.attempts')}</dt>
        <dd>{result.attempts}</dd>
        <dt>{t('x03.console.elapsed')}</dt>
        <dd>{result.elapsedMillis}</dd>
      </dl>
      {result.problems.length > 0 && (
        <>
          <h5>{t('x03.console.problems')}</h5>
          <ul aria-label={t('x03.console.problems')}>
            {result.problems.map((p, i) => (
              <li key={`${i}-${p}`}>{p}</li>
            ))}
          </ul>
        </>
      )}
      {result.data !== null && (
        <>
          <h5>{t('x03.console.data')}</h5>
          <pre aria-label={t('x03.console.data')}>{result.data}</pre>
        </>
      )}
    </section>
  );
}
