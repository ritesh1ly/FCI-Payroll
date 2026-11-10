'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, Empty, Problem, ServerPagination, Src, date, useToast, TextAreaField } from '@/shared/ui';
import type { VersionProposal } from '../api/reportApi';
import { LAYOUT_FORMATS, proposalBlocked, toVersionRequest } from '../business/reportRules';
import { definitionsPage, reportKeys, usePropose, useVersions } from '../orchestration/reportQueries';
import { ModelEditor } from './ModelEditor';

/**
 * M-12 Version history, behind the drawn button (plan 15 phase 15c; SRS-18-9, SRS-18-10, SRS-G-12;
 * BR-MIS-002, BR-MIS-003): the definitions and their versions; the Report Administrator proposes a new
 * version (format, period lock, store mode, blocked with the reason, effective from), the Rule Approver
 * approves it in the Approvals Inbox. A closed period keeps the version effective then. initial: the
 * report to open on (Edit definition, a row's Edit).
 */
export function DefinitionVersions({ initial = null }: { initial?: string | null }) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const [selected, setSelected] = useState<string | null>(initial);
  const versions = useVersions(selected);
  const propose = usePropose();
  const [error, setError] = useState<unknown>(null);
  const [form, setForm] = useState<VersionProposal>(emptyProposal);
  const mayPropose = can(me.data, 'M-12', 'CREATE');
  const blocked = mayPropose ? proposalBlocked(form, businessToday()) : 'reason.m12.reportAdminOnly';

  async function send(selected: string) {
    setError(null);
    try {
      await propose.mutateAsync({ key: selected, body: toVersionRequest(form) });
      toast(t('m12.proposed'));
      // Review 15-13: a proposal made clears the form.
      setForm(emptyProposal());
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <Card title={t('screen.M-12')}>
        <ServerPagination
          queryKey={reportKeys.definitions()}
          fetchPage={definitionsPage()}
          empty={<Empty text={t('o04.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('screen.M-12')}>
              <thead>
                <tr>
                  <th>
                    {t('o04.report')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m12.version')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('o04.status')}
                    <Src s="SYS" />
                  </th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.key}>
                    <td>
                      {r.name}
                      <div className="sub2">{r.key}</div>
                    </td>
                    <td>{r.versionNo ?? '—'}</td>
                    <td>{t(`o04.status.${r.status}`, [r.producingScreen])}</td>
                    <td>
                      <button
                        type="button"
                        className="btn"
                        onClick={() => {
                          // Review 15-13: another report opens with an empty proposal.
                          if (r.key !== selected) setForm(emptyProposal());
                          setSelected(r.key);
                        }}
                      >
                        {t('m12.versions')}
                      </button>
                      <HistoryButton entity="report_catalogue_version" recordKey={r.key} label={r.name} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </Card>
      {selected && (
        <Card title={t('m12.versionsOf', [selected])}>
          <Problem error={versions.error} />
          {versions.data && (
            <table className="t" aria-label={t('m12.versionsOf', [selected])}>
              <thead>
                <tr>
                  <th>
                    {t('m12.version')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('m12.effective')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m12.format')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m12.model')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m12.state')}
                    <Src s="SYS" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {versions.data.map((v) => (
                  <tr key={v.id}>
                    <td>{v.versionNo}</td>
                    <td>
                      {date(v.effectiveFrom)} - {v.effectiveTo ? date(v.effectiveTo) : t('m12.open')}
                    </td>
                    <td>{v.layoutFormat ?? '—'}</td>
                    <td>
                      {t('m12.modelCounts', [
                        (v.parameters ?? []).length,
                        (v.fields ?? []).length,
                        (v.controls ?? []).length,
                        (v.distribution ?? []).length,
                      ])}
                      {v.roundingRuleKey && <div className="sub2">{v.roundingRuleKey}</div>}
                    </td>
                    <td>
                      {t(`m12.state.${v.approvalState}`)}
                      {v.blocked && <div className="sub2">{v.blockedReason}</div>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <h4>{t('m12.newVersion')}</h4>
          <div className="toolbar">
            <label>
              {t('m12.format')}{' '}
              <select
                value={form.layoutFormat ?? ''}
                onChange={(e) => setForm({ ...form, layoutFormat: e.target.value || null })}
              >
                {LAYOUT_FORMATS.map((f) => (
                  <option key={f} value={f}>
                    {f || '—'}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('m12.effective')}{' '}
              <input
                type="date"
                value={form.effectiveFrom}
                onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })}
              />
            </label>
            <label>
              <input
                type="checkbox"
                checked={form.periodLockRequired}
                onChange={(e) => setForm({ ...form, periodLockRequired: e.target.checked })}
              />{' '}
              {t('m12.periodLock')}
            </label>
            <label>
              <input
                type="checkbox"
                checked={form.blocked}
                onChange={(e) => setForm({ ...form, blocked: e.target.checked })}
              />{' '}
              {t('m12.blocked')}
            </label>
            {form.blocked && (
              <label>
                {t('m12.blockedReason')}{' '}
                <TextAreaField
                  maxLength={500}
                  value={form.blockedReason ?? ''}
                  onChange={(e) => setForm({ ...form, blockedReason: e.target.value })}
                />
              </label>
            )}
          </div>
          <ModelEditor form={form} setForm={setForm} />
          <div className="toolbar">
            <button
              type="button"
              className="btn primary"
              disabled={blocked !== null || propose.isPending}
              title={blocked ? t(blocked) : undefined}
              onClick={() => void send(selected)}
            >
              {t('action.m12.propose')}
            </button>
          </div>
          <Problem error={error} />
        </Card>
      )}
    </div>
  );
}

/** A version proposal as it opens: nothing chosen, effective today (SRS-18-10). */
function emptyProposal(): VersionProposal {
  return {
    layoutFormat: null,
    periodLockRequired: false,
    storeMode: 'REGENERATE',
    blocked: false,
    blockedReason: null,
    effectiveFrom: businessToday(),
  };
}
