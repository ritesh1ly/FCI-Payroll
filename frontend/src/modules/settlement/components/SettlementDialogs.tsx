'use client';

import { Fragment, type ReactNode } from 'react';
import { useI18n } from '@/shared/i18n';
import { Problem, Src, TextAreaField, useDialogFocus, usePdfDownload } from '@/shared/ui';
import { Tbl } from '@/shared/ui/wireframe';
import type { CaseDetail } from '../api/settlementApi';
import { outstanding } from '../business/settlementRules';
import { settlementCalls, useAwaiting } from '../orchestration/settlementQueries';
import { StatementTable, useStatementCols } from './SettlementPanels';

/** O-05 l.2942, l.2945: the sanction letter and the authorising order are PDF, JPG or PNG. */
const ORDER_FILES = '.pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png';

export type FormState =
  | {
      kind: 'EX_GRATIA';
      letterNo: string;
      letterDate: string;
      amount: string;
      authority: string;
      file: File | null;
    }
  | { kind: 'EXCLUDE'; clearance: string; reason: string }
  | {
      kind: 'SOURCE';
      recoveryId: number;
      source: 'PAYROLL_DUES' | 'GRATUITY';
      reason: string;
      orderRef: string;
      orderDate: string;
      authority: string;
      file: File | null;
    }
  | null;

type Run = (work: () => Promise<unknown>, done: string) => Promise<boolean>;

function Modal({ title, children }: { title: string; children: ReactNode }) {
  const dialog = useDialogFocus<HTMLDivElement>();
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div ref={dialog} className="fl-modal fl-modal-wide" role="dialog" aria-modal="true" aria-label={title}>
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  );
}

/** SRS-10-11: how many cases await each clearance (the drawn 🔍 Pending Clearances). */
export function PendingClearancesDialog({ onClose }: { onClose: () => void }) {
  const { t } = useI18n();
  const awaiting = useAwaiting();
  return (
    <Modal title={t('o05.w.pendingClearances')}>
      <Problem error={awaiting.error} />
      {awaiting.data?.length === 0 && <p className="sub2">{t('o05.noneAwaiting')}</p>}
      <div className="kv">
        {awaiting.data?.map((a) => (
          <Fragment key={a.clearance}>
            <span className="k">{t(`o05.clearance.${a.clearance}`)}</span>
            <span>{a.cases}</span>
          </Fragment>
        ))}
      </div>
      <div className="rf-actbar">
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
    </Modal>
  );
}

/**
 * SRS-10-12: the settlement statement of the case on screen, and as the server's PDF (OD-144) with the same rows
 * (FCI's own statement layout stays PENDING O05-14).
 */
export function StatementDialog({ d, onClose }: { d: CaseDetail; onClose: () => void }) {
  const { t } = useI18n();
  const cols = useStatementCols();
  const pdf = usePdfDownload();
  return (
    <Modal title={t('o05.statementTitle', [d.summary.caseReference, d.summary.name])}>
      <Tbl title={t('o05.statementCard')} label={t('o05.statementCard')} cols={cols}>
        <StatementTable d={d} />
      </Tbl>
      <p className="sub2">{t('o05.statementNote')}</p>
      <Problem error={pdf.error} />
      <div className="rf-actbar">
        <button type="button" className="btn" onClick={() => window.print()}>
          {t('o05.print')}
        </button>
        <button
          type="button"
          className="btn pri"
          disabled={pdf.busy}
          onClick={() =>
            void pdf.download(
              `/v1/settlement-cases/${d.summary.caseId}/statement`,
              `settlement-statement-${d.summary.caseReference}.pdf`,
            )
          }
        >
          {t('o05.statementPdf')}
        </button>
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
    </Modal>
  );
}

/** The Maker's and the Checker's forms on the open case (SRS-10-25, SRS-10-22, SRS-10-14). */
export function CaseForm({
  d,
  form,
  onForm,
  run,
  error,
}: {
  d: CaseDetail;
  form: NonNullable<FormState>;
  onForm: (next: FormState) => void;
  run: Run;
  error: unknown;
}) {
  const { t } = useI18n();
  const id = d.summary.caseId;
  const close = () => onForm(null);
  if (form.kind === 'EX_GRATIA') {
    return (
      <Modal title={t('action.o05.exGratia')}>
        <Problem error={error} />
        <div className="form-grid">
          <label>
            {t('o05.letterNo')}
            <Src s="ENT" />
            <input
              maxLength={60}
              value={form.letterNo}
              onChange={(e) => onForm({ ...form, letterNo: e.target.value })}
            />
          </label>
          <label>
            {t('o05.letterDate')}
            <Src s="ENT" />
            <input
              type="date"
              value={form.letterDate}
              onChange={(e) => onForm({ ...form, letterDate: e.target.value })}
            />
          </label>
          <label>
            {t('o05.amount')}
            <Src s="ENT" />
            <input
              inputMode="decimal"
              maxLength={15}
              value={form.amount}
              onChange={(e) => onForm({ ...form, amount: e.target.value })}
            />
          </label>
          <label>
            {t('o05.issuingAuthority')}
            <Src s="ENT" />
            <input
              maxLength={200}
              value={form.authority}
              onChange={(e) => onForm({ ...form, authority: e.target.value })}
            />
          </label>
          <label>
            {t('o05.letter')}
            <Src s="ENT" />
            <input
              type="file"
              accept={ORDER_FILES}
              onChange={(e) => onForm({ ...form, file: e.target.files?.[0] ?? null })}
            />
          </label>
          <div className="toolbar">
            <button
              type="button"
              className="btn pri"
              disabled={
                !form.letterNo.trim() ||
                !form.letterDate ||
                !(Number(form.amount) > 0) ||
                !form.authority.trim() ||
                form.file === null
              }
              title={t('o05.why.exGratia')}
              onClick={() => {
                const file = form.file;
                if (!file) return;
                void run(
                  () =>
                    settlementCalls.exGratia(id, {
                      letterNo: form.letterNo.trim(),
                      letterDate: form.letterDate,
                      amount: Number(form.amount),
                      issuingAuthority: form.authority.trim(),
                      letter: file,
                    }),
                  'o05.exGratiaSent',
                ).then((ok) => ok && close());
              }}
            >
              {t('common.submitForApproval')}
            </button>
            <button type="button" className="btn" onClick={close}>
              {t('common.cancel')}
            </button>
          </div>
        </div>
      </Modal>
    );
  }
  if (form.kind === 'EXCLUDE') {
    return (
      <Modal title={t('action.o05.exclude')}>
        <Problem error={error} />
        <div className="form-grid">
          <label>
            {t('o05.clearanceToExclude')}
            <Src s="ENT" />
            <select value={form.clearance} onChange={(e) => onForm({ ...form, clearance: e.target.value })}>
              {d.clearances
                .filter((c) => outstanding(c.status))
                .map((c) => (
                  <option key={c.type} value={c.type}>
                    {t(`o05.clearance.${c.type}`)}
                  </option>
                ))}
            </select>
          </label>
          <label>
            {t('o05.exclusionReason')}
            <Src s="ENT" />
            <TextAreaField
              maxLength={500}
              value={form.reason}
              onChange={(e) => onForm({ ...form, reason: e.target.value })}
            />
          </label>
          <div className="toolbar">
            <button
              type="button"
              className="btn pri"
              disabled={!form.clearance || !form.reason.trim()}
              onClick={() =>
                void run(
                  () => settlementCalls.exclude(id, form.clearance, form.reason.trim()),
                  'o05.exclusionSent',
                ).then((ok) => ok && close())
              }
            >
              {t('action.o05.recordExclusion')}
            </button>
            <button type="button" className="btn" onClick={close}>
              {t('common.cancel')}
            </button>
          </div>
        </div>
      </Modal>
    );
  }
  return (
    <Modal title={t('action.o05.changeSource')}>
      <Problem error={error} />
      <div className="form-grid">
        <p className="sub2">{t(`o05.source.${form.source}`)}</p>
        <label>
          {t('o05.sourceReason')}
          <Src s="ENT" />
          <TextAreaField
            maxLength={500}
            value={form.reason}
            onChange={(e) => onForm({ ...form, reason: e.target.value })}
          />
        </label>
        {form.source === 'GRATUITY' && (
          <>
            <label>
              {t('o05.orderRef')}
              <Src s="ENT" />
              <input
                maxLength={60}
                value={form.orderRef}
                onChange={(e) => onForm({ ...form, orderRef: e.target.value })}
              />
            </label>
            <label>
              {t('o05.orderDate')}
              <Src s="ENT" />
              <input
                type="date"
                value={form.orderDate}
                onChange={(e) => onForm({ ...form, orderDate: e.target.value })}
              />
            </label>
            <label>
              {t('o05.issuingAuthority')}
              <Src s="ENT" />
              <input
                maxLength={200}
                value={form.authority}
                onChange={(e) => onForm({ ...form, authority: e.target.value })}
              />
            </label>
            <label>
              {t('o05.order')}
              <Src s="ENT" />
              <input
                type="file"
                accept={ORDER_FILES}
                onChange={(e) => onForm({ ...form, file: e.target.files?.[0] ?? null })}
              />
            </label>
          </>
        )}
        <div className="toolbar">
          <button
            type="button"
            className="btn pri"
            disabled={
              !form.reason.trim() ||
              (form.source === 'GRATUITY' &&
                (!form.orderRef.trim() || !form.orderDate || !form.authority.trim() || form.file === null))
            }
            onClick={() =>
              void run(
                () =>
                  settlementCalls.source(id, form.recoveryId, {
                    source: form.source,
                    reason: form.reason.trim(),
                    orderRef: form.orderRef.trim() || undefined,
                    orderDate: form.orderDate || undefined,
                    issuingAuthority: form.authority.trim() || undefined,
                    order: form.file ?? undefined,
                  }),
                'o05.sourceChanged',
              ).then((ok) => ok && close())
            }
          >
            {t('common.save')}
          </button>
          <button type="button" className="btn" onClick={close}>
            {t('common.cancel')}
          </button>
        </div>
      </div>
    </Modal>
  );
}
