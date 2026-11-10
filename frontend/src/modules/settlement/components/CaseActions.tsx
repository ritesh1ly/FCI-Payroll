'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { amount, date, TextAreaField, withBase } from '@/shared/ui';
import { Tbl } from '@/shared/ui/wireframe';
import type { CaseDetail } from '../api/settlementApi';
import {
  awaitsNoDues,
  mayExclude,
  mayRelease as stageAllowsRelease,
  maySubmit,
  outstanding,
} from '../business/settlementRules';
import { settlementCalls } from '../orchestration/settlementQueries';

/**
 * The functions of a case the drawing leaves out (brief: a function the drawing omits stays, behind a
 * closed section): the CPF final payment and invoice status for information (SRS-10-3, SRS-10-17),
 * the establishment no-dues (SRS-10-27), the Checker's exclusion (SRS-10-14), Submit (SRS-10-19),
 * Finance's Release (SRS-10-19) and the ex-gratia entries (SRS-10-25).
 */
export function CaseActions({
  d,
  mayCompute: maker,
  mayApprove,
  mayClear,
  mayRelease: finance,
  run,
  onExclude,
}: {
  d: CaseDetail;
  mayCompute: boolean;
  mayApprove: boolean;
  mayClear: boolean;
  mayRelease: boolean;
  run: (work: () => Promise<unknown>, done: string) => Promise<boolean>;
  onExclude: (clearance: string) => void;
}) {
  const { t } = useI18n();
  const [remark, setRemark] = useState('');
  // SRS-10-27: the dues are typed every time (zero for nil); an empty field is never a nil clearance.
  const [dues, setDues] = useState('');
  const id = d.summary.caseId;
  return (
    <div className="bd">
      <div className="kv">
        <span className="k">{t('o05.stage')}</span>
        <span>{t(`o05.stage.${d.summary.stage}`)}</span>
        <span className="k">{t('o05.separation')}</span>
        <span>
          {date(d.summary.separationDate)} · {d.summary.separationType}
        </span>
        <span className="k">{t('o05.cpf')}</span>
        <span>
          {d.cpfStatus ?? '—'}
          {d.cpfAmount !== null && ` · ${amount(d.cpfAmount)}`}
          {d.cpfSanctionNumber && ` · ${t('o05.cpfSanction', [d.cpfSanctionNumber])}`}
          <div className="sub2">{t('o05.cpfInfo')}</div>
        </span>
        <span className="k">{t('o05.invoiceStatus')}</span>
        <span>{t(`o05.invoice.${d.invoiceStatus ?? 'NOT_RAISED'}`)}</span>
      </div>
      {d.benefitsHeld && <p className="fl-hint">{t('o05.benefitsHeld')}</p>}
      {mayApprove && d.awaitingChecker && (
        <p className="toolbar">
          <a className="btn" href={withBase('/payroll/?s=W-01')}>
            {t('o05.decideInInbox')}
          </a>
        </p>
      )}
      <div className="toolbar">
        {mayClear && awaitsNoDues(d) && (
          <>
            <label>
              {t('o05.dues')}{' '}
              <input
                inputMode="decimal"
                maxLength={14}
                value={dues}
                onChange={(e) => setDues(e.target.value)}
              />
            </label>
            <label>
              {t('common.reason')}{' '}
              <TextAreaField maxLength={1000} value={remark} onChange={(e) => setRemark(e.target.value)} />
            </label>
            <button
              type="button"
              className="btn"
              disabled={remark.trim() === '' || dues.trim() === '' || !(Number(dues) >= 0)}
              onClick={() =>
                void run(() => settlementCalls.noDues(id, Number(dues), remark.trim()), 'o05.noDuesRecorded')
              }
            >
              {t('action.o05.noDues')}
            </button>
          </>
        )}
        <button
          type="button"
          className="btn"
          disabled={!mayApprove || !mayExclude(d)}
          title={!mayApprove ? t('reason.checkerOnly') : !mayExclude(d) ? t('o05.why.exclude') : undefined}
          onClick={() => onExclude(d.clearances.find((c) => outstanding(c.status))?.type ?? '')}
        >
          {t('action.o05.exclude')}
        </button>
        <button
          type="button"
          className="btn pri"
          disabled={!maker || !maySubmit(d)}
          title={!maker ? t('reason.makerOnly') : !maySubmit(d) ? t('o05.why.submit') : undefined}
          onClick={() => void run(() => settlementCalls.submit(id), 'o05.submitted')}
        >
          {t('common.submitForApproval')}
        </button>
        <button
          type="button"
          className="btn"
          disabled={!finance || !stageAllowsRelease(d)}
          title={
            !finance ? t('reason.financeOnly') : !stageAllowsRelease(d) ? t('o05.why.release') : undefined
          }
          onClick={() => void run(() => settlementCalls.release(id), 'o05.releaseSent')}
        >
          {t('action.o05.release')}
        </button>
      </div>
      <ExGratiaEntries d={d} />
    </div>
  );
}

/** SRS-10-25 (O-05 l.2942-2943): each ex-gratia entry with its letter, amount and status. */
function ExGratiaEntries({ d }: { d: CaseDetail }) {
  const { t } = useI18n();
  if (d.exGratiaEntries.length === 0) return null;
  return (
    <Tbl
      title={t('o05.exGratiaEntries')}
      cols={[
        { t: t('o05.letterNo'), s: 'ENT' },
        { t: t('o05.letterDate'), s: 'ENT' },
        { t: t('o05.amount'), s: 'ENT', num: true },
        { t: t('o05.entryStatus'), s: 'SYS' },
      ]}
    >
      {d.exGratiaEntries.map((x) => (
        <tr key={x.id}>
          <td>{x.letterNo}</td>
          <td>{date(x.letterDate)}</td>
          <td className="num">{amount(x.amount)}</td>
          <td>{t(`o05.exGratiaStatus.${x.status}`)}</td>
        </tr>
      ))}
    </Tbl>
  );
}
