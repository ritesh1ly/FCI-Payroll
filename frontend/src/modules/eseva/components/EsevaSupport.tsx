'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, Empty, Problem, ServerPagination, Src, amount, dateTime, TextAreaField } from '@/shared/ui';
import type { Preview, SubmissionFilter, SubmissionStatus, SubmissionType } from '../api/esevaApi';
import { REASON_MAX, SUBMISSION_STATUSES, SUBMISSION_TYPES, previewReady } from '../business/esevaRules';
import { esevaKeys, submissionsPage, useClosePreview, usePreview } from '../orchestration/esevaQueries';

function month(yymm: string): string {
  return yymm.length === 6 ? `${yymm.slice(4)}/${yymm.slice(0, 4)}` : yymm;
}

/**
 * E-02 eSeva Support & Submission Log (plan 14 phases 14a / 14b; SRS-17-6, SRS-17-8, SRS-17-10,
 * SRS-17-18; D14-1, D14-5). Payroll staff list the self-service submissions of their offices with
 * their approval status (members never see this log), and open a member's eSeva view for support:
 * each look is recorded with the reason and shown on X-04; the view is masked and read-only, every
 * submit action disabled.
 */
export function EsevaSupport() {
  const { t } = useI18n();
  const me = useMe();
  const [eis, setEis] = useState('');
  const [type, setType] = useState<SubmissionType | ''>('');
  const [status, setStatus] = useState<SubmissionStatus | ''>('');
  const filter: SubmissionFilter = {
    eis: eis.trim() || undefined,
    type: type || undefined,
    status: status || undefined,
  };
  const maySupport = can(me.data, 'E-02', 'SUPPORT');

  return (
    <div className="wireframe-body-container">
      {maySupport && <SupportPreview />}
      <Card title={t('e02.log')}>
        <div className="toolbar">
          <label>
            {t('m01.eis')} <input maxLength={20} value={eis} onChange={(e) => setEis(e.target.value)} />
          </label>
          <label>
            {t('e02.type')}{' '}
            <select value={type} onChange={(e) => setType(e.target.value as SubmissionType | '')}>
              <option value="">{t('e02.allTypes')}</option>
              {SUBMISSION_TYPES.map((s) => (
                <option key={s} value={s}>
                  {t(`e02.type.${s}`)}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('e02.status')}{' '}
            <select value={status} onChange={(e) => setStatus(e.target.value as SubmissionStatus | '')}>
              <option value="">{t('e02.allStatuses')}</option>
              {SUBMISSION_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {t(`e02.status.${s}`)}
                </option>
              ))}
            </select>
          </label>
        </div>
        <ServerPagination
          queryKey={esevaKeys.submissions(filter)}
          fetchPage={submissionsPage(filter)}
          empty={<Empty text={t('e02.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('e02.log')}>
              <thead>
                <tr>
                  <th>
                    {t('e02.member')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('e02.type')}
                    <Src s="ESS" />
                  </th>
                  <th>
                    {t('e02.value')}
                    <Src s="ESS" />
                  </th>
                  <th>
                    {t('e02.submittedAt')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('e02.status')}
                    <Src s="SYS" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} data-reference={r.reference}>
                    <td>
                      {r.eis} {r.member}
                      {r.office && <div className="sub2">{r.office}</div>}
                    </td>
                    <td>{t(`e02.type.${r.type}`)}</td>
                    <td>{r.value ?? '—'}</td>
                    <td>{dateTime(r.submittedAt)}</td>
                    <td>{t(`e02.status.${r.status}`)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </Card>
    </div>
  );
}

/** SRS-17-6 / SRS-17-8: one recorded, read-only look at a member's eSeva view. */
function SupportPreview() {
  const { t } = useI18n();
  const preview = usePreview();
  const close = useClosePreview();
  const [eis, setEis] = useState('');
  const [reason, setReason] = useState('');
  const [shown, setShown] = useState<Preview | null>(null);
  const [error, setError] = useState<unknown>(null);
  const ready = previewReady(eis, reason);

  async function open() {
    setError(null);
    setShown(null);
    try {
      setShown(await preview.mutateAsync({ eis: eis.trim(), reason: reason.trim() }));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('e02.preview')}>
      <p className="sub2">{t('e02.previewNote')}</p>
      <div className="toolbar">
        <label>
          {t('m01.eis')} <input maxLength={20} value={eis} onChange={(e) => setEis(e.target.value)} />
        </label>
        <label>
          {t('common.reason')}{' '}
          <TextAreaField maxLength={REASON_MAX} value={reason} onChange={(e) => setReason(e.target.value)} />
        </label>
        <button
          type="button"
          className="btn primary"
          disabled={!ready || preview.isPending}
          title={ready ? undefined : t('e02.why.preview')}
          onClick={() => void open()}
        >
          {t('action.e02.preview')}
        </button>
      </div>
      <Problem error={error} />
      {shown && (
        <section aria-label={t('e02.previewOf', [shown.member.eis])}>
          <p className="fl-hint" role="status">
            {t('e02.readOnly', [String(shown.sessionId)])}
          </p>
          <button
            type="button"
            className="btn"
            disabled={close.isPending}
            onClick={() =>
              void close
                .mutateAsync(shown.sessionId)
                .then(() => setShown(null))
                .catch((cause: unknown) => setError(cause))
            }
          >
            {t('action.e02.closePreview')}
          </button>
          <dl className="kv">
            <dt>{t('e02.member')}</dt>
            <dd>
              {shown.member.eis} {shown.member.name}
            </dd>
            <dt>{t('e02.office')}</dt>
            <dd>{shown.member.office ?? '—'}</dd>
            <dt>{t('m01.pan')}</dt>
            <dd>{shown.member.panMasked ?? '—'}</dd>
            <dt>{t('e02.aadhaar')}</dt>
            <dd>{shown.member.aadhaarMasked ?? '—'}</dd>
            <dt>{t('e02.bankAccount')}</dt>
            <dd>{shown.member.bankAccountMasked ?? '—'}</dd>
            <dt>{t('e02.mobile')}</dt>
            <dd>{shown.member.mobileMasked ?? '—'}</dd>
            <dt>{t('e02.email')}</dt>
            <dd>{shown.member.emailMasked ?? '—'}</dd>
          </dl>
          <h4>{t('e02.payslips')}</h4>
          {shown.payslipMonths.length === 0 ? (
            <Empty text={t('e02.noPayslips')} />
          ) : (
            <table className="t" aria-label={t('e02.payslips')}>
              <thead>
                <tr>
                  <th>
                    {t('e02.month')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('e02.gross')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('e02.deductions')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('e02.net')}
                    <Src s="CALC" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {shown.payslipMonths.map((m) => (
                  <tr key={m.yymm}>
                    <td>{month(m.yymm)}</td>
                    <td className="num">{amount(m.gross)}</td>
                    <td className="num">{amount(m.deductions)}</td>
                    <td className="num">{amount(m.net)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <h4>{t('e02.submissions')}</h4>
          {shown.submissions.length === 0 ? (
            <Empty text={t('e02.none')} />
          ) : (
            <ul className="fl-list">
              {shown.submissions.map((s, i) => (
                <li key={`${s.submittedAt}-${i}`}>
                  {t(`e02.type.${s.type}`)} - {t(`e02.status.${s.status}`)} ({dateTime(s.submittedAt)})
                </li>
              ))}
            </ul>
          )}
          <button type="button" className="btn" disabled title={t('e02.why.submit')}>
            {t('action.e02.submit')}
          </button>
        </section>
      )}
    </Card>
  );
}
