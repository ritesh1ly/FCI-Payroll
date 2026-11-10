'use client';

import { type FormEvent, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import {
  Card,
  dateTime,
  Problem,
  ReasonDialog,
  ServerPagination,
  Src,
  useDialogFocus,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import type { SuspendInput, Suspension } from '../api/suspensionApi';
import { suspensionKeys, suspensionPage, useSuspensionAction } from '../orchestration/suspensionQueries';

const PLAIN_TEXT = /^[\x20-\x7E\t\r\n]*$/;
const CHANNELS = ['ALL', 'BANK_FILE', 'EBS_AP_POSTING', 'APPS_REMITTANCE'] as const;

/**
 * O-02 Bank Advice & EBS Posting, Release Suspension part (plan 05 phase 05h; SRS-15-35, FRS BR-SEC-007 (SRS label BR-SEC-006, CLR-43)):
 * Finance at Headquarters stops the release of bank files and payment postings, for all units or a named
 * unit and a channel, with a reason, at once; it asks for the resumption, which the Payroll Checker at
 * Headquarters approves on W-01. On O-02 ({@code embedded}) it sits in a closed section of BankAdvice, whose drawn
 * Emergency stop opens the dialog; its own hint and button are left out there.
 */
export function ReleaseSuspensions({ embedded = false }: { embedded?: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const action = useSuspensionAction();
  const [suspending, setSuspending] = useState(false);
  const [resuming, setResuming] = useState<Suspension | null>(null);
  const [error, setError] = useState<unknown>(null);
  const atHq = me.data?.atHeadquarters === true;
  const hqOnly = atHq ? null : t('error.AUTHZ-403-HQ-ONLY');
  const maySuspend = can(me.data, 'O-02', 'SUSPEND');
  const mayResume = can(me.data, 'O-02', 'RESUME');

  async function run(input: Parameters<typeof action.mutateAsync>[0], done: string) {
    setError(null);
    try {
      await action.mutateAsync(input);
      toast(t(done));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      {!embedded && (
        <div className="toolbar">
          <span className="sub2">{t('o02.hint')}</span>
          {maySuspend && (
            <button
              type="button"
              className="btn d"
              disabled={hqOnly !== null}
              title={hqOnly ?? undefined}
              onClick={() => setSuspending(true)}
            >
              {t('o02.suspend')}
            </button>
          )}
        </div>
      )}
      <Problem error={error} />
      <Card title={t('o02.suspensions')}>
        <div className="wrap">
          <ServerPagination
            queryKey={suspensionKeys.list()}
            fetchPage={suspensionPage}
            personal={false}
            empty={<p className="fl-hint">{t('o02.none')}</p>}
          >
            {(rows) => (
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('o02.scope')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('o02.channel')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('o02.suspended')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('o02.resumed')}
                      <Src s="SYS" />
                    </th>
                    {mayResume && <th aria-label={t('common.actions')} />}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((s) => {
                    const why =
                      s.resumedAt !== null
                        ? t('o02.alreadyResumed')
                        : s.pendingApprovalId !== null
                          ? t('o02.pending', [s.pendingApprovalId])
                          : hqOnly;
                    return (
                      <tr key={s.id}>
                        <td>
                          <b>{s.scope === 'ALL_UNITS' ? t('o02.scope.ALL_UNITS') : s.unitCode}</b>
                          {s.resumedAt === null && <span className="pill p-bad">{t('o02.active')}</span>}
                        </td>
                        <td>{t(`o02.channel.${s.channel}`)}</td>
                        <td>
                          {s.suspendedBy}
                          <div className="sub2">{dateTime(s.suspendedAt)}</div>
                          <div className="sub2">{s.reason}</div>
                        </td>
                        <td>
                          {s.resumedBy ? (
                            <>
                              {s.resumedBy}
                              <div className="sub2">{dateTime(s.resumedAt)}</div>
                              <div className="sub2">{s.resumeReason}</div>
                            </>
                          ) : s.pendingApprovalId !== null ? (
                            t('o02.pending', [s.pendingApprovalId])
                          ) : (
                            '—'
                          )}
                        </td>
                        {mayResume && (
                          <td>
                            <button
                              type="button"
                              className="ia"
                              disabled={why !== null}
                              title={why ?? undefined}
                              onClick={() => setResuming(s)}
                            >
                              {t('o02.resume', [s.id])}
                            </button>
                          </td>
                        )}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </ServerPagination>
        </div>
      </Card>
      {suspending && (
        <SuspendDialog
          onCancel={() => setSuspending(false)}
          onConfirm={(body) => {
            setSuspending(false);
            void run({ kind: 'suspend', body }, 'o02.suspendedDone');
          }}
        />
      )}
      {resuming && (
        <ReasonDialog
          maxLength={1000}
          title={t('o02.resumeTitle', [resuming.id])}
          note={t('o02.resumeNote')}
          onCancel={() => setResuming(null)}
          onConfirm={(reason) => {
            const id = resuming.id;
            setResuming(null);
            void run({ kind: 'resume', id, reason }, 'o02.resumeRequested');
          }}
        />
      )}
    </div>
  );
}

/** SRS-15-35: what is suspended (all units or a named unit, a channel) and why. */
export function SuspendDialog({
  onConfirm,
  onCancel,
}: {
  onConfirm: (body: SuspendInput) => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLFormElement>();
  const [scope, setScope] = useState<SuspendInput['scope']>('ALL_UNITS');
  const [unit, setUnit] = useState('');
  const [channel, setChannel] = useState<SuspendInput['channel']>('ALL');
  const [reason, setReason] = useState('');
  const ready =
    (scope === 'ALL_UNITS' || unit.trim() !== '') && reason.trim() !== '' && PLAIN_TEXT.test(reason);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (ready) {
      onConfirm({
        scope,
        channel,
        reason: reason.trim(),
        ...(scope === 'UNIT' ? { unitCode: unit.trim() } : {}),
      });
    }
  }

  return (
    <div className="fl-modal-backdrop" role="presentation">
      <form
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="suspend-title"
        onSubmit={submit}
      >
        <h2 id="suspend-title">{t('o02.suspendTitle')}</h2>
        <p className="sub2">{t('o02.suspendNote')}</p>
        <label className="fl-field">
          {t('o02.scope')}
          <select
            name="scope"
            value={scope}
            onChange={(e) => setScope(e.target.value as SuspendInput['scope'])}
          >
            <option value="ALL_UNITS">{t('o02.scope.ALL_UNITS')}</option>
            <option value="UNIT">{t('o02.scope.UNIT')}</option>
          </select>
        </label>
        {scope === 'UNIT' && (
          <label className="fl-field">
            {t('o02.unitCode')}
            <input name="unitCode" maxLength={20} value={unit} onChange={(e) => setUnit(e.target.value)} />
          </label>
        )}
        <label className="fl-field">
          {t('o02.channel')}
          <select
            name="channel"
            value={channel}
            onChange={(e) => setChannel(e.target.value as SuspendInput['channel'])}
          >
            {CHANNELS.map((c) => (
              <option key={c} value={c}>
                {t(`o02.channel.${c}`)}
              </option>
            ))}
          </select>
        </label>
        <label className="fl-field">
          {t('common.reason')}
          <TextAreaField
            name="reason"
            maxLength={1000}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="btn d" disabled={!ready}>
            {t('o02.suspend')}
          </button>
        </div>
      </form>
    </div>
  );
}
