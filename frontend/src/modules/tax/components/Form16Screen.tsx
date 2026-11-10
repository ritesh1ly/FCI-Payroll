'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import { recentFinYears } from '@/shared/time';
import { amount, BulkJobButton, Problem, ServerPagination, usePdfDownload } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import {
  Act,
  Explained,
  Locked,
  Panel,
  RowActs,
  Tbl,
  Tiles,
  Toolbar,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { CertificateLine } from '../api/screensApi';
import { certificateStatus } from '../business/screenRules';
import { screenKeys, screenPages, useCertificateSummary } from '../orchestration/screensQueries';
import { Form16Register } from './Form16Register';

/**
 * C-10 Annual Tax Certificate - Form 16 as Wireframes v6.1 draws it (OD-121; SRS-7-5, SRS-7-9, SRS-7-19, SRS-7-21,
 * SRS-7-23, BR-TAX-010 to 012): the year just closed, the certificate chain with the system boundary, the register with
 * the PAN masked (R-PII, the pack draws it whole) and the rules. Generation, Part A upload, release and reissue stay
 * whole in the closed register. A row's ⇩ PDF is the server's certificate (OD-144): the received Part A first, then
 * Part B in the prescribed Annexure (SRS-7-5, SRS-7-9, SRS-7-30). Bulk PDF and Bulk download queue one background job
 * (OD-149, SRS-NF-2) whose ZIP of the year's certificates is downloaded here once ready; Preview is not built (PENDING
 * LIB-1) and says why.
 */
export function Form16Screen() {
  const { t } = useI18n();
  const me = useMe();
  const { options } = useReference();
  const years = recentFinYears(3).slice(1);
  const [finYear, setFinYear] = useState(years[0] ?? '');
  const [office, setOffice] = useState('');
  const [status, setStatus] = useState('');
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [viewing, setViewing] = useState<CertificateLine | null>(null);
  const summary = useCertificateSummary(finYear);
  const s = summary.data;
  const mayCreate = can(me.data, 'C-10', 'CREATE');
  const mayEdit = can(me.data, 'C-10', 'EDIT');
  const mayDecide = can(me.data, 'C-10', 'APPROVE');
  const noPdf = t('txw.c10.why.noPdf');
  const noCopy = t('txw.c10.why.noIssuedCopy');
  const bulkWhy = t('txw.c10.why.bulkPdf');
  const pdf = usePdfDownload();
  const show = () => setOpen(true);
  const awaited = (s?.due ?? 0) - (s?.partA ?? 0);
  const needle = query.trim().toLowerCase();
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Explained label={t('txw.c10.preview')} why={bulkWhy} />
        {mayEdit ? (
          <Act label={t('txw.c10.uploadPartA')} onClick={show} />
        ) : (
          <Locked label={t('txw.c10.uploadPartA')} why={t('txw.c10.why.maker')} />
        )}
        <BulkJobButton
          label={t('txw.c10.bulkPdf')}
          statusPath={`/v1/tax/form16-bulk-files?finYear=${finYear}`}
          requestPath="/v1/tax/form16-bulk-files"
          requestBody={{ finYear }}
          filePath={(id) => `/v1/tax/form16-bulk-files/${id}/file`}
          fileName={`form16-${finYear}.zip`}
        />
        {mayCreate ? (
          <Act label={t('txw.c10.generate')} kind="pri" onClick={show} />
        ) : (
          <Locked label={t('txw.c10.generate')} why={t('txw.c10.why.generate')} />
        )}
      </div>
      <Toolbar
        search={query}
        onSearch={setQuery}
        exportControl={<Explained label={t('wf.export')} why={t('txw.why.noExport')} />}
      >
        <select
          aria-label={t('txw.fy', [finYear])}
          value={finYear}
          onChange={(e) => setFinYear(e.target.value)}
        >
          {years.map((y) => (
            <option key={y} value={y}>
              {t('txw.fy', [y])}
            </option>
          ))}
        </select>
        <select
          aria-label={t('txw.c10.allOffices')}
          value={office}
          onChange={(e) => setOffice(e.target.value)}
        >
          <option value="">{t('txw.c10.allOffices')}</option>
          {(s?.units ?? []).map((u) => (
            <option key={u.id} value={u.id}>
              {u.code} · {u.name}
            </option>
          ))}
        </select>
        <select
          aria-label={t('txw.c10.allStatuses')}
          value={status}
          onChange={(e) => setStatus(e.target.value)}
        >
          <option value="">{t('txw.c10.allStatuses')}</option>
          {options('FORM16_REGISTER_STATUS').map((o) => (
            <option key={o.value} value={o.value}>
              {t(`txw.c10.filter.${o.value}`)}
            </option>
          ))}
        </select>
      </Toolbar>
      <Problem error={summary.error ?? pdf.error} />
      <Tiles>
        <WfTile
          icon="📜"
          label={t('txw.c10.tile.due')}
          src="CALC"
          big={s?.due ?? 0}
          sub={t('txw.c10.tile.dueSub', [finYear])}
        />
        <WfTile
          icon="✅"
          label={t('txw.c10.tile.partB')}
          src="CALC"
          big={s?.partB ?? 0}
          sub={t('txw.c10.tile.partBSub')}
          tone="ok"
        />
        <WfTile
          icon="⬇️"
          label={t('txw.c10.tile.partA')}
          src="INTF"
          big={s?.partA ?? 0}
          sub={t('txw.c10.tile.partASub', [awaited])}
          tone="warn"
        />
        <WfTile
          icon="🙋"
          label={t('txw.c10.tile.issued')}
          src="SYS"
          big={s?.issued ?? 0}
          sub={t('txw.c10.tile.issuedSub')}
          tone="pur"
        />
      </Tiles>
      <Panel
        title={t('txw.c10.chain')}
        items={[
          {
            icon: '①',
            tone: 'ok',
            title: t('txw.c10.c.1'),
            src: 'CALC',
            text: t('txw.c10.c.1s'),
            right: <span className="pill">{t(s?.partB ? 'txw.pill.done' : 'txw.pill.pending')}</span>,
          },
          {
            icon: '②',
            tone: 'ok',
            title: t('txw.c10.c.2'),
            src: 'RULE',
            text: t('txw.c10.c.2s'),
            right: <span className="pill">{s?.partB ?? 0}</span>,
          },
          {
            icon: '③',
            tone: 'info',
            title: t('txw.c10.c.3'),
            src: 'CALC',
            text: t('txw.c10.c.3s'),
            right: <span className="pill">{s?.form10e ?? 0}</span>,
          },
          {
            icon: '④',
            tone: 'gray',
            title: t('txw.c10.c.4'),
            src: 'SYS',
            text: t('txw.c10.c.4s'),
            right: <span className="pill">{t('txw.pill.external')}</span>,
          },
          {
            icon: '⑤',
            tone: 'info',
            title: t('txw.c10.c.5'),
            src: 'INTF',
            text: t('txw.c10.c.5s'),
            right: <span className="pill">{`${s?.partA ?? 0} / ${s?.due ?? 0}`}</span>,
          },
          {
            icon: '⑥',
            tone: 'ok',
            title: t('txw.c10.c.6'),
            src: 'SYS',
            text: t('txw.c10.c.6s'),
            right: <span className="pill">{s?.merged ?? 0}</span>,
          },
          {
            icon: '⑦',
            tone: 'gray',
            title: t('txw.c10.c.7'),
            src: 'SYS',
            text: t('txw.c10.c.7s'),
            right: <span className="pill">{t('txw.pill.external')}</span>,
          },
          {
            icon: '⑧',
            tone: 'pur',
            title: t('txw.c10.c.8'),
            src: 'ESS',
            text: t('txw.c10.c.8s'),
            right: <span className="pill">{s?.issued ?? 0}</span>,
          },
        ]}
      />
      <Tbl
        title={t('txw.c10.register', [finYear])}
        label={t('txw.c10.registerLabel')}
        acts={
          <>
            <BulkJobButton
              label={t('txw.c10.bulkDownload')}
              statusPath={`/v1/tax/form16-bulk-files?finYear=${finYear}`}
              requestPath="/v1/tax/form16-bulk-files"
              requestBody={{ finYear }}
              filePath={(id) => `/v1/tax/form16-bulk-files/${id}/file`}
              fileName={`form16-${finYear}.zip`}
            />
            {mayEdit ? (
              <Act label={t('txw.c10.reissue')} kind="sm" onClick={show} />
            ) : (
              <Locked label={t('txw.c10.reissue')} kind="sm" why={t('txw.c10.why.maker')} />
            )}
          </>
        }
        cols={[
          { t: t('txw.c10.col.eis'), s: 'HRMS' },
          { t: t('txw.c10.col.name'), s: 'HRMS' },
          { t: t('txw.c10.col.pan'), s: 'HRMS' },
          { t: t('txw.c10.col.regime'), s: 'ESS' },
          { t: t('txw.c10.col.taxable'), s: 'CALC', num: true },
          { t: t('txw.c10.col.tds'), s: 'CALC', num: true },
          { t: t('txw.c10.col.partB'), s: 'SYS' },
          { t: t('txw.c10.col.partA'), s: 'INTF' },
          { t: t('txw.c10.col.signed'), s: 'SYS' },
          { t: t('txw.c10.col.status'), s: 'SYS' },
          { t: t('txw.c10.col.document'), s: 'SYS' },
          { t: t('txw.c10.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={screenKeys.certificateLines(finYear, status, office)}
          fetchPage={screenPages.certificateLines(finYear, status, office)}
          empty={t('txw.c10.none')}
        >
          {(page) =>
            page
              .filter((l) => needle === '' || `${l.eis} ${l.name}`.toLowerCase().includes(needle))
              .map((l) => (
                <tr key={l.id} data-reference={l.eis}>
                  <td>{l.eis}</td>
                  <td>{l.name}</td>
                  <td>{l.panStatus === 'VALID' ? l.panMasked : t(`c10.panStatus.${l.panStatus}`)}</td>
                  <td>{l.regime ? t(l.regime === 'N' ? 'e01t.regime.NEW' : 'e01t.regime.OLD') : '—'}</td>
                  <td className="num">{amount(l.taxable)}</td>
                  <td className="num">{amount(l.tds)}</td>
                  <td>{l.partB ? '✓' : '—'}</td>
                  <td>{l.partA ? '✓' : t('txw.c10.awaited')}</td>
                  <td>{l.signed ? '✓' : '—'}</td>
                  <td>
                    <span className="pill">{t(certificateStatus(l))}</span>
                  </td>
                  <td>
                    {l.status === 'RELEASED' && l.issuedCopy === false ? (
                      // OD-153 ("Disable PDF with reason"): released before issued copies were kept.
                      <button type="button" className="ia off" disabled title={noCopy} data-why={noCopy}>
                        {t('txw.c10.pdf')}
                      </button>
                    ) : l.partB ? (
                      <button
                        type="button"
                        className="ia pdf"
                        title={t(l.partA ? 'txw.c10.pdfTitle' : 'txw.c10.pdfPartBTitle')}
                        onClick={() =>
                          void pdf.download(
                            `/v1/tax/form16-certificates/${l.id}/part-b`,
                            // OD-153: named as the server names the certificate (form16-<FY>.pdf).
                            `form16-${finYear}.pdf`,
                          )
                        }
                      >
                        {t('txw.c10.pdf')}
                      </button>
                    ) : (
                      <button type="button" className="ia off" disabled title={noPdf} data-why={noPdf}>
                        {t('txw.c10.pdf')}
                      </button>
                    )}
                  </td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${l.eis}`}
                      editName={`${t('common.edit')} ${l.eis}`}
                      onView={() => setViewing(l)}
                      editWhy={t('txw.c10.why.immutable')}
                      approveWhy={mayDecide ? t('txw.c10.why.decide') : undefined}
                    />
                  </td>
                </tr>
              ))
          }
        </ServerPagination>
      </Tbl>
      <Panel
        title={t('txw.c10.rules')}
        items={[
          { icon: '🔒', tone: 'bad', title: t('txw.c10.r.1'), src: 'RULE', text: t('txw.c10.r.1s') },
          { icon: '🧮', tone: 'warn', title: t('txw.c10.r.2'), src: 'RULE', text: t('txw.c10.r.2s') },
          { icon: '↩️', tone: 'info', title: t('txw.c10.r.3'), src: 'CALC', text: t('txw.c10.r.3s') },
          { icon: '🗄', tone: 'gray', title: t('txw.c10.r.4'), src: 'SYS', text: t('txw.c10.r.4s') },
          { icon: '🙋', tone: 'ok', title: t('txw.c10.r.5'), src: 'ESS', text: t('txw.c10.r.5s') },
        ]}
      />
      <RegisterSection label={t('txw.c10.section')} open={open} onToggle={setOpen} id="c10-register">
        {open && <Form16Register />}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.C-10')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('txw.c10.col.eis'), v: viewing.eis, s: 'HRMS' },
            { k: t('txw.c10.col.name'), v: viewing.name, s: 'HRMS' },
            { k: t('txw.c10.col.pan'), v: viewing.panMasked ?? '—', s: 'HRMS' },
            { k: t('txw.c10.col.taxable'), v: amount(viewing.taxable), s: 'CALC' },
            { k: t('txw.c10.col.tds'), v: amount(viewing.tds), s: 'CALC' },
            { k: t('txw.c10.col.status'), v: t(certificateStatus(viewing)), s: 'SYS' },
          ]}
        />
      )}
    </div>
  );
}
