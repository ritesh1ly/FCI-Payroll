'use client';

import { useState } from 'react';
import { useDocumentTypes } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  Card,
  dateTime,
  Empty,
  Problem,
  ServerPagination,
  Src,
  useToast,
  TextAreaField,
  FinancialYearSelect,
} from '@/shared/ui';
import type { Certificate, CertificateStatus, DocumentParticulars, Generation } from '../api/statutoryApi';
import {
  backgroundJobLink,
  isFinYear,
  mayReissue,
  mayUploadPartA,
  membersOf,
} from '../business/statutoryRules';
import {
  certificatePages,
  partAUrl,
  statutoryKeys,
  useCertificateActions,
  useMergedCount,
} from '../orchestration/statutoryQueries';
import { finYearOf } from './DeclarationReview';
import { rupees } from './money';

const STATUSES: readonly CertificateStatus[] = ['PENDING_PART_A', 'MERGED', 'RELEASED'];

/** The year before today's: Form 16 is issued after the year closes (date text, no time zone). */
function lastYear(): string {
  const today = businessToday();
  return finYearOf(`${Number(today.slice(0, 4)) - 1}${today.slice(4)}`);
}

/**
 * C-10 Annual Tax Certificate - Form 16 (SRS-7-5, SRS-7-9, SRS-7-19, SRS-7-21, SRS-7-23;
 * BR-TAX-010 … 012). The Payroll Maker of the paying unit generates Part B of a fully locked year
 * under the Active M-17 definition, uploads the Part A of the tax department and requests the
 * release; the release waits for the reconciliation to the posted TDS and the remitted challans and
 * for a different Payroll Checker (Approvals Inbox). A released certificate is reissued against a
 * correction return, never changed. The PAN shows masked with its state (SRS l.2589, SRS-7-23).
 */
export function Form16Register() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const actions = useCertificateActions();
  const [finYear, setFinYear] = useState(lastYear);
  const [status, setStatus] = useState('');
  const [population, setPopulation] = useState('');
  const chosen = membersOf(population);
  const [generation, setGeneration] = useState<Generation | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [partAFor, setPartAFor] = useState<Certificate | null>(null);
  const yearOk = isFinYear(finYear);
  const mayGenerate = can(me.data, 'C-10', 'CREATE');
  const mayRelease = can(me.data, 'C-10', 'SUBMIT');
  const mayEdit = can(me.data, 'C-10', 'EDIT');
  const mayQueue = mayGenerate && can(me.data, 'C-13', 'CREATE');
  // The whole paying unit may run in the background (SRS-NF-7); a chosen population stays here.
  const wholeUnit = chosen.ok && chosen.eis.length === 0;
  const merged = useMergedCount(finYear, mayRelease && yearOk);

  async function run<T>(action: () => Promise<T>, done: string, after?: (r: T) => void) {
    setError(null);
    try {
      const r = await action();
      toast(t(done));
      after?.(r);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <label>
          {t('i07.finYear')} <FinancialYearSelect value={finYear} onChange={setFinYear} />
        </label>
        <label>
          {t('i07.status')}{' '}
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">{t('i07.allStatuses')}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`c10.status.${s}`)}
              </option>
            ))}
          </select>
        </label>
        {mayGenerate && (
          <label>
            {t('c10.members')}{' '}
            <TextAreaField
              maxLength={4000}
              placeholder={t('c10.allMembers')}
              value={population}
              onChange={(e) => setPopulation(e.target.value)}
            />
          </label>
        )}
        {mayGenerate && (
          <button
            type="button"
            className="btn"
            disabled={!yearOk || !chosen.ok || actions.generate.isPending}
            title={chosen.ok ? undefined : t('c10.badMembers')}
            onClick={() =>
              void run(
                () => actions.generate.mutateAsync({ finYear, eis: chosen.eis }),
                'c10.generated',
                setGeneration,
              )
            }
          >
            {t('c10.generate')}
          </button>
        )}
        {mayQueue &&
          (yearOk && wholeUnit ? (
            <a className="btn" href={backgroundJobLink('TAX_FORM16_PART_B', { finYear })}>
              {t('tax.runInBackground')}
            </a>
          ) : (
            <button
              type="button"
              className="btn"
              disabled
              title={t(yearOk ? 'c10.backgroundWholeUnit' : 'c10.badYear')}
            >
              {t('tax.runInBackground')}
            </button>
          ))}
        {mayRelease && (
          <button
            type="button"
            className="btn"
            // UI audit S13 (BR-TAX-012): the release goes with MERGED certificates only.
            disabled={!yearOk || actions.release.isPending || merged.data === 0 || merged.data === undefined}
            title={yearOk && merged.data === 0 ? t('c10.releaseNoMerged') : undefined}
            onClick={() => void run(() => actions.release.mutateAsync(finYear), 'c10.releaseRequested')}
          >
            {t('c10.release')}
          </button>
        )}
      </div>
      <Problem error={error} />
      {generation && (
        <Card title={t('c10.generationOf', [generation.generated])}>
          {generation.skipped.length === 0 ? (
            <p className="sub2">{t('c10.noneSkipped')}</p>
          ) : (
            <ul className="fl-list" aria-label={t('c10.skipped')}>
              {generation.skipped.map((s) => (
                <li key={s.eis}>
                  {s.eis}: {s.reason}
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}
      <Card title={t('screen.C-10')}>
        {yearOk ? (
          <ServerPagination
            queryKey={statutoryKeys.certificates(finYear, status)}
            fetchPage={certificatePages.list(finYear, status)}
            empty={<Empty text={t('c10.none')} />}
          >
            {(rows) => (
              <table className="t" aria-label={t('screen.C-10')}>
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
                      {t('c10.pan')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('c10.gross')}
                      <Src s="CALC" />
                    </th>
                    <th>
                      {t('c07.taxableIncome')}
                      <Src s="CALC" />
                    </th>
                    <th>
                      {t('c10.tds')}
                      <Src s="CALC" />
                    </th>
                    <th>
                      {t('c10.partA')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('i07.status')}
                      <Src s="SYS" />
                    </th>
                    <th aria-label={t('common.actions')} />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((c) => (
                    <tr key={c.id} data-reference={`${c.eis}|${c.finYear}`}>
                      <td>{c.eis}</td>
                      <td>{c.name}</td>
                      <td>
                        {c.panMasked ?? '—'}
                        {c.panStatus && c.panStatus !== 'VALID' && (
                          <span className="pill p-bad">{t(`c10.panStatus.${c.panStatus}`)}</span>
                        )}
                      </td>
                      <td>{rupees(c.grossSalary)}</td>
                      <td>{rupees(c.taxableIncome)}</td>
                      <td>{rupees(c.taxDeducted)}</td>
                      <td>
                        {c.partADocumentId ? (
                          <a href={partAUrl(c.id)}>{t('c10.partA.RECEIVED')}</a>
                        ) : (
                          t('c10.partA.PENDING')
                        )}
                      </td>
                      <td>
                        {t(`c10.status.${c.status}`)}
                        <div className="sub2">{dateTime(c.releasedAt ?? c.partBGeneratedAt)}</div>
                      </td>
                      <td>
                        <div className="acts">
                          {mayEdit && mayUploadPartA(c) && (
                            <button
                              type="button"
                              className="ia"
                              aria-label={t('c10.partAOf', [c.eis ?? ''])}
                              onClick={() => setPartAFor(c)}
                            >
                              {t('c10.uploadPartA')}
                            </button>
                          )}
                          {mayEdit && mayReissue(c) && <Reissue certificate={c} />}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </ServerPagination>
        ) : (
          <p className="fl-hint">{t('i07.badYear')}</p>
        )}
      </Card>
      {partAFor && <PartA key={partAFor.id} certificate={partAFor} onClose={() => setPartAFor(null)} />}
    </div>
  );
}

/** The Part A of the tax department, with its particulars (SRS-7-9). */
function PartA({ certificate, onClose }: { certificate: Certificate; onClose: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const types = useDocumentTypes(true);
  const actions = useCertificateActions();
  const [file, setFile] = useState<File | null>(null);
  const [p, setP] = useState<DocumentParticulars>({
    documentType: 'CERTIFICATE',
    reference: '',
    documentDate: '',
    issuingAuthority: '',
  });
  const [error, setError] = useState<unknown>(null);
  const ready =
    file !== null &&
    p.documentDate !== '' &&
    p.documentDate <= businessToday() &&
    p.issuingAuthority.trim() !== '';

  async function upload() {
    if (!file) return;
    setError(null);
    try {
      await actions.partA.mutateAsync({ id: certificate.id, particulars: p, file });
      toast(t('c10.partAMerged'));
      onClose();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('c10.partAFor', [certificate.eis ?? '', certificate.finYear])}>
      <Problem error={error} />
      <div className="rf-grid">
        <div className="rf-field">
          <label htmlFor="c10-file">{t('c10.partAFile')}</label>
          <input
            id="c10-file"
            type="file"
            accept="application/pdf"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
        </div>
        <div className="rf-field">
          <label htmlFor="c10-type">{t('i18.documentType')}</label>
          <select
            id="c10-type"
            value={p.documentType}
            onChange={(e) => setP({ ...p, documentType: e.target.value })}
          >
            {(types.data ?? []).map((d) => (
              <option key={d.code} value={d.code}>
                {d.name}
              </option>
            ))}
          </select>
        </div>
        <div className="rf-field">
          <label htmlFor="c10-ref">{t('c10.partARef')}</label>
          <input
            id="c10-ref"
            maxLength={60}
            value={p.reference}
            onChange={(e) => setP({ ...p, reference: e.target.value })}
          />
        </div>
        <div className="rf-field">
          <label htmlFor="c10-date">{t('i18.documentDate')}</label>
          <input
            id="c10-date"
            type="date"
            max={businessToday()}
            value={p.documentDate}
            onChange={(e) => setP({ ...p, documentDate: e.target.value })}
          />
        </div>
        <div className="rf-field span2">
          <label htmlFor="c10-issuer">{t('i18.documentIssuer')}</label>
          <input
            id="c10-issuer"
            maxLength={200}
            value={p.issuingAuthority}
            onChange={(e) => setP({ ...p, issuingAuthority: e.target.value })}
          />
        </div>
      </div>
      <div className="rf-actbar">
        <button
          type="button"
          className="btn btn-primary"
          disabled={!ready || actions.partA.isPending}
          title={ready ? undefined : t('c10.partAFirst')}
          onClick={() => void upload()}
        >
          {t('c10.uploadPartA')}
        </button>
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
    </Card>
  );
}

/** A released certificate reissued against a correction return, with the reason. */
function Reissue({ certificate }: { certificate: Certificate }) {
  const { t } = useI18n();
  const toast = useToast();
  const actions = useCertificateActions();
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  return (
    <span>
      <TextAreaField
        aria-label={t('c10.reissueReasonOf', [certificate.eis ?? ''])}
        rows={2}
        maxLength={1000}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
      />
      <button
        type="button"
        className="ia"
        aria-label={t('c10.reissueOf', [certificate.eis ?? ''])}
        disabled={reason.trim() === '' || actions.reissue.isPending}
        title={reason.trim() === '' ? t('i07.reasonFirst') : undefined}
        onClick={() =>
          void actions.reissue
            .mutateAsync({ id: certificate.id, reason: reason.trim() })
            .then(() => toast(t('c10.reissued')))
            .catch(setError)
        }
      >
        {t('c10.reissue')}
      </button>
      <Problem error={error} />
    </span>
  );
}
