'use client';

import { useCallback, useId, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { businessToday } from '@/shared/time';
import { date, Problem, ServerPagination, TextAreaField, useDialogFocus, useToast } from '@/shared/ui';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { Act, Explained, KvCard, Locked, RowActs, Tbl, ViewDialog } from '@/shared/ui/wireframe';
import type { CityClassRow, DearnessRow } from '../api/mastersApi';
import { arrearImpact, daFormProblem, formatRate, versionTone } from '../business/masterRules';
import {
  masterKeys,
  masterPages,
  staffRateOrderUrl,
  useAttachStaffRateOrder,
  useProposeRate,
  useRateDocumentTypes,
  useStaffRateOrders,
} from '../orchestration/masterQueries';
import { useCurrentCirculars } from '../orchestration/mdmQueries';
import { RegisterSection, SimulateAct, useApproveRow, useMasterRights } from './MasterScreenParts';
import { RateMasters } from './RateMasters';

/**
 * M-04 Staff Rate Tables as Wireframes v6.1 draws it (a frozen requirement per OD-121; SRS M-04 field
 * table, SRS-1-62, SRS-11-15, DDL rate_set / rate_set_line): "🧪 Simulate" and "＋ New Rate Version"
 * over the screen; the New Dearness Allowance Rate Version form; the Dearness Allowance table, one row
 * per version; HRA & CCA by city class; the Overtime Allowance bases; the order copy of the chosen
 * version. Saving a DA version asks for its Active M-25 circular and reason (SRS-1-62) and is a dated
 * proposal for the Payroll Checker. The rate sets with every line, their history and the HRA / CCA
 * version editor stay in the closed register below.
 */
export function StaffRates() {
  const { t } = useI18n();
  const rights = useMasterRights('M-04');
  const approve = useApproveRow();
  const [register, setRegister] = useState(false);
  const [pattern, setPattern] = useState('');
  const [viewing, setViewing] = useState<DearnessRow | null>(null);
  const [viewingCity, setViewingCity] = useState<CityClassRow | null>(null);
  const [selected, setSelected] = useState<DearnessRow | null>(null);
  const shown = useCallback((rows: DearnessRow[]) => {
    setSelected((prev) => (prev ? (rows.find((r) => r.id === prev.id) ?? prev) : (rows[0] ?? null)));
  }, []);

  function focusForm(code = '') {
    setPattern(code);
    document.getElementById('daRateForm')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <SimulateAct label={t('m04.simulate')} />
        {rights.drawn ? (
          <Act label={t('m04.newVersion')} kind="pri" onClick={() => focusForm()} />
        ) : (
          <Locked label={t('m04.newVersion')} why={t('m04.why.adminOnly')} />
        )}
      </div>
      <div className="card fl-mb" id="daRateForm">
        <div className="hd">
          <h3>{t('m04.form.title')}</h3>
        </div>
        <div className="bd">
          {rights.drawn ? (
            <DaForm key={pattern} pattern={pattern} entryWhy={rights.entryWhy} />
          ) : (
            <p className="sub2">{t('m04.form.adminOnly')}</p>
          )}
        </div>
      </div>
      <Tbl
        title={t('m04.da.title')}
        acts={<Act label={t('mst.history')} kind="sm" onClick={() => setRegister(true)} />}
        cols={[
          { t: t('m04.col.pattern'), s: 'ENT' },
          { t: t('m04.col.rate'), s: 'ENT', num: true },
          { t: t('m04.col.computedOn'), s: 'RULE' },
          { t: t('m04.col.from'), s: 'ENT' },
          { t: t('m04.col.to'), s: 'SYS' },
          { t: t('m04.col.version'), s: 'SYS' },
          { t: t('m04.col.arrear'), s: 'CALC' },
          { t: t('m04.col.status'), s: 'SYS' },
          { t: t('m04.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={masterKeys.dearness}
          fetchPage={masterPages.dearness}
          personal={false}
          empty={<p className="fl-hint">{t('m04.da.none')}</p>}
          onRows={shown}
        >
          {(rows) =>
            rows.map((r) => (
              <tr
                key={r.id}
                aria-selected={selected?.id === r.id}
                className={selected?.id === r.id ? 'fl-row-selected' : undefined}
                onClick={() => setSelected(r)}
              >
                <td>{r.pattern}</td>
                <td className="num">{r.rate === null ? '—' : formatRate(r.rate, 'PERCENT')}</td>
                <td>{r.computedOn ?? t(`m04.computedOn.${r.pattern}`)}</td>
                <td>{date(r.effectiveFrom)}</td>
                <td>{r.effectiveTo ? date(r.effectiveTo) : '—'}</td>
                <td>v{r.versionNo}</td>
                <td>{arrearImpact(r.arrearReference, r.arrearAmount)}</td>
                <td>
                  <span className={`pill ${versionTone(r.status)}`}>{t(`m04.status.${r.status}`)}</span>
                </td>
                <td onClick={(e) => e.stopPropagation()}>
                  <RowActs
                    viewName={`${t('common.view')} ${r.code} v${r.versionNo}`}
                    editName={`${t('common.edit')} ${r.code} v${r.versionNo}`}
                    onView={() => setViewing(r)}
                    onEdit={rights.drawn && r.status === 'ACTIVE' ? () => focusForm(r.pattern) : undefined}
                    editWhy={editWhy(t, r, rights.drawn)}
                    onApprove={rights.checker ? () => approve(r.status === 'AWAITING_APPROVAL') : undefined}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Tbl
          title={t('m04.city.title')}
          cols={[
            { t: t('m04.col.cityClass'), s: 'ENT' },
            { t: t('m04.col.hra'), s: 'ENT', num: true },
            { t: t('m04.col.cca'), s: 'ENT', num: true },
            { t: t('m04.col.suppression'), s: 'RULE' },
            { t: t('m04.col.status'), s: 'SYS' },
            { t: t('m04.col.actions'), s: 'SYS' },
          ]}
        >
          <ServerPagination
            queryKey={masterKeys.cityClasses}
            fetchPage={masterPages.cityClasses}
            personal={false}
          >
            {(rows) =>
              rows.map((c) => (
                <tr key={c.cityClass}>
                  <td>
                    <b>{c.cityClass === 'DOUBLE' ? t('m04.city.DOUBLE') : c.cityClass}</b>
                    {c.cityClass === 'DOUBLE' ? '' : ` — ${t(`m04.city.${c.cityClass}`)}`}
                  </td>
                  <td className="num">{hraText(t, c)}</td>
                  <td className="num">{c.ccaAmount === null ? '—' : formatRate(c.ccaAmount, 'NUM')}</td>
                  <td>{t(c.cityClass === 'DOUBLE' ? 'm04.suppression.double' : 'm04.suppression.lease')}</td>
                  <td>
                    <span className={`pill ${c.inForce ? 'p-ok' : 'p-bad'}`}>
                      {t(c.inForce ? 'm04.status.ACTIVE' : 'mst.notSeeded')}
                    </span>
                  </td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${c.cityClass}`}
                      editName={`${t('common.edit')} ${c.cityClass}`}
                      onView={() => setViewingCity(c)}
                      onEdit={rights.drawn ? () => setRegister(true) : undefined}
                      editWhy={t('m04.why.adminOnly')}
                      onApprove={rights.checker ? () => approve(c.pending) : undefined}
                    />
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
        <KvCard
          title={t('m04.ota.title')}
          rows={[
            { k: t('m04.ota.staff'), v: t('m04.ota.staffV'), s: 'RULE' },
            { k: t('m04.ota.cap'), v: t('m04.ota.capV'), s: 'RULE' },
            { k: t('m04.ota.dps'), v: t('m04.ota.dpsV'), s: 'RULE' },
            { k: t('m04.ota.nwnp'), v: t('m04.ota.nwnpV'), s: 'RULE' },
            { k: t('m04.ota.note'), v: t('m04.ota.noteV'), s: 'SYS' },
          ]}
        />
      </div>
      <OrderCard version={selected} canAttach={rights.mayCreate} drawn={rights.drawn} />
      <RegisterSection
        id="m04-register"
        label={t('mst.register.M-04')}
        open={register}
        onToggle={setRegister}
      >
        <RateMasters screen="M-04" />
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-04')}
          onClose={() => setViewing(null)}
          actions={<HistoryButton entity="rate_set" recordKey={viewing.code} />}
          rows={[
            { k: t('m04.col.pattern'), v: viewing.pattern, s: 'ENT' },
            {
              k: t('m04.col.rate'),
              v: viewing.rate === null ? '—' : formatRate(viewing.rate, 'PERCENT'),
              s: 'ENT',
            },
            {
              k: t('m04.col.computedOn'),
              v: viewing.computedOn ?? t(`m04.computedOn.${viewing.pattern}`),
              s: 'RULE',
            },
            { k: t('m04.col.from'), v: date(viewing.effectiveFrom), s: 'ENT' },
            { k: t('m04.col.to'), v: viewing.effectiveTo ? date(viewing.effectiveTo) : '—', s: 'SYS' },
            { k: t('m04.col.version'), v: `v${viewing.versionNo}`, s: 'SYS' },
            {
              k: t('m04.col.arrear'),
              v: arrearImpact(viewing.arrearReference, viewing.arrearAmount),
              s: 'CALC',
            },
            { k: t('m04.col.status'), v: t(`m04.status.${viewing.status}`), s: 'SYS' },
          ]}
        />
      )}
      {viewingCity && (
        <ViewDialog
          screen={t('screen.M-04')}
          onClose={() => setViewingCity(null)}
          rows={[
            { k: t('m04.col.cityClass'), v: viewingCity.cityClass, s: 'ENT' },
            { k: t('m04.col.hra'), v: hraText(t, viewingCity), s: 'ENT' },
            {
              k: t('m04.col.cca'),
              v: viewingCity.ccaAmount === null ? '—' : formatRate(viewingCity.ccaAmount, 'NUM'),
              s: 'ENT',
            },
            {
              k: t('m04.col.suppression'),
              v: t(viewingCity.cityClass === 'DOUBLE' ? 'm04.suppression.double' : 'm04.suppression.lease'),
              s: 'RULE',
            },
          ]}
        />
      )}
    </div>
  );
}

type T = (key: string, args?: (string | number)[]) => string;

function editWhy(t: T, r: DearnessRow, drawn: boolean): string {
  if (!drawn) return t('m04.why.adminOnly');
  if (r.status === 'SUPERSEDED') return t('m04.why.superseded');
  return r.status === 'AWAITING_APPROVAL' ? t('m04.why.awaiting') : t('m04.why.notInForce');
}

/** The HRA of a class: one figure where IDA and CDA agree, both where they differ; the Double row's rule. */
function hraText(t: T, c: CityClassRow): string {
  if (c.cityClass === 'DOUBLE') return t('m04.hra.double');
  if (c.hraPercent === null && c.hraCdaPercent === null) return '—';
  if (c.hraCdaPercent === null || c.hraCdaPercent === c.hraPercent)
    return formatRate(c.hraPercent ?? c.hraCdaPercent ?? 0, 'PERCENT');
  return t('m04.hra.both', [
    c.hraPercent === null ? '—' : formatRate(c.hraPercent, 'PERCENT'),
    formatRate(c.hraCdaPercent, 'PERCENT'),
  ]);
}

/** The drawn form; Save asks for the Active M-25 circular and the reason (SRS-1-62, SRS M-04 field table). */
function DaForm({ pattern: chosen, entryWhy }: { pattern: string; entryWhy: string | null }) {
  const { t } = useI18n();
  const [draft, setDraft] = useState({ pattern: chosen, rate: '', basis: '', from: '' });
  const [tried, setTried] = useState(false);
  const [saving, setSaving] = useState(false);
  const problem = daFormProblem(draft);
  const show = (key: string) => tried && problem === key;
  return (
    <>
      <p className="sub2">{t('m04.form.note')}</p>
      <div className="rf-grid">
        <div className={`rf-field${show('m04.err.pattern') ? ' rf-invalid' : ''}`}>
          <label htmlFor="da_pattern">
            {t('m04.form.pattern')} <span className="req">*</span>
          </label>
          <select
            id="da_pattern"
            required
            value={draft.pattern}
            onChange={(e) => setDraft({ ...draft, pattern: e.target.value })}
          >
            <option value="">{t('m04.form.select')}</option>
            <ReferenceOptions list="DA_RATE_PATTERN" />
          </select>
          {show('m04.err.pattern') && <span className="err">{t('m04.err.pattern')}</span>}
        </div>
        <div className={`rf-field${show('m04.err.rate') ? ' rf-invalid' : ''}`}>
          <label htmlFor="da_rate">
            {t('m04.form.rate')} <span className="req">*</span>
          </label>
          <input
            id="da_rate"
            type="number"
            required
            min={0}
            max={100}
            step="0.5"
            placeholder={t('m04.form.ratePh')}
            value={draft.rate}
            onChange={(e) => setDraft({ ...draft, rate: e.target.value })}
          />
          <span className="hint">{t('m04.form.rateHint')}</span>
          {show('m04.err.rate') && <span className="err">{t('m04.err.rate')}</span>}
        </div>
        <div className={`rf-field span2${show('m04.err.basis') ? ' rf-invalid' : ''}`}>
          <label htmlFor="da_basis">
            {t('m04.form.basis')} <span className="req">*</span>
          </label>
          <input
            id="da_basis"
            type="text"
            required
            maxLength={200}
            placeholder={t('m04.form.basisPh')}
            value={draft.basis}
            onChange={(e) => setDraft({ ...draft, basis: e.target.value })}
          />
          {show('m04.err.basis') && <span className="err">{t('m04.err.basis')}</span>}
        </div>
        <div className={`rf-field${show('m04.err.from') ? ' rf-invalid' : ''}`}>
          <label htmlFor="da_eff">
            {t('m04.form.from')} <span className="req">*</span>
          </label>
          <input
            id="da_eff"
            type="date"
            required
            value={draft.from}
            onChange={(e) => setDraft({ ...draft, from: e.target.value })}
          />
          <span className="hint">{t('m04.form.fromHint')}</span>
          {show('m04.err.from') && <span className="err">{t('m04.err.from')}</span>}
        </div>
      </div>
      <div className="rf-actbar">
        {entryWhy ? (
          <Explained label={t('m04.form.save')} kind="pri" why={entryWhy} />
        ) : (
          <Act
            label={t('m04.form.save')}
            kind="pri"
            onClick={() => {
              setTried(true);
              if (problem === null) setSaving(true);
            }}
          />
        )}
      </div>
      {saving && (
        <SubmitDialog
          draft={draft}
          onClose={() => setSaving(false)}
          onDone={() => {
            setSaving(false);
            setTried(false);
            setDraft({ pattern: '', rate: '', basis: '', from: '' });
          }}
        />
      )}
    </>
  );
}

/** The two fields SRS-1-62 and SRS-G-1 add to the drawn form, asked at submission. */
function SubmitDialog({
  draft,
  onClose,
  onDone,
}: {
  draft: { pattern: string; rate: string; basis: string; from: string };
  onClose: () => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const dialog = useDialogFocus<HTMLDivElement>();
  const circulars = useCurrentCirculars();
  const proposal = useProposeRate('M-04');
  const ids = useId();
  const [circularId, setCircularId] = useState<number | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);

  async function submit() {
    setError(null);
    try {
      await proposal.mutateAsync({
        code: `DA_${draft.pattern}`,
        effectiveFrom: draft.from,
        reason,
        circularOrderId: circularId,
        computedOn: draft.basis.trim(),
        lines: [
          {
            designationId: null,
            categoryCode: null,
            payPattern: draft.pattern,
            basicSlabFrom: null,
            basicSlabTo: null,
            cityClass: null,
            rateCode: 'DA_PCT',
            rateValue: Number(draft.rate),
            unit: 'PERCENT',
          },
        ],
      });
      toast(t('mst.submitted', [t('m04.da.title')]));
      onDone();
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('m04.submit.title')}
      >
        <h2>{t('m04.submit.title')}</h2>
        <Problem error={error} />
        <div className="rf-grid">
          <div className="rf-field span2">
            <label htmlFor={`${ids}c`}>{t('mst.circular')}</label>
            <select
              id={`${ids}c`}
              value={circularId ?? ''}
              onChange={(e) => setCircularId(e.target.value === '' ? null : Number(e.target.value))}
            >
              <option value="">{t('common.choose')}</option>
              {(circulars.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.number} · {c.subject}
                </option>
              ))}
            </select>
          </div>
          <div className="rf-field span2">
            <label htmlFor={`${ids}r`}>{t('common.reason')}</label>
            <TextAreaField
              id={`${ids}r`}
              value={reason}
              maxLength={500}
              onChange={(e) => setReason(e.target.value)}
            />
          </div>
        </div>
        <p className="fl-hint">{t('mst.approvalNote')}</p>
        <div className="rf-actbar">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="btn pri"
            disabled={circularId === null || !reason.trim() || proposal.isPending}
            title={circularId === null ? t('mst.why.circular') : undefined}
            onClick={() => void submit()}
          >
            {t('common.submitForApproval')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** The order copy of the chosen DA version (SRS-1-62, SRS-G-30). */
function OrderCard({
  version,
  canAttach,
  drawn,
}: {
  version: DearnessRow | null;
  canAttach: boolean;
  drawn: boolean;
}) {
  const { t } = useI18n();
  const orders = useStaffRateOrders(version?.id ?? null);
  const types = useRateDocumentTypes();
  const attach = useAttachStaffRateOrder();
  return (
    <OrderCopyCard
      key={version?.id ?? 0}
      hint={t('m04.ord.hint')}
      canAttach={canAttach && version !== null}
      readOnly={!drawn}
      stored={orders.data}
      storedError={orders.error ?? types.error}
      fileHref={(fileId) => staffRateOrderUrl(version?.id ?? 0, fileId)}
      today={businessToday()}
      attachmentRequired={false}
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      onAttach={async (o) => {
        if (!version) return;
        for (const file of o.files) {
          await attach.mutateAsync({
            id: version.id,
            order: {
              documentType: o.documentType,
              reference: o.reference,
              orderDate: o.orderDate,
              issuingAuthority: o.issuingOffice,
              file,
            },
          });
        }
      }}
    />
  );
}
