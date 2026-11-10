'use client';

import { type FormEvent, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, Problem, Src, useToast, TextAreaField } from '@/shared/ui';
import type { ControlFlagState } from '../api/controlFlagApi';
import {
  EMPTY_ORDER,
  OrderFields,
  orderComplete,
  todayIso,
  useDocumentTypes,
  type OrderDraft,
} from '@/modules/general';
import { controlProposalComplete, proposable } from '../business/flagRules';
import { useEmployee } from '../orchestration/employeeQueries';
import { useControlFlags, useProposeControlFlag } from '../orchestration/flagQueries';

/**
 * M-01 Control flags (SRS-1-7, SRS-1-11; FRS §7.8.1 Scenario 4; US-GAP-4): Hold and Stop-Payment,
 * Payroll-owned and Enterable. Establishment/HR sets or clears one from a date with a reason and
 * the order (SRS-G-32); the Payroll Checker approves on the Approvals Inbox. While set, the member's
 * payment is suppressed and the Exceptions & Holds worklist shows it. Absconding is HRMS-owned
 * (I-21) and is not changed here.
 */
export function ControlFlags({ eis }: { eis: string }) {
  const { t } = useI18n();
  const me = useMe();
  const flags = useControlFlags(eis);
  const [proposing, setProposing] = useState<ControlFlagState | null>(null);
  const mayPropose = can(me.data, 'M-01', 'FLAG');
  // SRS-1-77 (US-GAP-49): a separated record is read-only.
  const separated = useEmployee(eis).data?.employmentStatus === 'SEPARATED';
  const data = flags.data;
  return (
    <Card title={t('m01.control.title')}>
      <p className="fl-hint">{t('m01.control.hint')}</p>
      <Problem error={flags.error} />
      {data && (
        <table className="t" aria-label={t('m01.control.title')}>
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
              {mayPropose && <th aria-label={t('common.actions')} />}
            </tr>
          </thead>
          <tbody>
            {data.flags.map((f) => (
              <tr key={f.code}>
                <td>{t(`m01.control.${f.code}`)}</td>
                <td>
                  {t(`m01.flags.${f.value}`)}
                  {f.pending && <div className="sub2">{t('m01.control.pending')}</div>}
                </td>
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
                            : t('m01.control.pending')
                      }
                      aria-label={`${t('m01.control.change')} ${t(`m01.control.${f.code}`)}`}
                      onClick={() => setProposing(f)}
                    >
                      {t(f.value === 'Y' ? 'm01.control.clear' : 'm01.control.set')}
                    </button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data && proposing && (
        <ControlForm
          eis={eis}
          flag={proposing}
          joiningDate={data.joiningDate}
          onDone={() => setProposing(null)}
        />
      )}
    </Card>
  );
}

function ControlForm({
  eis,
  flag,
  joiningDate,
  onDone,
}: {
  eis: string;
  flag: ControlFlagState;
  joiningDate: string | null;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const propose = useProposeControlFlag(eis);
  const value: 'Y' | 'N' = flag.value === 'Y' ? 'N' : 'Y';
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [reason, setReason] = useState('');
  const [order, setOrder] = useState<OrderDraft>(EMPTY_ORDER);
  const types = useDocumentTypes();
  const today = todayIso();
  const label = t(`m01.control.${flag.code}`);
  const ready =
    controlProposalComplete({ flagCode: flag.code, value, effectiveFrom, reason, joiningDate, today }) &&
    orderComplete(order, types.data ?? [], today) &&
    !propose.isPending;

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ready || !order.file) return;
    try {
      await propose.mutateAsync({
        body: { flagCode: flag.code, value, effectiveFrom, reason: reason.trim() },
        doc: {
          documentType: order.documentType,
          reference: order.reference.trim(),
          orderDate: order.orderDate,
          issuingAuthority: order.issuingAuthority.trim(),
          file: order.file,
        },
      });
      toast(t('m01.control.submitted'));
      onDone();
    } catch {
      // shown by <Problem error={propose.error} />
    }
  }

  return (
    <form
      className="fl-form"
      onSubmit={(e) => void onSubmit(e)}
      aria-label={`${t('m01.control.change')} ${label}`}
    >
      <p className="fl-hint">
        {label}: <strong>{t(`m01.flags.${flag.value}`)}</strong> → <strong>{t(`m01.flags.${value}`)}</strong>
      </p>
      <label className="fl-field">
        {t('m01.flags.from')}
        <input
          type="date"
          min={joiningDate ?? undefined}
          max={today}
          value={effectiveFrom}
          onChange={(e) => setEffectiveFrom(e.target.value)}
        />
      </label>
      <label className="fl-field">
        {t('common.reason')}
        <TextAreaField maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      <fieldset className="rf-grid">
        <legend>{t('m01.flags.document')}</legend>
        <OrderFields
          idPrefix={`control-${flag.code}`}
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
