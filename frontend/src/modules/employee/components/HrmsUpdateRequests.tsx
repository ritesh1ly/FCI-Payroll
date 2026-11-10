'use client';

import { type FormEvent, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, date, dateTime, Problem, ServerPagination, Src, useToast } from '@/shared/ui';
import type { UpdateRequest } from '../api/hrmsUpdateApi';
import { refetchable, requestComplete } from '../business/hrmsUpdateRules';
import {
  hrmsUpdateKeys,
  hrmsUpdatePage,
  myHrmsUpdatePage,
  useRaiseUpdate,
  useRefetchUpdate,
  useUpdateFields,
} from '../orchestration/hrmsUpdateQueries';
import { UpdateOrderCopies } from './UpdateOrderCopies';

/**
 * HRMS Update Request (plan 16 phase 16c; SRS-1-70): when a change made in HRMS has not reached
 * Payroll, the employee (eSeva, {@code mine}) or the Payroll Maker (I-21) names the field, the HRMS
 * reference if known and the effective date. On submission Payroll fetches the record from HRMS:
 * a different value opens the I-21 task, a change already received is linked, otherwise the
 * request closes with "not yet in HRMS". The request never changes a value itself.
 */
export function HrmsUpdateRequests({ mine }: { mine: boolean }) {
  const { t } = useI18n();
  const me = useMe();
  const screen = mine ? 'E-01' : 'I-21';
  const mayView = can(me.data, screen, 'VIEW');
  const mayRaise = mine ? can(me.data, 'E-01', 'CREATE') : can(me.data, 'I-21', 'REQUEST');
  if (!mayView) return null;
  return (
    <Card title={t(mine ? 'i21.upd.mine' : 'i21.upd.title')}>
      <p className="fl-hint">{t('i21.upd.hint')}</p>
      {mayRaise && <RaiseForm mine={mine} />}
      <RequestList mine={mine} mayRefetch={!mine && mayRaise} mayAttach={mayRaise} />
    </Card>
  );
}

function RaiseForm({ mine }: { mine: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const fields = useUpdateFields();
  const raise = useRaiseUpdate(mine);
  const [eis, setEis] = useState('');
  const [fieldCode, setFieldCode] = useState('');
  const [reference, setReference] = useState('');
  const [effectiveDate, setEffectiveDate] = useState('');
  const ready =
    requestComplete({ eis, needsEmployee: !mine, fieldCode, effectiveDate, hrmsReference: reference }) &&
    !raise.isPending;

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ready) return;
    try {
      const made = await raise.mutateAsync({
        ...(mine ? {} : { eis: eis.trim() }),
        fieldCode,
        effectiveDate,
        ...(reference.trim() ? { hrmsReference: reference.trim() } : {}),
      });
      toast(t('i21.upd.raised', [made.requestNo, t(`i21.upd.status.${made.status}`)]));
      setFieldCode('');
      setReference('');
      setEffectiveDate('');
    } catch {
      // shown by <Problem error={raise.error} />
    }
  }

  return (
    <form className="fl-form" onSubmit={(e) => void onSubmit(e)} aria-label={t('i21.upd.raise')}>
      <h4>
        {t('i21.upd.raise')}
        <Src s={mine ? 'ESS' : 'ENT'} />
      </h4>
      <Problem error={fields.error} />
      {!mine && (
        <label className="fl-field">
          {t('i21.upd.eis')}
          <input maxLength={20} value={eis} onChange={(e) => setEis(e.target.value)} />
        </label>
      )}
      <label className="fl-field">
        {t('i21.field')}
        <select value={fieldCode} onChange={(e) => setFieldCode(e.target.value)}>
          <option value="">{t('i21.upd.choose')}</option>
          {(fields.data ?? []).map((f) => (
            <option key={f.code} value={f.code}>
              {f.label}
            </option>
          ))}
        </select>
      </label>
      <label className="fl-field">
        {t('i21.upd.reference')}
        <input maxLength={60} value={reference} onChange={(e) => setReference(e.target.value)} />
      </label>
      <label className="fl-field">
        {t('i21.effective')}
        <input type="date" value={effectiveDate} onChange={(e) => setEffectiveDate(e.target.value)} />
      </label>
      <Problem error={raise.error} />
      <div className="fl-actions">
        <button type="submit" className="btn pri" disabled={!ready}>
          {t('i21.upd.submit')}
        </button>
      </div>
    </form>
  );
}

function RequestList({
  mine,
  mayRefetch,
  mayAttach,
}: {
  mine: boolean;
  mayRefetch: boolean;
  mayAttach: boolean;
}) {
  const [open, setOpen] = useState<UpdateRequest | null>(null);
  const { t } = useI18n();
  return (
    <div className="wrap">
      <ServerPagination
        queryKey={mine ? hrmsUpdateKeys.mine : hrmsUpdateKeys.list}
        fetchPage={mine ? myHrmsUpdatePage : hrmsUpdatePage}
        personal={mine}
        empty={<p className="fl-hint">{t('i21.upd.none')}</p>}
      >
        {(rows) => (
          <table className="t" aria-label={t('i21.upd.list')}>
            <thead>
              <tr>
                <th>
                  {t('i21.upd.request')}
                  <Src s="SYS" />
                </th>
                {!mine && (
                  <th>
                    {t('i21.employee')}
                    <Src s="HRMS" />
                  </th>
                )}
                <th>
                  {t('i21.field')}
                  <Src s={mine ? 'ESS' : 'ENT'} />
                </th>
                <th>
                  {t('i21.effective')}
                  <Src s={mine ? 'ESS' : 'ENT'} />
                </th>
                <th>
                  {t('i21.status')}
                  <Src s="SYS" />
                </th>
                {mayRefetch && <th aria-label={t('common.actions')} />}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <Row
                  key={row.id}
                  row={row}
                  mine={mine}
                  mayRefetch={mayRefetch}
                  onOrders={() => setOpen(row)}
                />
              ))}
            </tbody>
          </table>
        )}
      </ServerPagination>
      {open && (
        <UpdateOrderCopies
          key={open.id}
          request={open}
          mine={mine}
          mayAttach={mayAttach}
          onClose={() => setOpen(null)}
        />
      )}
    </div>
  );
}

function Row({
  row,
  mine,
  mayRefetch,
  onOrders,
}: {
  row: UpdateRequest;
  mine: boolean;
  mayRefetch: boolean;
  onOrders: () => void;
}) {
  const { t } = useI18n();
  const refetch = useRefetchUpdate();
  return (
    <tr>
      <td>
        <b>{row.requestNo}</b>
        <div className="sub2">
          {dateTime(row.raisedAt)} · {t(`i21.upd.channel.${row.channel}`)}
        </div>
        {row.hrmsReference && <div className="sub2">{row.hrmsReference}</div>}
        <button
          type="button"
          className="lnk"
          aria-label={t('i21.upd.order.of', [row.requestNo])}
          onClick={onOrders}
        >
          {t('i21.upd.order.open')}
        </button>
      </td>
      {!mine && (
        <td>
          <b>{row.eis}</b> <span className="sub2">{row.employee}</span>
        </td>
      )}
      <td>{row.fieldLabel}</td>
      <td>{date(row.effectiveDate)}</td>
      <td>
        {t(`i21.upd.status.${outcome(row)}`)}
        {row.taskNo && <div className="sub2">{t('i21.upd.task', [row.taskNo])}</div>}
        {row.messageCode && <div className="sub2">{t(`i21.upd.msg.${row.messageCode}`)}</div>}
      </td>
      {mayRefetch && (
        <td>
          {refetchable(row.status) && (
            <button
              type="button"
              className="btn"
              disabled={refetch.isPending}
              onClick={() => refetch.mutate(row.id)}
            >
              {t('i21.upd.refetch')}
            </button>
          )}
          <Problem error={refetch.error} />
        </td>
      )}
    </tr>
  );
}

/**
 * E-01 l.3308 (US-GAP-258): the outcome of a request - Task opened, Linked to open task, Approved
 * and applied once its I-21 task is approved, Closed - not yet in HRMS.
 */
export function outcome(row: { status: string; taskStatus?: string | null }): string {
  return row.taskStatus === 'APPROVED' || row.taskStatus === 'AUTO_APPLIED' ? 'APPROVED_APPLIED' : row.status;
}
