'use client';

import { type FormEvent, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, date, dateTime, Problem, ServerPagination, Src, useToast, TextAreaField } from '@/shared/ui';
import type { FlagState } from '../api/flagApi';
import {
  EMPTY_ORDER,
  OrderFields,
  orderComplete,
  todayIso,
  useDocumentTypes,
  type OrderDraft,
} from '@/modules/general';
import { endDateValid, proposalComplete, proposable } from '../business/flagRules';
import { flagHistoryPage, flagKeys, useEmployeeFlags, useProposeFlag } from '../orchestration/flagQueries';
import { useEmployee } from '../orchestration/employeeQueries';

/**
 * M-01 Payroll allowance flags (plan 16 phase 16d-2; SRS-1-4, SRS-1-11; BR-EMPM-004 / 005 / 006):
 * the allowances the engine pays only when flagged (CCA, NPA, transport, …), each with its value
 * today; Establishment/HR proposes a value from a date with a reason and the supporting document
 * (SRS-1-26), the Payroll Checker approves on the Approvals Inbox with the current and requested
 * value in view, and every version stays in the history.
 */
export function AllowanceFlags({ eis }: { eis: string }) {
  const { t } = useI18n();
  const me = useMe();
  const flags = useEmployeeFlags(eis);
  const [proposing, setProposing] = useState<FlagState | null>(null);
  const mayPropose = can(me.data, 'M-01', 'FLAG');
  // SRS-1-77 (US-GAP-49): a separated record is read-only (only the FSD / paying unit changes).
  const separated = useEmployee(eis).data?.employmentStatus === 'SEPARATED';
  const data = flags.data;
  return (
    <Card title={t('m01.flags.title')}>
      <p className="fl-hint">{t('m01.flags.hint')}</p>
      <Problem error={flags.error} />
      {data && (
        <table className="t" aria-label={t('m01.flags.title')}>
          <thead>
            <tr>
              <th>
                {t('m01.flags.allowance')}
                <Src s="SYS" />
              </th>
              <th>
                {t('m01.flags.value')}
                <Src s="ENT" />
              </th>
              <th>
                {t('m01.flags.since')}
                <Src s="ENT" />
              </th>
              {mayPropose && <th aria-label={t('common.actions')} />}
            </tr>
          </thead>
          <tbody>
            {data.flags.map((f) => (
              <tr key={f.code}>
                <td>{f.label}</td>
                <td>
                  {t(`m01.flags.${f.value}`)}
                  {f.pending && <div className="sub2">{t('m01.flags.pending')}</div>}
                </td>
                <td>{f.since ? date(f.since) : '—'}</td>
                {mayPropose && (
                  <td>
                    <button
                      type="button"
                      className="btn sm"
                      disabled={separated || !proposable(f.pending)}
                      title={
                        separated
                          ? t('m01.why.separated')
                          : proposable(f.pending)
                            ? undefined
                            : t('m01.flags.why.pending')
                      }
                      aria-label={t('m01.flags.change', [f.label])}
                      onClick={() => setProposing(f)}
                    >
                      {t('m01.flags.changeShort')}
                    </button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data && proposing && (
        <ProposalForm
          eis={eis}
          flag={proposing}
          joiningDate={data.joiningDate}
          onDone={() => setProposing(null)}
        />
      )}
      <h4>{t('m01.flags.history')}</h4>
      <ServerPagination
        queryKey={flagKeys.history(eis)}
        fetchPage={flagHistoryPage(eis)}
        personal={false}
        empty={<p className="fl-hint">{t('m01.flags.noHistory')}</p>}
      >
        {(rows) => (
          <table className="t" aria-label={t('m01.flags.history')}>
            <thead>
              <tr>
                <th>
                  {t('m01.flags.allowance')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('m01.flags.value')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m01.flags.from')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('common.reason')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m01.flags.by')}
                  <Src s="SYS" />
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((v) => (
                <tr key={`${v.code}-${v.effectiveFrom}-${v.changedAt}`}>
                  <td>{data?.flags.find((f) => f.code === v.code)?.label ?? v.code}</td>
                  <td>{t(`m01.flags.${v.value}`)}</td>
                  <td>
                    {date(v.effectiveFrom)}
                    {v.effectiveTo && <span className="sub2"> – {date(v.effectiveTo)}</span>}
                  </td>
                  <td>{v.reason}</td>
                  <td>
                    {v.changedBy}
                    <div className="sub2">{dateTime(v.changedAt)}</div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </ServerPagination>
    </Card>
  );
}

function ProposalForm({
  eis,
  flag,
  joiningDate,
  onDone,
}: {
  eis: string;
  flag: FlagState;
  joiningDate: string | null;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const propose = useProposeFlag(eis);
  const [value, setValue] = useState<'Y' | 'N'>(flag.value === 'Y' ? 'N' : 'Y');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [effectiveTo, setEffectiveTo] = useState('');
  const [reason, setReason] = useState('');
  const [order, setOrder] = useState<OrderDraft>(EMPTY_ORDER);
  const types = useDocumentTypes();
  const today = todayIso();
  const ready =
    proposalComplete({ flagCode: flag.code, value, effectiveFrom, reason, joiningDate }) &&
    endDateValid(effectiveFrom, effectiveTo) &&
    orderComplete(order, types.data ?? [], today) &&
    !propose.isPending;

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ready) return;
    try {
      if (!order.file) return;
      const made = await propose.mutateAsync({
        body: {
          flagCode: flag.code,
          value,
          effectiveFrom,
          effectiveTo: effectiveTo === '' ? null : effectiveTo,
          reason: reason.trim(),
        },
        doc: {
          documentType: order.documentType,
          reference: order.reference.trim(),
          orderDate: order.orderDate,
          issuingAuthority: order.issuingAuthority.trim(),
          file: order.file,
        },
      });
      toast(t('m01.flags.submitted', [flag.label, made.approvalId]));
      onDone();
    } catch {
      // shown by <Problem error={propose.error} />
    }
  }

  return (
    <form
      className="fl-form"
      onSubmit={(e) => void onSubmit(e)}
      aria-label={t('m01.flags.change', [flag.label])}
    >
      <p className="fl-hint">
        {t('m01.flags.current')} <strong>{t(`m01.flags.${flag.value}`)}</strong>
      </p>
      <label className="fl-field">
        {t('m01.flags.requested')}
        <select value={value} onChange={(e) => setValue(e.target.value as 'Y' | 'N')}>
          <option value="Y">{t('m01.flags.Y')}</option>
          <option value="N">{t('m01.flags.N')}</option>
        </select>
      </label>
      <label className="fl-field">
        {t('m01.flags.from')}
        <input
          type="date"
          min={joiningDate ?? undefined}
          value={effectiveFrom}
          onChange={(e) => setEffectiveFrom(e.target.value)}
        />
      </label>
      <label className="fl-field">
        {t('m01.flags.to')}
        <input
          type="date"
          min={effectiveFrom || undefined}
          value={effectiveTo}
          onChange={(e) => setEffectiveTo(e.target.value)}
        />
      </label>
      {!endDateValid(effectiveFrom, effectiveTo) && <p className="fl-hint">{t('m01.flags.why.to')}</p>}
      <label className="fl-field">
        {t('common.reason')}
        <TextAreaField maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      <fieldset className="rf-grid">
        <legend>{t('m01.flags.document')}</legend>
        <OrderFields
          idPrefix={`flag-${flag.code}`}
          value={order}
          types={types.data ?? []}
          today={today}
          onChange={setOrder}
        />
      </fieldset>
      <Problem error={propose.error} />
      <div className="fl-actions">
        <button type="button" className="btn" onClick={onDone}>
          {t('common.cancel')}
        </button>
        <button type="submit" className="btn pri" disabled={!ready}>
          {t('m01.flags.submit')}
        </button>
      </div>
    </form>
  );
}
