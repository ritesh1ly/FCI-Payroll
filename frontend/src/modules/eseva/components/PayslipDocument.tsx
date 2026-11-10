'use client';

import { useI18n } from '@/shared/i18n';
import { periodLabel } from '@/shared/session';
import { yymmToMonth } from '@/shared/time';
import { amount, Problem, Src, useDialogFocus, usePdfDownload } from '@/shared/ui';
import { Act } from '@/shared/ui/wireframe';
import type { MemberOverview, MemberPayslip } from '../api/esevaApi';
import { amountInWords, payslipPdfPath, slipRows } from '../business/esevaRules';

/**
 * The drawn Pay Slip document (Wireframes v6.1 openDoc; SRS-17-1, SRS-17-7, SRS-17-10, SRS-18-13): a
 * locked month with PAN and bank account masked (the pack's full PAN is not followed, SRS-17-10). The
 * API gives no days paid or projected taxable income: those show a dash. Close and Print work;
 * Download PDF fetches the server's PDF of the same locked month (OD-144), masked the same way.
 */
export function PayslipDocument({
  slip,
  overview,
  cpfCode,
  onClose,
  sessionId = null,
}: {
  slip: MemberPayslip;
  overview: MemberOverview | undefined;
  cpfCode: string | null;
  onClose: () => void;
  /** The support preview's session (null for the member's own payslip). */
  sessionId?: number | null;
}) {
  const { t, locale } = useI18n();
  const pdf = usePdfDownload();
  const dialog = useDialogFocus<HTMLDivElement>();
  const month = periodLabel(yymmToMonth(slip.yymm), locale);
  const title = t('e01w.doc.title', [month, slip.name]);
  const tds = overview?.tds;
  return (
    <div
      className="fl-modal-backdrop"
      role="presentation"
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose();
      }}
    >
      <div
        ref={dialog}
        className="fl-modal fl-modal-wide e01-doc"
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <h2>{title}</h2>
        <div className="docpaper">
          <h2>{t('e01w.doc.org')}</h2>
          <div className="dsub">
            {t('e01w.doc.forMonth', [month])}
            {slip.provisional && ` ${t('e01w.doc.provisional')}`}
            {slip.office && ` · ${slip.office}`}
          </div>
          <table>
            <tbody>
              <tr>
                <th scope="row">{t('e01w.doc.eis')}</th>
                <td>{slip.eis}</td>
                <th scope="row">{t('e01w.doc.name')}</th>
                <td>{slip.name}</td>
              </tr>
              <tr>
                <th scope="row">{t('e01p.designation')}</th>
                <td>{slip.designation ?? '—'}</td>
                <th scope="row">{t('e01w.doc.pattern')}</th>
                <td>{slip.payPattern ?? '—'}</td>
              </tr>
              <tr>
                <th scope="row">{t('e01p.pan')}</th>
                <td>{slip.panMasked ?? '—'}</td>
                <th scope="row">{t('e01w.doc.cpfCode')}</th>
                <td>{cpfCode ?? '—'}</td>
              </tr>
              <tr>
                <th scope="row">{t('e01w.doc.bank')}</th>
                <td>{slip.bankAccountMasked ?? '—'}</td>
                <th scope="row">{t('e01w.doc.daysPaid')}</th>
                <td>—</td>
              </tr>
              {slip.pran && (
                <tr>
                  <th scope="row">{t('e01p.pran')}</th>
                  <td colSpan={3}>{slip.pran}</td>
                </tr>
              )}
            </tbody>
          </table>
          <div className="band">{t('e01w.doc.lines')}</div>
          <table aria-label={t('e01w.doc.lines')}>
            <thead>
              <tr>
                <th scope="row">{t('e01p.earnings')}</th>
                <th className="rt">
                  {t('e01w.amount')} <Src s="CALC" />
                </th>
                <th scope="row">{t('e01p.deductions')}</th>
                <th className="rt">
                  {t('e01w.amount')} <Src s="CALC" />
                </th>
              </tr>
            </thead>
            <tbody>
              {slipRows(slip.earnings, slip.deductions).map(([e, d], i) => (
                <tr key={i}>
                  <td>{e?.name ?? ''}</td>
                  <td className="rt">{e ? amount(e.amount) : ''}</td>
                  <td>{d?.name ?? ''}</td>
                  <td className="rt">{d ? amount(d.amount) : ''}</td>
                </tr>
              ))}
              <tr>
                <th scope="row" className="rt">
                  {t('e01p.gross')}
                </th>
                <td className="rt">
                  <b>{amount(slip.gross)}</b>
                </td>
                <th scope="row" className="rt">
                  {t('e01p.deductions')}
                </th>
                <td className="rt">
                  <b>{amount(slip.totalDeductions)}</b>
                </td>
              </tr>
            </tbody>
          </table>
          <div className="band">
            {t('e01w.doc.net', [amount(slip.net), t('e01w.doc.words', [amountInWords(slip.net)])])}
          </div>
          <table>
            <tbody>
              <tr>
                <th scope="row">{t('e01w.doc.cpfBalance')}</th>
                <td className="rt">{amount(overview?.cpf?.balance)}</td>
                <th scope="row">{t('e01w.doc.loan', [overview?.loan?.number ?? '—'])}</th>
                <td className="rt">{amount(overview?.loan?.outstanding)}</td>
              </tr>
              <tr>
                <th scope="row">{t('e01w.doc.taxable', [tds?.finYear ?? '—'])}</th>
                <td className="rt">—</td>
                <th scope="row">{t('e01w.tile.tds')}</th>
                <td className="rt">
                  {tds && tds.amount === null ? t('e01w.notConfigured') : amount(tds?.amount)}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <Problem error={pdf.error} />
        <div className="rf-actbar e01-doc-foot">
          <span className="sub2">{t('e01w.doc.note')}</span>
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
          <button type="button" className="btn" onClick={() => window.print()}>
            {t('e01w.doc.print')}
          </button>
          <Act
            label={t('e01w.doc.download')}
            kind="pri"
            onClick={() =>
              void pdf.download(payslipPdfPath(slip.yymm, sessionId), `payslip-${slip.yymm}.pdf`)
            }
          />
        </div>
      </div>
    </div>
  );
}
