'use client';

import { type FormEvent, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { date, Problem, ServerPagination, useToast } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { Locked, Tbl } from '@/shared/ui/wireframe';
import type { UpdateRequest } from '../api/hrmsUpdateApi';
import { requestComplete } from '../business/hrmsUpdateRules';
import {
  hrmsUpdateKeys,
  myHrmsUpdatePage,
  useRaiseUpdate,
  useUpdateFields,
} from '../orchestration/hrmsUpdateQueries';
import { outcome } from './HrmsUpdateRequests';
import { UpdateOrderCopies } from './UpdateOrderCopies';

/** The member's requests a support preview may show, and why nothing can be raised there (SRS-17-6). */
export interface HrmsPreview {
  why: string;
  rows: { value: string | null; status: string; submittedAt: string }[];
}

/** SRS l.3309: the drawn E-01 form takes an HRMS reference of up to 30 characters. */
const REFERENCE_MAX = 30;

function outcomeTone(key: string): string {
  if (key === 'APPROVED_APPLIED') return 'p-ok';
  if (key === 'FETCH_FAILED') return 'p-bad';
  if (key === 'NOT_YET_IN_HRMS' || key === 'CLOSED') return 'p-gray';
  return 'p-info';
}

/**
 * E-01 the drawn "🔄 HRMS Update Request" card and "My HRMS Update Requests" table (Wireframes v6.1,
 * G22; SRS-17-14, SRS-17-15, SRS-1-70, l.3308-3310): Field and Effective date required, the HRMS
 * reference and the order copy optional. A request never changes a value: Payroll reads the record
 * from HRMS. The drawn file control holds the file; the order details SRS-G-30 asks for are taken in
 * the order copy card, which opens on the new request or from the closed section under the table (the
 * drawn rows have no action). With {@code preview} the same objects are drawn for a support user, every
 * input disabled, and no /mine read is made (a refused call ends the session, OD-130); the field list is
 * read only by a role the API lets read it (I-21 VIEW), for the same reason.
 */
export function MyHrmsUpdate({ preview }: { preview?: HrmsPreview }) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const member = preview === undefined;
  const mayRaise = member && can(me.data, 'E-01', 'CREATE');
  const fields = useUpdateFields(member || can(me.data, 'I-21', 'VIEW'));
  const raise = useRaiseUpdate(true);
  const [fieldCode, setFieldCode] = useState('');
  const [reference, setReference] = useState('');
  const [effectiveDate, setEffectiveDate] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [fileKey, setFileKey] = useState(0);
  const [incomplete, setIncomplete] = useState(false);
  const [orders, setOrders] = useState<{ request: UpdateRequest; file: File | null } | null>(null);
  const [ordersOpen, setOrdersOpen] = useState(false);
  const why = preview?.why ?? t('e01w.why.hrmsRaise');

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const complete = requestComplete({
      needsEmployee: false,
      fieldCode,
      effectiveDate,
      hrmsReference: reference,
    });
    setIncomplete(!complete);
    if (!complete) return;
    try {
      const made = await raise.mutateAsync({
        fieldCode,
        effectiveDate,
        ...(reference.trim() ? { hrmsReference: reference.trim() } : {}),
      });
      toast(t('i21.upd.raised', [made.requestNo, t(`i21.upd.status.${made.status}`)]));
      if (file) setOrders({ request: made, file });
      setFieldCode('');
      setReference('');
      setEffectiveDate('');
      setFile(null);
      setFileKey((k) => k + 1);
    } catch {
      // shown by <Problem error={raise.error} />
    }
  }

  return (
    <div className="g2 fl-mt" id="e01-hrms">
      <div className="card">
        <div className="hd">
          <h3>{t('e01w.hrms.title')}</h3>
        </div>
        <form className="bd" noValidate aria-label={t('i21.upd.raise')} onSubmit={(e) => void onSubmit(e)}>
          <div className="sub2">{t('e01w.hrms.hint')}</div>
          <Problem error={fields.error} />
          <div className="rf-grid">
            <div className="rf-field span2">
              <label htmlFor="e01-hrms-field">
                {t('i21.field')} <span className="req">*</span>
              </label>
              <select
                id="e01-hrms-field"
                required
                disabled={!member}
                value={fieldCode}
                onChange={(e) => setFieldCode(e.target.value)}
              >
                <option value="">{t('i21.upd.choose')}</option>
                {(fields.data ?? []).map((f) => (
                  <option key={f.code} value={f.code}>
                    {f.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="e01-hrms-reference">{t('i21.upd.reference')}</label>
              <input
                id="e01-hrms-reference"
                maxLength={REFERENCE_MAX}
                disabled={!member}
                placeholder={t('e01w.hrms.referencePlaceholder')}
                value={reference}
                onChange={(e) => setReference(e.target.value)}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="e01-hrms-effective">
                {t('i21.effective')} <span className="req">*</span>
              </label>
              <input
                id="e01-hrms-effective"
                type="date"
                required
                disabled={!member}
                value={effectiveDate}
                onChange={(e) => setEffectiveDate(e.target.value)}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="e01-hrms-order">{t('e01w.hrms.orderCopy')}</label>
              <input
                key={fileKey}
                id="e01-hrms-order"
                type="file"
                accept=".pdf,.jpg,.jpeg,.png"
                disabled={!member}
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
            </div>
          </div>
          {incomplete && (
            <p className="fl-error" role="alert">
              {t('e01w.hrms.incomplete')}
            </p>
          )}
          <Problem error={raise.error} />
          <div className="fl-mt">
            {mayRaise ? (
              <button type="submit" className="btn pri" disabled={raise.isPending}>
                {t('e01w.hrms.raise')}
              </button>
            ) : (
              <Locked label={t('e01w.hrms.raise')} why={why} kind="pri" />
            )}
          </div>
        </form>
      </div>
      <Tbl
        title={t('i21.upd.mine')}
        label={t('i21.upd.list')}
        cols={[
          { t: t('i21.upd.request'), s: 'ESS' },
          { t: t('i21.field'), s: 'ESS' },
          { t: t('e01w.hrms.raised'), s: 'SYS' },
          { t: t('e01w.hrms.outcome'), s: 'SYS' },
        ]}
        after={
          <>
            <div className="bd">
              <div className="oq" role="note">
                <b>{t('e01.claims')} —</b> {t('e01.claimsInHrms')}
              </div>
            </div>
            {member && (
              <RegisterSection
                id="e01-hrms-orders"
                label={t('e01w.hrms.orders')}
                open={ordersOpen}
                onToggle={setOrdersOpen}
              >
                {ordersOpen && (
                  <ServerPagination
                    queryKey={[...hrmsUpdateKeys.mine, 'order-copies']}
                    fetchPage={myHrmsUpdatePage}
                    personal
                    empty={<p className="fl-hint">{t('i21.upd.none')}</p>}
                  >
                    {(rows) => (
                      <ul className="fl-list">
                        {rows.map((row) => (
                          <li key={row.id}>
                            {row.requestNo}{' '}
                            <button
                              type="button"
                              className="lnk"
                              aria-label={t('i21.upd.order.of', [row.requestNo])}
                              onClick={() => setOrders({ request: row, file: null })}
                            >
                              {t('i21.upd.order.open')}
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </ServerPagination>
                )}
              </RegisterSection>
            )}
          </>
        }
      >
        {preview ? (
          <PreviewRows rows={preview.rows} />
        ) : (
          <ServerPagination
            queryKey={hrmsUpdateKeys.mine}
            fetchPage={myHrmsUpdatePage}
            personal
            empty={<p className="fl-hint">{t('i21.upd.none')}</p>}
          >
            {(rows) => rows.map((row) => <RequestRow key={row.id} row={row} />)}
          </ServerPagination>
        )}
      </Tbl>
      {orders && (
        <UpdateOrderCopies
          key={orders.request.id}
          request={orders.request}
          mine
          mayAttach={mayRaise}
          initialFile={orders.file}
          onClose={() => setOrders(null)}
        />
      )}
    </div>
  );
}

function RequestRow({ row }: { row: UpdateRequest }) {
  const { t } = useI18n();
  const shown = outcome(row);
  return (
    <tr>
      <td>
        <b>{row.requestNo}</b>
        <div className="sub2">{t(`i21.upd.channel.${row.channel}`)}</div>
        {row.hrmsReference && <div className="sub2">{row.hrmsReference}</div>}
      </td>
      <td>
        {row.fieldLabel}
        <div className="sub2">
          {t('i21.effective')}: {date(row.effectiveDate)}
        </div>
      </td>
      <td>{date(row.raisedAt)}</td>
      <td>
        <span className={`pill ${outcomeTone(shown)}`}>{t(`i21.upd.status.${shown}`)}</span>
        {row.taskNo && <div className="sub2">{t('i21.upd.task', [row.taskNo])}</div>}
        {row.messageCode && <div className="sub2">{t(`i21.upd.msg.${row.messageCode}`)}</div>}
      </td>
    </tr>
  );
}

/** The support view has the member's submissions only (API-14-2): the request's own details are not read. */
function PreviewRows({ rows }: { rows: HrmsPreview['rows'] }) {
  const { t } = useI18n();
  if (rows.length === 0) {
    return (
      <tr>
        <td>—</td>
        <td>—</td>
        <td>—</td>
        <td>—</td>
      </tr>
    );
  }
  return (
    <>
      {rows.map((r) => (
        <tr key={`${r.submittedAt}-${r.value ?? ''}`}>
          <td>{r.value ?? '—'}</td>
          <td>—</td>
          <td>{date(r.submittedAt)}</td>
          <td>
            <span className="pill p-info">{t(`e02.status.${r.status}`)}</span>
          </td>
        </tr>
      ))}
    </>
  );
}
