'use client';

import { type FormEvent, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions, useReference } from '@/shared/reference';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import {
  Card,
  date,
  errorMessage,
  formText,
  Problem,
  ReasonDialog,
  Src,
  StatusPill,
  useLocalPage,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import type { Group } from '../api/adminApi';
import { LOCATION_TYPE, scopesFor } from '../business/accessRules';
import { useCreateGroup, useEndGroup, useGroups, useOrg, useRoleMatrix } from '../orchestration/adminQueries';

/**
 * A-03 Access Groups: role ∩ scope. A new group is maker-checker at Headquarters with an OTP and
 * grants nothing until approved; only a Headquarters group may be a consolidated (read-only) view.
 */
export function AccessGroups() {
  const { t } = useI18n();
  const reference = useReference();
  const toast = useToast();
  const me = useMe();
  const hqBlock = hqOnlyBlock(me.data, 'A-03');
  const groups = useGroups();
  // The groups also feed the A-04 group choice, so they arrive whole; the table shows a page of them.
  const shown = useLocalPage(groups.data ?? [], '');
  const roles = useRoleMatrix(can(me.data, 'A-02', 'VIEW'));
  const org = useOrg();
  const createGroup = useCreateGroup();
  const endGroup = useEndGroup();
  const [creating, setCreating] = useState(false);
  const [scopeType, setScopeType] = useState<string>('DIVISION');
  const [ending, setEnding] = useState<Group | null>(null);
  const [error, setError] = useState<unknown>(null);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const target = Number(form.get('scope'));
    setError(null);
    try {
      const group = await createGroup.mutateAsync({
        code: formText(form, 'code'),
        name: formText(form, 'name'),
        roleId: Number(form.get('roleId')),
        scopeType,
        consolidated: form.get('consolidated') === 'on',
        accessMode: formText(form, 'accessMode', 'FULL'),
        scopes: scopesFor(scopeType, target),
        reason: formText(form, 'reason'),
      });
      setCreating(false);
      toast(t('a03.submitted', [group.code]));
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  async function end(group: Group, reason: string) {
    try {
      await endGroup.mutateAsync({ groupId: group.id, reason });
      toast(t('a03.ended', [group.code]));
    } catch (cause) {
      toast(errorMessage(t, cause));
    }
  }

  const scopeChoices =
    scopeType === 'ZONE'
      ? (org.data?.zones ?? []).map((z) => ({ id: z.id, label: `${z.name} (${z.code})` }))
      : (org.data?.locations ?? [])
          .filter((l) => l.type === LOCATION_TYPE[scopeType])
          .map((l) => ({ id: l.id, label: l.label }));

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        {can(me.data, 'A-03', 'CREATE') && (
          <button
            type="button"
            disabled={hqBlock !== null}
            title={hqBlock ? t(hqBlock) : undefined}
            className="btn pri"
            onClick={() => setCreating(true)}
          >
            {t('action.newAccessGroup')}
          </button>
        )}
        <span className="sub2">{t('a03.hint')}</span>
      </div>
      <Problem error={groups.error ?? error} />
      {creating && (
        <Card title={t('action.newAccessGroup')}>
          <form className="bd" onSubmit={create}>
            <div className="rf-grid">
              <div className="rf-field">
                <label htmlFor="g-code">{t('a03.code')}</label>
                <input id="g-code" name="code" maxLength={40} required />
              </div>
              <div className="rf-field">
                <label htmlFor="g-name">{t('a03.name')}</label>
                <input id="g-name" name="name" maxLength={200} required />
              </div>
              <div className="rf-field">
                <label htmlFor="g-role">{t('a03.role')}</label>
                <select id="g-role" name="roleId" required>
                  {(roles.data?.roles ?? []).map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.code} — {r.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="rf-field">
                <label htmlFor="g-type">{t('a03.scopeType')}</label>
                <select id="g-type" value={scopeType} onChange={(e) => setScopeType(e.target.value)}>
                  <ReferenceOptions list="SCOPE_TYPE" />
                </select>
              </div>
              {scopeType !== 'GLOBAL' && (
                <div className="rf-field">
                  <label htmlFor="g-scope">{t('a03.scope')}</label>
                  <select id="g-scope" name="scope" required>
                    {scopeChoices.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.label}
                      </option>
                    ))}
                  </select>
                </div>
              )}
              <div className="rf-field">
                <label htmlFor="g-mode">{t('a03.accessMode')}</label>
                <select id="g-mode" name="accessMode" defaultValue="FULL">
                  <ReferenceOptions list="ACCESS_MODE" />
                </select>
                <span className="hint">{t('a03.accessModeHint')}</span>
              </div>
              {(scopeType === 'HEADQUARTERS' || scopeType === 'GLOBAL') && (
                <div className="rf-field">
                  <label htmlFor="g-cons">
                    <input id="g-cons" name="consolidated" type="checkbox" /> {t('a03.consolidated')}
                  </label>
                  <span className="hint">{t('a03.consolidatedHint')}</span>
                </div>
              )}
              <div className="rf-field span2">
                <label htmlFor="g-reason">{t('common.reason')}</label>
                <TextAreaField id="g-reason" name="reason" maxLength={500} required />
              </div>
            </div>
            <p className="fl-hint">{t('a03.otpNote')}</p>
            <div className="rf-actbar">
              <button type="button" className="btn" onClick={() => setCreating(false)}>
                {t('common.cancel')}
              </button>
              <button type="submit" className="btn pri">
                {t('common.submitForApproval')}
              </button>
            </div>
          </form>
        </Card>
      )}
      <Card title={t('a03.title', [groups.data?.length ?? 0])}>
        <div className="wrap">
          <table className="t">
            <thead>
              <tr>
                <th>
                  {t('a03.code')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('a03.name')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('a03.role')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('a03.scopeType')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('a03.scope')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('a03.effective')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('a03.approval')}
                  <Src s="SYS" />
                </th>
                <th aria-label={t('common.actions')} />
              </tr>
            </thead>
            <tbody>
              {shown.rows.map((g) => (
                <tr key={g.id}>
                  <td>
                    <b>{g.code}</b>
                    {g.consolidated && <span className="pill p-info">{t('a03.readOnlyView')}</span>}
                    {g.accessMode && g.accessMode !== 'FULL' && (
                      <span className="pill p-info">{reference.label('ACCESS_MODE', g.accessMode)}</span>
                    )}
                  </td>
                  <td>{g.name}</td>
                  <td>{g.roleCode ?? '—'}</td>
                  <td>{reference.label('SCOPE_TYPE', g.scopeType)}</td>
                  <td>{g.scopes.map((s) => s.label ?? s.code ?? s.id).join(', ') || t('scope.GLOBAL')}</td>
                  <td>
                    {/* SRS l.1131 (UI_BMAD_g1 #81): dates as DD-MM-YYYY. */}
                    {date(g.effectiveFrom)}
                    {g.effectiveTo ? ` → ${date(g.effectiveTo)}` : ''}
                  </td>
                  <td>
                    <StatusPill status={g.approvalStatus} />
                  </td>
                  <td>
                    <div className="acts">
                      <HistoryButton entity="access_group" recordKey={g.code} />
                      {can(me.data, 'A-03', 'EDIT') && !g.effectiveTo && (
                        <button
                          type="button"
                          disabled={hqBlock !== null}
                          title={hqBlock ? t(hqBlock) : undefined}
                          className="ia d"
                          onClick={() => setEnding(g)}
                        >
                          {t('a03.end')}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {shown.pager}
      </Card>
      {ending && (
        <ReasonDialog
          title={t('a03.endTitle', [ending.code])}
          onCancel={() => setEnding(null)}
          onConfirm={(reason) => {
            const group = ending;
            setEnding(null);
            void end(group, reason);
          }}
        />
      )}
    </div>
  );
}
