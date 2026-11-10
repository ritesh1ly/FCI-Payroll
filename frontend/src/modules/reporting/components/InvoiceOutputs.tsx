'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { periodLabel, useMe } from '@/shared/session';
import { amount, Problem, ServerPagination, useToast } from '@/shared/ui';
import { InvoiceCard } from '@/shared/ui/invoiceCard';
import { Explained, Locked, Panel, RowActs, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { InvoiceLine, InvoiceRegisterRow } from '../api/screensApi';
import { screenPages, useInvoiceOverview } from '../orchestration/screensQueries';

const DASH = '—';

/** ap_invoice_staging.posting_status as the invoice card's status (BR-AUDIT-010). */
export function invoiceStatus(posting: string): string {
  if (posting === 'SENT' || posting === 'POSTED') return 'SENT_TO_APPS';
  return posting === 'CANCELLED' ? 'CANCELLED_IN_APPS' : 'RAISED';
}

/** yyyymm as the session's period text. */
function periodOf(yymm: string): string {
  return `${yymm.slice(0, 4)}-${yymm.slice(4)}`;
}

function registerStatus(t: (k: string) => string, r: InvoiceRegisterRow) {
  if (r.held > 0) return <span className="pill p-warn">{t('o06.status.held')}</span>;
  if (r.released > 0) return <span className="pill p-ok">{t('o06.status.released')}</span>;
  return <span className="pill p-gray">{t('o06.status.none')}</span>;
}

/**
 * O-06 Invoice Outputs as Wireframes v6.1 draws it (OD-121; FRS Annexure A.4, §6.10.3.3, §6.21.3, BR-AUDIT-009 / 010):
 * the period, type and unit filters, the tiles, the Invoice Register, why invoices are held, the Invoice Rules, the
 * status and cancellation card and the invoice report names. The figures are ap_invoice_staging and
 * gl_posting_statement, read only.
 *
 * Generating invoices, the GL reconciliation run, the release to Finance and ⊘ Cancel come with the APPS posting
 * (PENDING 15-invoices, API-23-2): each is drawn for the pack's role and says why; nothing is sent. A held invoice
 * stays held: this screen releases nothing, and the rule "reconcile before release" stands.
 */
export function InvoiceOutputs() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const me = useMe();
  const { options } = useReference();
  const [yymm, setYymm] = useState('');
  const [type, setType] = useState('');
  const [unit, setUnit] = useState<number | null>(null);
  const overview = useInvoiceOverview(yymm, unit);
  const o = overview.data;
  const shown = o?.period ?? '';
  const label = shown ? periodLabel(periodOf(shown), locale) : DASH;
  const roles = me.data?.roles ?? [];
  const maker = roles.includes('PAYROLL_MAKER');
  const checker = roles.includes('PAYROLL_CHECKER');
  const notBuilt = t('o06.why.apps');
  const lockText = o?.locked ? t('o06.locked') : t('o06.open');
  const keyTitle = o?.periods[0] === shown ? t('o06.lastLocked') : '';
  // The session's month joins the periods that hold invoices until it holds its own: invoices are raised after the lock.
  const open = me.data?.period?.replace('-', '') ?? '';
  const waiting = open !== '' && o !== undefined && !o.periods.includes(open);
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        <Explained label={t('o06.act.reconcile')} why={notBuilt} />
        {maker ? (
          <Explained label={t('o06.act.generate')} why={notBuilt} kind="pri" />
        ) : (
          <Locked label={t('o06.act.generate')} why={t('o06.why.maker')} kind="pri" />
        )}
      </div>
      <div className="toolbar">
        <select aria-label={t('o06.filter.period')} value={shown} onChange={(e) => setYymm(e.target.value)}>
          {(o?.periods ?? []).map((p) => (
            <option key={p} value={p}>
              {periodLabel(periodOf(p), locale)}
            </option>
          ))}
          {waiting && (
            <option value={open}>{t('o06.afterLock', [periodLabel(periodOf(open), locale)])}</option>
          )}
        </select>
        <select aria-label={t('o06.filter.type')} value={type} onChange={(e) => setType(e.target.value)}>
          <option value="">{t('o06.allTypes')}</option>
          {options('INVOICE_OUTPUT_TYPE').map((x) => (
            <option key={x.value} value={x.value}>
              {t(`o06.type.${x.value}`)}
            </option>
          ))}
        </select>
        <select
          aria-label={t('o06.filter.unit')}
          value={unit ?? ''}
          onChange={(e) => setUnit(e.target.value ? Number(e.target.value) : null)}
        >
          <option value="">{t('o06.allUnits')}</option>
          {(o?.units ?? []).map((u) => (
            <option key={u.id} value={u.id}>
              {u.label}
            </option>
          ))}
        </select>
        <span className="sp" />
        <Explained label={t('wf.export')} why={notBuilt} />
      </div>
      <Problem error={overview.error} />
      <Tiles>
        <WfTile
          icon="🧾"
          label={t('o06.tile.generated')}
          src="CALC"
          big={o?.count ?? 0}
          sub={t('o06.tile.generatedSub', [o?.types.length ?? 0])}
        />
        <WfTile
          icon="₹"
          label={t('o06.tile.value')}
          src="CALC"
          tone="pur"
          big={amount(o?.value ?? 0)}
          sub={t('o06.tile.valueSub', [amount(o?.salary ?? 0), amount(o?.incentive ?? 0)])}
        />
        <WfTile
          icon="⚖️"
          label={t('o06.tile.gl')}
          src="CALC"
          tone="warn"
          big={amount(o?.glGap ?? 0)}
          sub={t('o06.tile.glSub')}
        />
        <WfTile
          icon="📤"
          label={t('o06.tile.released')}
          src="SYS"
          tone="ok"
          big={o?.released ?? 0}
          sub={t('o06.tile.releasedSub', [o?.held ?? 0])}
        />
      </Tiles>
      <Tbl
        title={t('o06.register', [`${label}${keyTitle}`])}
        acts={
          <>
            {checker ? (
              <Explained label={t('o06.act.release')} kind="sm" why={notBuilt} />
            ) : (
              <Locked label={t('o06.act.release')} kind="sm" why={t('o06.why.checker')} />
            )}
            <Explained label={t('o06.act.export')} kind="sm" why={notBuilt} />
          </>
        }
        cols={[
          { t: t('o06.col.type'), s: 'RULE' },
          { t: t('o06.col.basis'), s: 'RULE' },
          { t: t('o06.col.count'), s: 'CALC', num: true },
          { t: t('o06.col.value'), s: 'CALC', num: true },
          { t: t('o06.col.gl'), s: 'CALC' },
          { t: t('o06.col.lock'), s: 'RULE' },
          { t: t('o06.col.status'), s: 'SYS' },
          { t: t('o06.col.actions'), s: 'SYS' },
        ]}
        foot={
          o ? (
            <tr>
              <td>
                <b>{t('o06.total')}</b>
              </td>
              <td />
              <td className="num">
                <b>{o.count}</b>
              </td>
              <td className="num">
                <b>{amount(o.value)}</b>
              </td>
              <td>
                <b>{t('o06.unreconciled', [amount(o.glGap)])}</b>
              </td>
              <td />
              <td />
              <td />
            </tr>
          ) : undefined
        }
      >
        {shown ? (
          <ServerPagination
            queryKey={['invoice-register', shown, unit, type]}
            fetchPage={screenPages.invoiceRegister(shown, unit, type)}
            empty={t('o06.none')}
          >
            {(rows) =>
              rows.map((r) => (
                <tr key={r.key}>
                  <td>
                    <b>{r.name}</b>
                  </td>
                  <td>{t(`o06.basis.${r.key}`)}</td>
                  <td className="num">{r.count > 0 ? r.count : DASH}</td>
                  <td className="num">{r.count > 0 ? amount(r.value) : DASH}</td>
                  <td>
                    {r.count === 0 ? (
                      'n/a'
                    ) : (
                      <span className={`pill ${r.held > 0 ? 'p-warn' : 'p-ok'}`}>
                        {t(r.held > 0 ? 'o06.gl.held' : 'o06.gl.matched')}
                      </span>
                    )}
                  </td>
                  <td>{lockText}</td>
                  <td>{registerStatus(t, r)}</td>
                  <td>
                    <RowActs
                      onView={() => toast(r.blockedReason ?? t('o06.status.none'))}
                      editWhy={t('o06.why.immutable')}
                      onApprove={checker ? () => toast(notBuilt) : undefined}
                      viewName={t('o03.viewName', [r.name])}
                      editName={t('o03.editName', [r.name])}
                    />
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        ) : (
          <tr>
            <td colSpan={8} className="sub2">
              {t('o06.none')}
            </td>
          </tr>
        )}
      </Tbl>
      <div className="g2 fl-mt">
        <Panel
          title={t('o06.heldTitle', [o?.held ?? 0])}
          act={<Explained label={t('o06.act.variance')} kind="sm" why={notBuilt} />}
          items={[
            {
              icon: '⚖️',
              tone: 'warn',
              src: 'CALC',
              title: t('o06.held.1.t'),
              text: t('o06.held.1.m', [amount(o?.glGap ?? 0)]),
              right: <b>{o?.held ?? 0}</b>,
            },
            {
              icon: '🎯',
              tone: 'warn',
              src: 'CALC',
              title: t('o06.held.2.t'),
              text: t('o06.held.2.m'),
              right: DASH,
            },
            {
              icon: '↩️',
              tone: 'info',
              src: 'CALC',
              title: t('o06.held.3.t'),
              text: t('o06.held.3.m'),
              right: DASH,
            },
          ]}
        />
        <Panel
          title={t('o06.rules')}
          items={[
            { icon: '⚖️', tone: 'bad', src: 'RULE', title: t('o06.rule.1.t'), text: t('o06.rule.1.m') },
            { icon: '🔒', tone: 'bad', src: 'RULE', title: t('o06.rule.2.t'), text: t('o06.rule.2.m') },
            { icon: '♻️', tone: 'ok', src: 'RULE', title: t('o06.rule.3.t'), text: t('o06.rule.3.m') },
            { icon: '🗄', tone: 'ok', src: 'SYS', title: t('o06.rule.4.t'), text: t('o06.rule.4.m') },
          ]}
        />
      </div>
      <div className="g2 fl-mt">
        {shown ? (
          <ServerPagination
            queryKey={['invoice-lines', shown, unit]}
            fetchPage={screenPages.invoiceLines(shown, unit)}
            empty={<Lines rows={[]} title={t('o06.lines')} why={notBuilt} />}
          >
            {(rows) => <Lines rows={rows} title={t('o06.lines')} why={notBuilt} />}
          </ServerPagination>
        ) : (
          <Lines rows={[]} title={t('o06.lines')} why={notBuilt} />
        )}
        <Panel
          title={t('o06.names')}
          items={[
            { icon: '🧾', tone: 'info', src: 'SYS', title: t('o06.name.1.t'), text: t('o06.name.1.m') },
            { icon: '🧾', tone: 'info', src: 'SYS', title: t('o06.name.2.t'), text: t('o06.name.2.m') },
            { icon: '🔐', tone: 'warn', src: 'SYS', title: t('o06.name.3.t'), text: t('o06.name.3.m') },
          ]}
        />
      </div>
    </>
  );
}

function Lines({ rows, title, why }: { rows: InvoiceLine[]; title: string; why: string }) {
  return (
    <InvoiceCard
      title={title}
      rows={rows.map((l) => ({
        key: String(l.id),
        entry: l.number,
        member: l.unit,
        paymentType: l.paymentType ?? DASH,
        amount: amount(l.amount),
        invoiceNo: l.number,
        invoiceStatus: invoiceStatus(l.postingStatus),
        sentToAppsDate: l.sentToApps,
        appsCancelled: l.appsCancelStatus === 'CONFIRMED',
        cancelled: l.postingStatus === 'CANCELLED',
        cancelWhy: why,
        onCancel: () => undefined,
      }))}
    />
  );
}
