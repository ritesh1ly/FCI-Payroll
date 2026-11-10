'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import type { Parameter } from '../api/parametersApi';
import { parameterKeys, parameterPages, useProposeParameter } from '../orchestration/parameterQueries';
import { Card, date, Problem, ServerPagination, Src, useToast, TextAreaField } from '@/shared/ui';
import { RetentionPeriods } from './RetentionPeriods';

/**
 * A-07 System Parameters (ADM-SYS-001): the security and session policy as approved, versioned data.
 * A change is proposed with a reason and an OTP and takes effect only when a Headquarters Payroll
 * Checker approves it; values are bounded by hard limits the server enforces. A setting the sources
 * give no value for is shown as not configured until its first value is approved.
 */
export function SystemParameters() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const hqBlock = hqOnlyBlock(me.data, 'A-07');
  const proposal = useProposeParameter();
  const [editing, setEditing] = useState<Parameter | null>(null);
  const [value, setValue] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const mayEdit = can(me.data, 'A-07', 'EDIT');

  function show(p: Parameter, v: number): string {
    if (p.valueType === 'BOOLEAN') return v === 1 ? t('common.yes') : t('common.no');
    return p.unit ? `${v} ${t(`a07.unit.${p.unit}`)}` : String(v);
  }

  async function propose(editing: Parameter) {
    setError(null);
    try {
      await proposal.mutateAsync({ code: editing.code, value: Number(value), reason });
      setEditing(null);
      toast(t('a07.submitted'));
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  const groups = ['SECURITY', 'SESSION', 'PAYROLL'];
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sub2">{t('a07.hint')}</span>
      </div>
      <Problem error={error} />
      <ServerPagination queryKey={parameterKeys.all} fetchPage={parameterPages.list} personal={false}>
        {(settings) => (
          <>
            {groups.map((group) => {
              const rows = settings.filter((p) => p.group === group);
              if (rows.length === 0) return null;
              return (
                <Card key={group} title={t(`a07.group.${group}`)}>
                  <div className="wrap">
                    <table className="t">
                      <thead>
                        <tr>
                          <th>
                            {t('a07.parameter')}
                            <Src s="SYS" />
                          </th>
                          <th>
                            {t('a07.value')}
                            <Src s="RULE" />
                          </th>
                          <th>
                            {t('a07.range')}
                            <Src s="RULE" />
                          </th>
                          <th>
                            {t('a07.version')}
                            <Src s="SYS" />
                          </th>
                          <th>
                            {t('a07.pending')}
                            <Src s="SYS" />
                          </th>
                          <th aria-label={t('common.actions')} />
                        </tr>
                      </thead>
                      <tbody>
                        {rows.map((p) => (
                          <tr key={p.code}>
                            <td>
                              <b>{t(`a07.param.${p.code}`)}</b>
                              <div className="sub2">
                                {p.code}
                                {!p.enforced && ` · ${t('a07.notEnforced')}`}
                              </div>
                            </td>
                            <td>
                              {p.value === null ? (
                                <span className="pill p-bad">{t('a07.notConfigured')}</span>
                              ) : (
                                show(p, p.value)
                              )}
                            </td>
                            <td>{p.valueType === 'BOOLEAN' ? '—' : `${p.minValue} – ${p.maxValue}`}</td>
                            <td>
                              {p.version === null ? (
                                '—'
                              ) : (
                                <>
                                  v{p.version}
                                  <div className="sub2">
                                    {date(p.effectiveFrom)} · {p.changedBy}
                                  </div>
                                </>
                              )}
                            </td>
                            <td>
                              {p.pending ? (
                                <span className="pill p-warn" title={p.pending.reason ?? undefined}>
                                  {t('a07.pendingValue', [
                                    show(p, p.pending.value),
                                    p.pending.requestedBy,
                                    date(p.pending.effectiveFrom),
                                  ])}
                                </span>
                              ) : (
                                '—'
                              )}
                            </td>
                            <td>
                              <div className="acts">
                                {/* SRS-20-29: every master row opens its change history. */}
                                <HistoryButton
                                  entity="rule_version"
                                  recordKey={p.code}
                                  label={t(`a07.param.${p.code}`)}
                                />
                                {mayEdit && !p.pending && (
                                  <button
                                    disabled={hqBlock !== null}
                                    title={hqBlock ? t(hqBlock) : undefined}
                                    type="button"
                                    className="ia"
                                    onClick={() => {
                                      setEditing(p);
                                      setValue(p.value === null ? '' : String(p.value));
                                      setReason('');
                                    }}
                                  >
                                    {t('action.editParameter')}
                                  </button>
                                )}
                              </div>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </Card>
              );
            })}
          </>
        )}
      </ServerPagination>
      <RetentionPeriods />
      {editing && (
        <Card title={t('a07.editTitle', [t(`a07.param.${editing.code}`)])}>
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="a07-value">{t('a07.newValue')}</label>
              {editing.valueType === 'BOOLEAN' ? (
                <select id="a07-value" value={value} onChange={(e) => setValue(e.target.value)}>
                  <option value="1">{t('common.yes')}</option>
                  <option value="0">{t('common.no')}</option>
                </select>
              ) : (
                <input
                  id="a07-value"
                  type="number"
                  min={editing.minValue}
                  max={editing.maxValue}
                  value={value}
                  onChange={(e) => setValue(e.target.value)}
                />
              )}
            </div>
            <div className="rf-field span2">
              <label htmlFor="a07-reason">{t('common.reason')}</label>
              <TextAreaField
                id="a07-reason"
                value={reason}
                maxLength={500}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
          </div>
          <p className="fl-hint">{t('a07.otpNote')}</p>
          <div className="rf-actbar">
            <button type="button" className="btn" onClick={() => setEditing(null)}>
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={!reason.trim() || value === ''}
              onClick={() => void propose(editing)}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
    </div>
  );
}
