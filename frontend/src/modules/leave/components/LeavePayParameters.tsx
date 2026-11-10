'use client';

import { useEffect, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, date, Empty, Problem, ServerPagination, Src, useToast, TextAreaField } from '@/shared/ui';
import {
  draftProblem,
  LEAVE_PARAMETERS,
  numeric,
  PAY_EFFECTS,
  type ParameterDraft,
  perType,
} from '../business/leaveRules';
import {
  leaveKeys,
  leavePages,
  useLeaveCirculars,
  useProposeLeaveParameter,
} from '../orchestration/leaveQueries';

const EMPTY: ParameterDraft = {
  code: 'LWP_DIVISOR',
  leaveType: '',
  value: '',
  effectiveFrom: '',
  reason: '',
};

/**
 * M-20 Leave Encashment Parameters (plan 19 phase 19b; SRS-LV-14, SRS M-20 field table). Every version
 * of every parameter with its status; the Administrator (the Rule Administrator at Headquarters)
 * proposes a new version from a date with a reason, and it is read only once the Payroll Checker
 * approves it on W-01; a returned version is never used. No value is pre-filled: each is set as in the
 * current system (R-RULE).
 */
export function LeavePayParameters({ supersede = null }: { supersede?: { code: string } | null } = {}) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const propose = useProposeLeaveParameter();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<ParameterDraft>(EMPTY);
  const [error, setError] = useState<unknown>(null);
  const [circularId, setCircularId] = useState('');
  const mayCreate = can(me.data, 'M-20', 'CREATE');
  const circulars = useLeaveCirculars(mayCreate);
  // The form only offers known codes; LWP_DIVISOR stands in for an unknown one.
  const kind = LEAVE_PARAMETERS[draft.code] ?? 'DIVISOR';
  const problem = draftProblem(draft);
  // The drawn row's Edit opens the new version of that parameter here.
  useEffect(() => {
    if (supersede) {
      setDraft({ ...EMPTY, code: supersede.code });
      setOpen(true);
    }
  }, [supersede]);

  async function submit() {
    setError(null);
    const value = draft.value.trim();
    const isNumber = numeric(kind, value);
    try {
      await propose.mutateAsync({
        code: draft.code,
        leaveType: draft.leaveType.trim().toUpperCase() || null,
        number: isNumber ? Number(value) : null,
        text: isNumber ? null : value,
        effectiveFrom: draft.effectiveFrom,
        reason: draft.reason.trim(),
        circularId: circularId === '' ? null : Number(circularId),
      });
      toast(t('m20.submitted', [draft.code]));
      setDraft(EMPTY);
      setCircularId('');
      setOpen(false);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        {mayCreate ? (
          <button type="button" className="btn primary" onClick={() => setOpen(true)} disabled={open}>
            {t('m20.newVersion')}
          </button>
        ) : (
          <button type="button" className="btn" disabled title={t('reason.ruleAdminOnly')}>
            {t('m20.newVersion')}
          </button>
        )}
      </div>
      <p className="sub2">{t('m20.note')}</p>
      {open && (
        <Card title={t('m20.newVersion')}>
          <Problem error={error} />
          <div className="rf-grid">
            <div className="rf-field">
              <label htmlFor="m20-code">{t('m20.parameter')}</label>
              <select
                id="m20-code"
                value={draft.code}
                onChange={(e) => setDraft({ ...draft, code: e.target.value, value: '', leaveType: '' })}
              >
                {Object.keys(LEAVE_PARAMETERS).map((c) => (
                  <option key={c} value={c}>
                    {t(`m20.param.${c}`)}
                  </option>
                ))}
              </select>
            </div>
            {perType(kind) && (
              <div className="rf-field">
                <label htmlFor="m20-type">{t('m20.leaveType')}</label>
                <input
                  id="m20-type"
                  maxLength={15}
                  value={draft.leaveType}
                  onChange={(e) => setDraft({ ...draft, leaveType: e.target.value })}
                />
              </div>
            )}
            <div className="rf-field">
              <label htmlFor="m20-value">{t('m20.value')}</label>
              {kind === 'PAY_EFFECT' ? (
                <select
                  id="m20-value"
                  value={draft.value}
                  onChange={(e) => setDraft({ ...draft, value: e.target.value })}
                >
                  <option value="">—</option>
                  {PAY_EFFECTS.map((p) => (
                    <option key={p} value={p}>
                      {t(`lv.effect.${p}`)}
                    </option>
                  ))}
                </select>
              ) : (
                <input
                  id="m20-value"
                  maxLength={200}
                  value={draft.value}
                  onChange={(e) => setDraft({ ...draft, value: e.target.value })}
                />
              )}
              <div className="sub2">{t(`m20.hint.${kind}`)}</div>
            </div>
            <div className="rf-field">
              <label htmlFor="m20-from">{t('m20.effectiveFrom')}</label>
              <input
                id="m20-from"
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="m20-circular">{t('m20.circular')}</label>
              <select id="m20-circular" value={circularId} onChange={(e) => setCircularId(e.target.value)}>
                <option value="">{t('m20.noCircular')}</option>
                {(circulars.data ?? []).map((c) => (
                  <option key={c.id} value={String(c.id)}>
                    {c.number}
                    {c.subject ? ` - ${c.subject}` : ''}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="m20-reason">{t('common.reason')}</label>
              <TextAreaField
                id="m20-reason"
                maxLength={500}
                value={draft.reason}
                onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
              />
            </div>
          </div>
          <div className="toolbar">
            <button
              type="button"
              className="btn primary"
              disabled={problem !== null || propose.isPending}
              title={problem ? t(problem) : undefined}
              onClick={() => void submit()}
            >
              {t('common.submitForApproval')}
            </button>
            <button type="button" className="btn" onClick={() => setOpen(false)}>
              {t('common.cancel')}
            </button>
          </div>
        </Card>
      )}
      <Card title={t('screen.M-20')}>
        <ServerPagination
          queryKey={leaveKeys.versions(undefined)}
          fetchPage={leavePages.versions(undefined)}
          empty={<Empty text={t('m20.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('screen.M-20')}>
              <thead>
                <tr>
                  <th>
                    {t('m20.parameter')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m20.value')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m20.effectiveFrom')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m20.status')}
                    <Src s="SYS" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {rows.map((v) => (
                  <tr key={v.id} data-reference={`${v.code}|${v.leaveType ?? ''}|${v.effectiveFrom}`}>
                    <td>
                      {t(`m20.param.${v.code}`)}
                      {v.leaveType && <div className="sub2">{v.leaveType}</div>}
                    </td>
                    <td>
                      {v.code === 'PAY_EFFECT' && v.text
                        ? t(`lv.effect.${v.text}`)
                        : (v.text ?? v.number ?? '—')}
                    </td>
                    <td>
                      {date(v.effectiveFrom)}
                      {v.effectiveTo && ` – ${date(v.effectiveTo)}`}
                      <div className="sub2">v{v.versionNo}</div>
                    </td>
                    <td>
                      {t(`m20.status.${v.status}`)}
                      {v.circularNumber && <div className="sub2">{v.circularNumber}</div>}
                    </td>
                    <td>
                      {v.status === 'ACTIVE' && (
                        <button
                          type="button"
                          className="btn sm"
                          disabled={!mayCreate || open}
                          title={!mayCreate ? t('reason.ruleAdminOnly') : undefined}
                          onClick={() => {
                            setDraft({ ...EMPTY, code: v.code, leaveType: v.leaveType ?? '' });
                            setCircularId('');
                            setError(null);
                            setOpen(true);
                          }}
                        >
                          {t('m20.supersede')}
                        </button>
                      )}
                      <HistoryButton
                        entity="leave_pay_parameter"
                        recordKey={v.leaveType ? `${v.code} / ${v.leaveType}` : v.code}
                        label={
                          v.leaveType
                            ? `${t(`m20.param.${v.code}`)} / ${v.leaveType}`
                            : t(`m20.param.${v.code}`)
                        }
                      />
                    </td>
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
