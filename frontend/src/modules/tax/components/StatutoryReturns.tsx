'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  Card,
  date,
  dateTime,
  Empty,
  Problem,
  ServerPagination,
  Src,
  useToast,
  FinancialYearSelect,
} from '@/shared/ui';
import type { NewFiling, Quarter, StatutoryReturn } from '../api/statutoryApi';
import {
  backgroundJobLink,
  challanFileProblem,
  challanProblems,
  correctable,
  filingProblems,
  isFinYear,
  openQuarters,
} from '../business/statutoryRules';
import {
  evidenceUrl,
  returnPages,
  statutoryKeys,
  useChallans,
  useReturnActions,
  useReturnsOfYear,
} from '../orchestration/statutoryQueries';
import { finYearOf } from './DeclarationReview';
import { rupees } from './money';

const QUARTERS: readonly Quarter[] = ['Q1', 'Q2', 'Q3', 'Q4'];

function blankFiling(): NewFiling {
  return {
    filingDate: businessToday(),
    signatoryName: '',
    signatoryDesignation: '',
    acknowledgementToken: '',
    evidence: {
      documentType: 'CERTIFICATE',
      reference: '',
      documentDate: businessToday(),
      issuingAuthority: '',
    },
    file: null,
  };
}

/**
 * C-11 Statutory Returns - Form 24Q (SRS-7-7, SRS-7-8, SRS-7-13, SRS-7-21, SRS-7-23, SRS-7-31). The
 * Payroll Maker of the paying unit imports the challan file, generates the return of a locked
 * quarter (one original per quarter; a correction against a released or filed one), validates it
 * (each failed check itemised) and requests the release; a different Payroll Checker releases it
 * in the Approvals Inbox; the Maker records the filing by FCI's authorised signatory. PANs masked.
 */
export function StatutoryReturns() {
  const { t } = useI18n();
  const me = useMe();
  const [finYear, setFinYear] = useState(finYearOf(businessToday()));
  const [open, setOpen] = useState<StatutoryReturn | null>(null);
  const yearOk = isFinYear(finYear);
  const mayCreate = can(me.data, 'C-11', 'CREATE');
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <label>
          {t('i07.finYear')} <FinancialYearSelect value={finYear} onChange={setFinYear} />
        </label>
        <span className="sub2">{t('c11.hint')}</span>
      </div>
      {yearOk ? (
        <>
          <Challans finYear={finYear} mayImport={mayCreate} />
          {mayCreate && <Generate finYear={finYear} />}
          <Card title={t('screen.C-11')}>
            <ServerPagination
              queryKey={statutoryKeys.returns(finYear)}
              fetchPage={returnPages.list(finYear)}
              empty={<Empty text={t('c11.none')} />}
            >
              {(rows) => (
                <table className="t" aria-label={t('screen.C-11')}>
                  <thead>
                    <tr>
                      <th>
                        {t('c11.type')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('c11.quarter')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('c11.deductees')}
                        <Src s="CALC" />
                      </th>
                      <th>
                        {t('c10.tds')}
                        <Src s="CALC" />
                      </th>
                      <th>
                        {t('i07.status')}
                        <Src s="SYS" />
                      </th>
                      <th aria-label={t('common.actions')} />
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((r) => (
                      <tr key={r.id} data-reference={`${r.type}|${r.quarter}|${r.id}`}>
                        <td>
                          {t(`c11.type.${r.type}`)}
                          {r.correctsReturnId && (
                            <div className="sub2">{t('c11.corrects', [r.correctsReturnId])}</div>
                          )}
                        </td>
                        <td>{r.quarter}</td>
                        <td>{r.deductees}</td>
                        <td>{rupees(r.totalTds)}</td>
                        <td>
                          {t(`c11.status.${r.status}`)}
                          <div className="sub2">{dateTime(r.generatedAt)}</div>
                        </td>
                        <td>
                          <button
                            type="button"
                            className="ia"
                            aria-label={t('c11.openOf', [r.id])}
                            onClick={() => setOpen(r)}
                          >
                            {t('common.view')}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </ServerPagination>
          </Card>
          {open && <ReturnDetail key={open.id} r={open} onClose={() => setOpen(null)} />}
        </>
      ) : (
        <p className="fl-hint">{t('i07.badYear')}</p>
      )}
    </div>
  );
}

/** SRS-7-21: the challans of the year and the import of the challan file. */
function Challans({ finYear, mayImport }: { finYear: string; mayImport: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const challans = useChallans(finYear, true);
  const actions = useReturnActions();
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [duplicates, setDuplicates] = useState<string[]>([]);
  const problem = challanFileProblem(file);

  async function upload() {
    if (!file) return;
    setError(null);
    try {
      const r = await actions.importChallans.mutateAsync({ finYear, file });
      setDuplicates(r.duplicates);
      toast(t('c11.imported', [r.imported]));
      setFile(null);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('c11.challans')}>
      <Problem error={error ?? challans.error} />
      {(challans.data ?? []).length === 0 ? (
        <Empty text={t('c11.noChallans')} />
      ) : (
        <table className="t" aria-label={t('c11.challans')}>
          <thead>
            <tr>
              <th>
                {t('c11.cin')}
                <Src s="INTF" />
              </th>
              <th>
                {t('c11.bsr')}
                <Src s="INTF" />
              </th>
              <th>
                {t('c11.challanDate')}
                <Src s="INTF" />
              </th>
              <th>
                {t('i18.amount')}
                <Src s="INTF" />
              </th>
              <th>
                {t('c11.period')}
                <Src s="INTF" />
              </th>
            </tr>
          </thead>
          <tbody>
            {(challans.data ?? []).map((c) => (
              <tr key={c.id}>
                <td>{c.cin}</td>
                <td>{c.bsr}</td>
                <td>{date(c.date)}</td>
                <td>{rupees(c.amount)}</td>
                <td>{c.period}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {duplicates.length > 0 && (
        <p className="sub2" aria-label={t('c11.duplicates')}>
          {t('c11.duplicatesList', [duplicates.join('; ')])}
        </p>
      )}
      {mayImport && (
        <div className="toolbar">
          <label>
            {t('c11.challanFile')}{' '}
            <input
              type="file"
              accept=".csv,text/csv,text/plain"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          </label>
          <button
            type="button"
            className="btn"
            disabled={problem !== null || actions.importChallans.isPending}
            title={problem ? t(problem) : undefined}
            onClick={() => void upload()}
          >
            {t('c11.import')}
          </button>
          <span className="sub2">{t('c11.fileFormat')}</span>
        </div>
      )}
      {mayImport && <KeyedChallan finYear={finYear} />}
    </Card>
  );
}

const NO_CHALLAN = { cin: '', bsr: '', date: '', amount: '', reference: '', period: '' };

/**
 * SRS C-11 field table (l.2625) "Challan File Import / User Input": the Payroll Maker keys a
 * challan the file did not carry; the server checks it as a file line and refuses one already held.
 */
function KeyedChallan({ finYear }: { finYear: string }) {
  const { t } = useI18n();
  const toast = useToast();
  const actions = useReturnActions();
  const [draft, setDraft] = useState(NO_CHALLAN);
  const [error, setError] = useState<unknown>(null);
  const problems = challanProblems(draft);
  const field = (key: keyof typeof NO_CHALLAN, label: string, max: number, type = 'text') => (
    <div className="rf-field">
      <label htmlFor={`c11-k-${key}`}>{t(label)}</label>
      <input
        id={`c11-k-${key}`}
        type={type}
        maxLength={type === 'date' ? undefined : max}
        value={draft[key]}
        onChange={(e) => setDraft({ ...draft, [key]: e.target.value })}
      />
    </div>
  );

  async function send() {
    setError(null);
    try {
      const saved = await actions.enterChallan.mutateAsync({
        finYear,
        challan: { ...draft, cin: draft.cin.trim(), bsr: draft.bsr.trim(), period: draft.period.trim() },
      });
      toast(t('c11.challanKeyed', [saved.cin]));
      setDraft(NO_CHALLAN);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <fieldset className="bd rf-grid" aria-label={t('c11.keyChallan')}>
      <legend>{t('c11.keyChallan')}</legend>
      {field('cin', 'c11.cin', 30)}
      {field('bsr', 'c11.bsr', 20)}
      {field('date', 'c11.challanDate', 10, 'date')}
      {field('amount', 'i18.amount', 19)}
      {field('reference', 'c11.reference', 50)}
      {field('period', 'c11.period', 6)}
      {problems.length > 0 && (
        <ul className="fl-list span2" aria-label={t('c11.keyProblems')}>
          {problems.map((p) => (
            <li key={p}>{t(p)}</li>
          ))}
        </ul>
      )}
      <Problem error={error} />
      <div className="rf-actbar">
        <button
          type="button"
          className="btn"
          disabled={problems.length > 0 || actions.enterChallan.isPending}
          onClick={() => void send()}
        >
          {t('c11.addChallan')}
        </button>
      </div>
    </fieldset>
  );
}

/** SRS-7-7: the original of a quarter that has none, or the correction of a released or filed one. */
function Generate({ finYear }: { finYear: string }) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const mayQueue = can(me.data, 'C-13', 'CREATE');
  const actions = useReturnActions();
  const year = useReturnsOfYear(finYear, true);
  const rows = year.data?.items ?? [];
  const quarters = openQuarters(rows);
  const corrections = correctable(rows);
  const [quarter, setQuarter] = useState<string>('');
  const [corrects, setCorrects] = useState<string>('');
  const [error, setError] = useState<unknown>(null);
  const target = corrections.find((r) => String(r.id) === corrects) ?? null;

  async function generate() {
    setError(null);
    try {
      await actions.generate.mutateAsync({
        finYear,
        quarter: (target ? target.quarter : quarter) as Quarter,
        corrects: target ? target.id : null,
      });
      toast(t('c11.generated'));
      setQuarter('');
      setCorrects('');
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('c11.generate')}>
      <Problem error={error} />
      <div className="toolbar">
        <label>
          {t('c11.quarter')}{' '}
          <select value={quarter} disabled={target !== null} onChange={(e) => setQuarter(e.target.value)}>
            <option value="">—</option>
            {QUARTERS.filter((q) => quarters.includes(q)).map((q) => (
              <option key={q} value={q}>
                {q}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('c11.correctionOf')}{' '}
          <select value={corrects} onChange={(e) => setCorrects(e.target.value)}>
            <option value="">{t('c11.original')}</option>
            {corrections.map((r) => (
              <option key={r.id} value={String(r.id)}>
                {t('c11.returnOf', [r.id, r.quarter])}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="btn btn-primary"
          disabled={(target === null && quarter === '') || actions.generate.isPending}
          title={target === null && quarter === '' ? t('c11.chooseQuarter') : undefined}
          onClick={() => void generate()}
        >
          {t('c11.generate')}
        </button>
        {mayQueue &&
          (target === null && quarter !== '' ? (
            <a className="btn" href={backgroundJobLink('TAX_24Q_RETURN', { finYear, quarter })}>
              {t('tax.runInBackground')}
            </a>
          ) : (
            <button
              type="button"
              className="btn"
              disabled
              title={t(target === null ? 'c11.chooseQuarter' : 'c11.backgroundOriginalOnly')}
            >
              {t('tax.runInBackground')}
            </button>
          ))}
      </div>
      {mayQueue && <p className="fl-hint">{t('c11.backgroundHint')}</p>}
    </Card>
  );
}

function ReturnDetail({ r, onClose }: { r: StatutoryReturn; onClose: () => void }) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const actions = useReturnActions();
  const [current, setCurrent] = useState(r);
  const [requested, setRequested] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const maySubmit = can(me.data, 'C-11', 'SUBMIT');
  const mayEdit = can(me.data, 'C-11', 'EDIT');

  async function run(action: () => Promise<unknown>, done: string, after?: () => void) {
    setError(null);
    try {
      const out = await action();
      if (out && typeof out === 'object' && 'status' in out) setCurrent(out as StatutoryReturn);
      after?.();
      toast(t(done));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('c11.returnOf', [current.id, current.quarter])}>
      <Problem error={error} />
      <p>
        {t(`c11.type.${current.type}`)} · {t(`c11.status.${current.status}`)}
        {current.validationUtility && (
          <span className="sub2"> · {t('c11.utility', [current.validationUtility])}</span>
        )}
        {current.releasedBy && <span className="sub2"> · {t('c11.releasedBy', [current.releasedBy])}</span>}
      </p>
      {/* SRS l.2604-2606: each status names its next step (a failed validation: correct and regenerate). */}
      <p className="fl-hint">{t(`c11.next.${current.status}`)}</p>
      {current.validationResult.length > 0 && (
        <ul className="bd fl-list" aria-label={t('c11.rejections')}>
          {current.validationResult.map((line) => (
            <li key={line}>
              <span className="pill p-warn">{t('c11.rejected')}</span> {line}
            </li>
          ))}
        </ul>
      )}
      <div className="rf-actbar">
        {maySubmit && current.status === 'GENERATED' && (
          <button
            type="button"
            className="btn"
            disabled={actions.validate.isPending}
            onClick={() => void run(() => actions.validate.mutateAsync(current.id), 'c11.validatedDone')}
          >
            {t('c11.validate')}
          </button>
        )}
        {maySubmit && current.status === 'VALIDATED' && (
          <button
            type="button"
            className="btn btn-primary"
            // UI audit S13: once requested, the release waits for the Checker; a second request
            // would be refused, so the button says so until the return is reopened.
            disabled={actions.release.isPending || requested}
            title={requested ? t('c11.releasePending') : undefined}
            onClick={() =>
              void run(
                () => actions.release.mutateAsync(current.id),
                'c11.releaseRequested',
                () => setRequested(true),
              )
            }
          >
            {t('c11.release')}
          </button>
        )}
      </div>
      <Deductees id={current.id} />
      {current.filings.length > 0 && (
        <ul className="fl-list" aria-label={t('c11.filings')}>
          {current.filings.map((f) => (
            <li key={`${f.filingDate}|${f.signatoryName}`}>
              {t('c11.filingLine', [
                date(f.filingDate),
                f.signatoryName,
                f.signatoryDesignation,
                f.acknowledgementToken ?? '—',
              ])}{' '}
              {f.evidenceId && <a href={evidenceUrl(current.id, f.evidenceId)}>{t('c11.evidence')}</a>}
            </li>
          ))}
        </ul>
      )}
      {mayEdit && current.status === 'RELEASED' && <FilingForm r={current} onFiled={setCurrent} />}
      <div className="rf-actbar">
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
    </Card>
  );
}

/** The deductees of a return a page at a time, the PAN masked (SRS-7-23). */
function Deductees({ id }: { id: number }) {
  const { t } = useI18n();
  return (
    <ServerPagination
      queryKey={statutoryKeys.deductees(id)}
      fetchPage={returnPages.deductees(id)}
      empty={<Empty text={t('c11.noDeductees')} />}
    >
      {(rows) => (
        <table className="t" aria-label={t('c11.deductees')}>
          <thead>
            <tr>
              <th>
                {t('m01.eis')}
                <Src s="HRMS" />
              </th>
              <th>
                {t('i07.member')}
                <Src s="HRMS" />
              </th>
              <th>
                {t('c07.pan')}
                <Src s="HRMS" />
              </th>
              <th>
                {t('c11.amountPaid')}
                <Src s="CALC" />
              </th>
              <th>
                {t('c10.tds')}
                <Src s="CALC" />
              </th>
              <th>
                {t('c11.challan')}
                <Src s="SYS" />
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((d) => (
              <tr key={d.employeeId}>
                <td>{d.eis}</td>
                <td>{d.name}</td>
                <td>
                  {d.panMasked ?? '—'}
                  {!d.panValid && <span className="pill p-warn"> {t('c07.pan.MISSING')}</span>}
                  {d.higherRateApplied && <div className="sub2">{t('c07.higherRate')}</div>}
                </td>
                <td>{rupees(d.amountPaid)}</td>
                <td>{rupees(d.taxDeducted)}</td>
                <td>{d.challanReference ?? (d.challanId === null ? t('c11.noChallan') : d.challanId)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </ServerPagination>
  );
}

/** SRS-7-31: the filing by FCI's authorised signatory, with its acknowledgement. */
function FilingForm({ r, onFiled }: { r: StatutoryReturn; onFiled: (r: StatutoryReturn) => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const actions = useReturnActions();
  const [f, setF] = useState<NewFiling>(blankFiling);
  const [error, setError] = useState<unknown>(null);
  const problems = filingProblems(f, businessToday());

  async function file() {
    setError(null);
    try {
      onFiled(await actions.filing.mutateAsync({ id: r.id, filing: f }));
      toast(t('c11.filed'));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="bd">
      <h3>{t('c11.recordFiling')}</h3>
      <Problem error={error} />
      <div className="rf-grid">
        <div className="rf-field">
          <label htmlFor="c11-date">{t('c11.filingDate')}</label>
          <input
            id="c11-date"
            type="date"
            max={businessToday()}
            value={f.filingDate}
            onChange={(e) => setF({ ...f, filingDate: e.target.value })}
          />
        </div>
        <div className="rf-field">
          <label htmlFor="c11-signatory">{t('c11.signatory')}</label>
          <input
            id="c11-signatory"
            maxLength={150}
            value={f.signatoryName}
            onChange={(e) => setF({ ...f, signatoryName: e.target.value })}
          />
        </div>
        <div className="rf-field">
          <label htmlFor="c11-designation">{t('c11.designation')}</label>
          <input
            id="c11-designation"
            maxLength={150}
            value={f.signatoryDesignation}
            onChange={(e) => setF({ ...f, signatoryDesignation: e.target.value })}
          />
        </div>
        <div className="rf-field">
          <label htmlFor="c11-token">{t('c11.token')}</label>
          <input
            id="c11-token"
            maxLength={100}
            value={f.acknowledgementToken}
            onChange={(e) => setF({ ...f, acknowledgementToken: e.target.value })}
          />
        </div>
        <div className="rf-field">
          <label htmlFor="c11-evidence">{t('c11.evidence')}</label>
          <input
            id="c11-evidence"
            type="file"
            accept="application/pdf,image/jpeg,image/png"
            onChange={(e) => setF({ ...f, file: e.target.files?.[0] ?? null })}
          />
        </div>
        {f.file && (
          <div className="rf-field">
            <label htmlFor="c11-issuer">{t('i18.documentIssuer')}</label>
            <input
              id="c11-issuer"
              maxLength={200}
              value={f.evidence.issuingAuthority}
              onChange={(e) => setF({ ...f, evidence: { ...f.evidence, issuingAuthority: e.target.value } })}
            />
          </div>
        )}
      </div>
      {problems.length > 0 && (
        <ul className="fl-list" aria-label={t('common.problems')}>
          {problems.map((p) => (
            <li key={p}>{t(p)}</li>
          ))}
        </ul>
      )}
      <div className="rf-actbar">
        <button
          type="button"
          className="btn btn-primary"
          disabled={problems.length > 0 || actions.filing.isPending}
          title={problems.length > 0 ? t('common.fixProblems') : undefined}
          onClick={() => void file()}
        >
          {t('c11.recordFiling')}
        </button>
      </div>
    </div>
  );
}
