'use client';

import { useState } from 'react';
import { type Translate, useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import {
  Card,
  date,
  dateTime,
  ExportButton,
  Problem,
  ReasonDialog,
  ServerPagination,
  Th,
  useToast,
  withBase,
} from '@/shared/ui';
import { Act, Explained, Locked, Panel, RowActs, Tbl, Tiles, Toolbar, WfTile } from '@/shared/ui/wireframe';
import type {
  HrmsApiFilter,
  HrmsApiRow,
  InterfaceRow,
  InterfaceSettings,
  QueueItem,
  Window,
} from '../api/integrationApi';
import {
  ageDays,
  balanced,
  configured,
  reprocessBlocked,
  statusTone,
  unresolved,
  windowReprocessBlocked,
  writeOffBlocked,
} from '../business/integrationRules';
import {
  auditTrailUrl,
  integrationKeys,
  integrationPages,
  useContract,
  useIntegrationSummary,
  useReprocess,
  useReprocessWindow,
  useTestConnection,
  useWriteOff,
} from '../orchestration/integrationQueries';
import { HrmsConsole } from './HrmsConsole';

/** The outcomes and look-back windows (days, 1 to 90) the HRMS APIs list can be narrowed by. */
const HRMS_STATUSES = ['OK', 'PARTIAL', 'FAILED', 'NONE'] as const;
const HRMS_WINDOWS = [1, 7, 30, 90] as const;

/** The part of the screen a drawn button opens (closed until pressed, OD-121). */
type Section = 'queue' | 'windows' | 'contract' | 'test' | null;

/**
 * X-03 Integration Monitor as Wireframes v6.1 draws it (a frozen requirement per OD-121; plan 23 phase 23b; SRS-INT-6,
 * SRS-INT-16, SRS-INT-17, SRS-INT-31 … SRS-INT-33; BR-INT-011): "📄 Exception Queue", "🔁 Reprocess Failed" (the
 * Administrator's) and "📥 Fallback upload (X-07)" over the screen; the five tiles; the status filter (INTERFACE_RUN_STATUS,
 * OD-123), the search and the export of the integration audit trail; the HRMS -> Payroll interfaces with Contract (the
 * HRMS API catalogue and each interface's contract), the enterprise interfaces with Test connection (the console and
 * the per-interface test); what happens when HRMS is unavailable, the exception handling and the posting rules. The
 * functions the pack opens from its buttons are drawn closed until pressed: the exception queue (the Integration Failure
 * Report), the exchange windows to reprocess, the contract and the test. Every viewer opens an interface's resilience
 * parameters read-only (SRS-INT-16); Contract, Test connection, Reprocess Failed and Write off are the Administrator's
 * (SRS-INT-33), each use audited by the backend.
 */
export function IntegrationMonitor() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const isAdmin = can(me.data, 'X-03', 'EDIT');
  const checker = me.data?.role === 'PAYROLL_CHECKER';
  const mayExport = can(me.data, 'X-03', 'EXPORT');
  const summary = useIntegrationSummary();
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const [section, setSection] = useState<Section>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [shown, setShown] = useState<InterfaceRow | null>(null);
  const [error, setError] = useState<unknown>(null);
  const contract = useContract(selected);
  const test = useTestConnection();
  const s = summary.data;
  const age = ageDays(s?.oldestAwaitingI21 ?? null, new Date());
  const toggle = (next: Section) => setSection(section === next ? null : next);

  async function testConnection(code: string) {
    setError(null);
    try {
      const o = await test.mutateAsync(code);
      toast(t('x03.tested', [o.interfaceCode, o.outcome]));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('x03.queueAct')} onClick={() => toggle('queue')} />
        {isAdmin ? (
          <Act label={t('x03.reprocessAct')} kind="pri" onClick={() => toggle('windows')} />
        ) : (
          <Locked label={t('x03.reprocessAct')} kind="pri" why={t('x03.why.admin')} />
        )}
        <Act label={t('x03.fallbackAct')} href={withBase('/payroll/?s=X-07')} />
      </div>
      <Problem error={summary.error ?? error} />
      <Tiles>
        <WfTile
          icon="🔗"
          label={t('x03.tile.hrmsApis')}
          src="INTF"
          big={s ? `${s.interfaces - s.notConfigured} / ${s.interfaces}` : '—'}
          sub={t('x03.tile.hrmsApisSub')}
        />
        <WfTile
          icon="⛔"
          label={t('x03.tile.exceptions')}
          src="SYS"
          tone="bad"
          bad
          big={s?.openExceptions ?? '—'}
          sub={t('x03.tile.exceptionsSub')}
        />
        <WfTile
          icon="🔁"
          label={t('x03.tile.payEffect')}
          src="SYS"
          tone="warn"
          big={s?.awaitingI21 ?? '—'}
          sub={t('x03.tile.payEffectSub')}
        />
        {/* Not reported by X-03's summary yet (PENDING X03-1): drawn, never a guessed figure. */}
        <WfTile icon="📮" label={t('x03.tile.ebs')} src="SYS" big="—" sub={t('x03.tile.ebsSub')} />
        <WfTile
          icon="🏦"
          label={t('x03.tile.cpfSsp')}
          src="SYS"
          tone="warn"
          big="—"
          sub={t('x03.tile.cpfSspSub')}
        />
      </Tiles>
      <Toolbar
        search={search}
        onSearch={setSearch}
        exportControl={
          mayExport ? (
            <ExportButton href={auditTrailUrl('')} what={t('x03.auditTrail')} label={t('wf.export')} />
          ) : (
            <Explained label={t('wf.export')} why={t('x03.why.export')} />
          )
        }
      >
        <select aria-label={t('x03.statusFilter')} value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">{t('x03.allStatuses')}</option>
          <ReferenceOptions list="INTERFACE_RUN_STATUS" />
        </select>
      </Toolbar>
      <Tbl
        title={t('x03.hrmsTitle')}
        acts={
          isAdmin ? (
            <Act label={t('x03.contractAct')} kind="sm" onClick={() => toggle('contract')} />
          ) : (
            <Locked label={t('x03.contractAct')} kind="sm" why={t('x03.why.admin')} />
          )
        }
        cols={[
          { t: t('x03.col.interface'), s: 'SYS' },
          { t: t('x03.col.data'), s: 'SYS' },
          { t: t('x03.col.consumedBy'), s: 'SYS' },
          { t: t('x03.col.received'), s: 'INTF', num: true },
          { t: t('x03.col.accepted'), s: 'SYS', num: true },
          { t: t('x03.col.rejected'), s: 'SYS', num: true },
          { t: t('x03.col.status'), s: 'SYS' },
          { t: t('x03.col.actions'), s: 'SYS' },
        ]}
        after={
          <div className="bd fl-pad-top-0">
            <div className="oq">
              <b>{t('x03.hrmsNoteLead')}</b> {t('x03.hrmsNote')}
            </div>
          </div>
        }
      >
        <ServerPagination
          queryKey={[...integrationKeys.interfaces, 'HRMS', status, search.trim()]}
          fetchPage={integrationPages.drawn('HRMS', status, search.trim())}
          personal={false}
          empty={<p className="fl-hint">{t('x03.noInterfaces')}</p>}
        >
          {(rows) =>
            rows.map((r) => (
              <tr key={r.code} data-code={r.code}>
                <td>
                  <b>{r.stagingObject ?? r.code}</b>
                  {!r.specSignedOff && <div className="sub2">{t('x03.specNotHeld')}</div>}
                </td>
                <td>{r.name}</td>
                <td>{r.consumingScreen ?? '—'}</td>
                <td className="num">{r.status ? r.received : '—'}</td>
                <td className="num">{r.status ? r.accepted : '—'}</td>
                <td className="num">
                  {r.status ? r.rejected : '—'}
                  {r.status && !balanced(r) && <div className="sub2">{t('x03.unbalanced')}</div>}
                </td>
                <td>
                  <span className={`pill ${statusTone(r.status)}`}>
                    {r.status ? t(`x03.status.${r.status}`) : t('x03.noExchange')}
                  </span>
                  {r.breakerState !== 'CLOSED' && (
                    <div className="sub2">{t(`x03.breaker.${r.breakerState}`)}</div>
                  )}
                  {r.openExceptions > 0 && <div className="sub2">{t('x03.openOf', [r.openExceptions])}</div>}
                  {/* X-03 l.5173 (OD-89): HRMS changes awaiting I-21 show their age on the HRMS rows. */}
                  {(s?.awaitingI21 ?? 0) > 0 && (
                    <div className="sub2" aria-label={t('x03.awaitingOf', [r.code])}>
                      {t('x03.awaitingRow', [s?.awaitingI21 ?? 0, age ?? 0])}
                    </div>
                  )}
                  {/* SRS-INT-16: whether the interface's resilience settings are in force. */}
                  <div className="sub2">{configured(r) ? t('x03.configured') : t('x03.notConfigured')}</div>
                </td>
                <td>
                  <RowActs
                    viewName={t('x03.resilienceOf', [r.code])}
                    editName={t('x03.contractOf', [r.code])}
                    onView={() => setShown(r)}
                    onEdit={isAdmin ? () => setSelected(r.code) : undefined}
                    editWhy={t('x03.why.rateAdmin')}
                    onApprove={
                      checker ? () => window.location.assign(withBase('/payroll/?s=W-01')) : undefined
                    }
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Tbl
          title={t('x03.enterpriseTitle')}
          acts={
            isAdmin ? (
              <Act label={t('x03.testAct')} kind="sm" onClick={() => toggle('test')} />
            ) : (
              <Locked label={t('x03.testAct')} kind="sm" why={t('x03.why.admin')} />
            )
          }
          cols={[
            { t: t('x03.col.system'), s: 'SYS' },
            { t: t('x03.col.endpoint'), s: 'SYS' },
            { t: t('x03.col.direction'), s: 'SYS' },
            { t: t('x03.col.status'), s: 'SYS' },
          ]}
        >
          <ServerPagination
            queryKey={[...integrationKeys.interfaces, 'ENTERPRISE', status, search.trim()]}
            fetchPage={integrationPages.drawn('ENTERPRISE', status, search.trim())}
            personal={false}
            empty={<p className="fl-hint">{t('x03.noInterfaces')}</p>}
          >
            {(rows) =>
              rows.map((r) => (
                <tr key={r.code} data-code={r.code}>
                  <td>
                    {r.counterparty} - {t(`x03.cp.${r.counterparty}`)}
                  </td>
                  <td>{r.endpointObject ?? r.name}</td>
                  <td>{t(`x03.dir.${r.direction}`)}</td>
                  <td>
                    <span className={`pill ${statusTone(r.status)}`}>
                      {r.status ? t(`x03.status.${r.status}`) : t('x03.noExchange')}
                    </span>
                    {/* SRS-INT-6 / SRS-INT-16 (OD-148 member e-mail): the drawn status cell also says, as on
                        the HRMS rows, an open or probing breaker, the open failures and whether the
                        settings are in force. */}
                    {r.breakerState !== 'CLOSED' && (
                      <div className="sub2">{t(`x03.breaker.${r.breakerState}`)}</div>
                    )}
                    {r.openExceptions > 0 && (
                      <div className="sub2">{t('x03.openOf', [r.openExceptions])}</div>
                    )}
                    <div className="sub2">{configured(r) ? t('x03.configured') : t('x03.notConfigured')}</div>
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
        <Panel
          title={t('x03.unavailable')}
          items={[
            {
              icon: '⏸',
              tone: 'info',
              title: t('x03.un.notStop'),
              src: 'SYS',
              text: t('x03.un.notStopText'),
            },
            {
              icon: 'a',
              tone: 'warn',
              title: t('x03.un.fallbackA'),
              src: 'INTF',
              text: t('x03.un.fallbackAText'),
            },
            {
              icon: 'b',
              tone: 'warn',
              title: t('x03.un.fallbackB'),
              src: 'INTF',
              text: t('x03.un.fallbackBText'),
            },
            {
              icon: '✖',
              tone: 'bad',
              title: t('x03.un.noOther'),
              src: 'RULE',
              text: t('x03.un.noOtherText'),
            },
            { icon: '⚖', tone: 'info', title: t('x03.un.recon'), src: 'SYS', text: t('x03.un.reconText') },
          ]}
        />
      </div>
      <div className="g2 fl-mt">
        <Panel
          title={t('x03.handling')}
          items={[
            { icon: '🔁', tone: 'info', title: t('x03.h.retry'), src: 'RULE', text: t('x03.h.retryText') },
            {
              icon: '📄',
              tone: 'bad',
              title: t('x03.h.queue'),
              src: 'SYS',
              text: t('x03.h.queueText'),
              right: <b>{s?.openExceptions ?? '—'}</b>,
            },
            { icon: '🚫', tone: 'pur', title: t('x03.h.never'), src: 'INTF', text: t('x03.h.neverText') },
            { icon: '📤', tone: 'gray', title: t('x03.h.ack'), src: 'SYS', text: t('x03.h.ackText') },
          ]}
        />
        <Tbl
          title={t('x03.postingRules')}
          cols={[
            { t: t('x03.col.rule'), s: 'RULE' },
            { t: t('x03.col.statement'), s: 'RULE' },
          ]}
        >
          {(['invoice', 'partial', 'cancel'] as const).map((k) => (
            <tr key={k}>
              <td>{t(`x03.rule.${k}`)}</td>
              <td>{t(`x03.rule.${k}Text`)}</td>
            </tr>
          ))}
        </Tbl>
      </div>

      {/* 📄 Exception Queue (every viewer): the failure report and the exchange windows, read-only for all but the
          Administrator; 🔁 Reprocess Failed (the Administrator) opens the windows to reprocess (SRS-INT-31 .. 33). */}
      {section === 'queue' && (
        <>
          <ExceptionQueue isAdmin={isAdmin} onClose={() => setSection(null)} />
          <ExchangeWindows isAdmin={isAdmin} onClose={() => setSection(null)} />
        </>
      )}
      {section === 'windows' && <ExchangeWindows isAdmin={isAdmin} onClose={() => setSection(null)} />}
      {section === 'contract' && (
        <Card
          title={t('x03.contractAct')}
          actions={
            <button type="button" className="btn sm" onClick={() => setSection(null)}>
              {t('common.close')}
            </button>
          }
        >
          <HrmsApis />
        </Card>
      )}
      {section === 'test' && (
        <Card
          title={t('x03.testAct')}
          actions={
            <button type="button" className="btn sm" onClick={() => setSection(null)}>
              {t('common.close')}
            </button>
          }
        >
          <ServerPagination
            queryKey={[...integrationKeys.interfaces, 'TEST']}
            fetchPage={integrationPages.drawn('', '', '')}
            personal={false}
            empty={<p className="fl-hint">{t('x03.noInterfaces')}</p>}
          >
            {(rows) => (
              <table className="t" aria-label={t('x03.testAct')}>
                <thead>
                  <tr>
                    <Th label={t('x03.interface')} s="SYS" />
                    <Th label={t('x03.direction')} s="SYS" />
                    <Th label={t('x05.actions')} s="SYS" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.code}>
                      <td>
                        <b>{r.code}</b>
                        <div className="sub2">{r.name}</div>
                      </td>
                      <td>{t(`x03.dir.${r.direction}`)}</td>
                      <td>
                        <button
                          type="button"
                          className="ia"
                          disabled={test.isPending}
                          aria-label={t('x03.testOf', [r.code])}
                          onClick={() => void testConnection(r.code)}
                        >
                          {t('action.x03.test')}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </ServerPagination>
          <HrmsConsole isAdmin={isAdmin} />
        </Card>
      )}
      {shown && (
        <Card
          title={t('x03.resilienceOf', [shown.code])}
          actions={
            <button type="button" className="btn sm" onClick={() => setShown(null)}>
              {t('common.close')}
            </button>
          }
        >
          <Resilience settings={shown.settings} />
        </Card>
      )}
      {selected && contract.data && (
        <Card
          title={t('x03.contractTitle', [selected])}
          actions={
            <button type="button" className="btn sm" onClick={() => setSelected(null)}>
              {t('common.close')}
            </button>
          }
        >
          <dl className="kv">
            <dt>{t('x03.counterparty')}</dt>
            <dd>
              {contract.data.row.counterparty} - {t(`x03.cp.${contract.data.row.counterparty}`)}
            </dd>
            <dt>{t('x03.direction')}</dt>
            <dd>{t(`x03.dir.${contract.data.row.direction}`)}</dd>
            <dt>{t('x03.transport')}</dt>
            <dd>{contract.data.row.transport ?? t('x03.notStated')}</dd>
            <dt>{t('x03.endpoint')}</dt>
            <dd>{contract.data.row.endpointObject ?? t('x03.notStated')}</dd>
            <dt>{t('x03.frequency')}</dt>
            <dd>{contract.data.frequency ?? t('x03.notStated')}</dd>
            <dt>{t('x03.idempotency')}</dt>
            <dd>{contract.data.idempotencyKey ?? t('x03.notStated')}</dd>
            <dt>{t('x03.reconciliation')}</dt>
            <dd>{contract.data.reconciliation ?? t('x03.notStated')}</dd>
            <dt>{t('x03.spec')}</dt>
            <dd>{contract.data.row.specSignedOff ? t('x03.specHeld') : t('x03.specNotHeld')}</dd>
          </dl>
          <h4>{t('x03.resilience')}</h4>
          <p className="sub2">{t('x03.resilienceInList')}</p>
        </Card>
      )}
      <Problem error={contract.error} />
    </div>
  );
}

/** "🔁 Reprocess Failed": the exchange windows; a failed window's records reprocessed with one reason (SRS-INT-33). */
function ExchangeWindows({ isAdmin, onClose }: { isAdmin: boolean; onClose: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const [filter, setFilter] = useState('');
  const [failedWindow, setFailedWindow] = useState<Window | null>(null);
  const [error, setError] = useState<unknown>(null);
  const reprocessWindow = useReprocessWindow();
  const code = filter.trim().toUpperCase();
  return (
    <Card
      title={t('x03.windows')}
      actions={
        <button type="button" className="btn sm" onClick={onClose}>
          {t('common.close')}
        </button>
      }
    >
      <Problem error={error} />
      <div className="toolbar">
        <label className="sub2">
          {t('x03.filterInterface')}{' '}
          <input
            maxLength={40}
            value={filter}
            aria-label={t('x03.filterInterface')}
            onChange={(e) => setFilter(e.target.value)}
          />
        </label>
        {can(me.data, 'X-03', 'EXPORT') && (
          <ExportButton href={auditTrailUrl(code)} what={t('x03.auditTrail')} label={t('x03.auditTrail')} />
        )}
      </div>
      <ServerPagination
        queryKey={[...integrationKeys.windows, code]}
        fetchPage={integrationPages.windows(code)}
        personal={false}
        empty={<p className="fl-hint">{t('x03.noWindows')}</p>}
      >
        {(rows) => (
          <table className="t" aria-label={t('x03.windows')}>
            <thead>
              <tr>
                <Th label={t('x03.interface')} s="SYS" />
                <Th label={t('x03.batch')} s="INTF" />
                <Th label={t('x03.started')} s="SYS" />
                <Th label={t('x03.counts')} s="SYS" num />
                <Th label={t('gen.status')} s="SYS" />
                <Th label={t('x05.actions')} s="SYS" />
              </tr>
            </thead>
            <tbody>
              {rows.map((w) => {
                const whyWindow = windowReprocessBlocked(w, isAdmin);
                return (
                  <tr key={w.runId}>
                    <td>{w.interfaceCode}</td>
                    <td>{w.batchReference ?? '—'}</td>
                    <td>{dateTime(w.startedAt)}</td>
                    <td className="num">
                      {w.received} / {w.accepted} / {w.rejected}
                    </td>
                    <td>
                      <span className={`pill ${statusTone(w.status)}`}>{t(`x03.status.${w.status}`)}</span>
                      {w.failureDetail && <div className="sub2">{w.failureDetail}</div>}
                    </td>
                    <td className="acts">
                      <button
                        type="button"
                        className={`ia${whyWindow ? ' off' : ''}`}
                        disabled={whyWindow !== null}
                        title={whyWindow ? t(whyWindow) : undefined}
                        aria-label={t('x03.reprocessWindowOf', [w.runId])}
                        onClick={() => setFailedWindow(w)}
                      >
                        {t('action.x03.reprocessWindow')}
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </ServerPagination>
      {failedWindow && (
        <ReasonDialog
          maxLength={1000}
          title={t('x03.reprocessWindowTitle', [failedWindow.runId, failedWindow.interfaceCode])}
          onCancel={() => setFailedWindow(null)}
          onConfirm={(reason) => {
            const target = failedWindow;
            setFailedWindow(null);
            setError(null);
            reprocessWindow
              .mutateAsync({ runId: target.runId, reason })
              .then((r) => toast(t('x03.reprocessedWindow', [r.reprocessed.length, r.runId])))
              .catch((cause: unknown) => setError(cause));
          }}
        />
      )}
    </Card>
  );
}

/** "📄 Exception Queue": the queue, whose open items are the Integration Failure Report (SRS-INT-31). */
function ExceptionQueue({ isAdmin, onClose }: { isAdmin: boolean; onClose: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const [openOnly, setOpenOnly] = useState(true);
  const [filter, setFilter] = useState('');
  const [action, setAction] = useState<{ kind: 'reprocess' | 'writeOff'; item: QueueItem } | null>(null);
  const [error, setError] = useState<unknown>(null);
  const reprocess = useReprocess();
  const writeOff = useWriteOff();
  const code = filter.trim().toUpperCase();
  return (
    <Card
      title={openOnly ? t('x03.failureReport') : t('x03.queue')}
      actions={
        <button type="button" className="btn sm" onClick={onClose}>
          {t('common.close')}
        </button>
      }
    >
      <Problem error={error} />
      <div className="toolbar">
        <label className="sub2">
          <input type="checkbox" checked={openOnly} onChange={(e) => setOpenOnly(e.target.checked)} />{' '}
          {t('x03.openOnly')}
        </label>
        <label className="sub2">
          {t('x03.filterInterface')}{' '}
          <input
            maxLength={40}
            value={filter}
            aria-label={t('x03.filterInterface')}
            onChange={(e) => setFilter(e.target.value)}
          />
        </label>
      </div>
      <ServerPagination
        queryKey={[...integrationKeys.exceptions, openOnly, code]}
        fetchPage={integrationPages.exceptions(openOnly, code)}
        personal={false}
        empty={<p className="fl-hint">{t('x03.noExceptions')}</p>}
      >
        {(rows) => (
          <table className="t" aria-label={t('x03.queue')}>
            <thead>
              <tr>
                <Th label={t('x03.interface')} s="SYS" />
                <Th label={t('x03.sourceKey')} s="INTF" />
                <Th label={t('x03.reason')} s="SYS" />
                <Th label={t('x03.raised')} s="SYS" />
                <Th label={t('gen.status')} s="SYS" />
                <Th label={t('x05.actions')} s="SYS" />
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => {
                const whyReprocess = reprocessBlocked(e, isAdmin);
                const whyWriteOff = writeOffBlocked(e, isAdmin);
                return (
                  <tr key={e.id} data-item={e.id}>
                    <td>{e.interfaceCode}</td>
                    <td>
                      {e.sourceKey ? (
                        <>
                          {e.txnType} · {e.sourceKey}
                        </>
                      ) : (
                        <span className="sub2">{t('x03.windowLevel')}</span>
                      )}
                    </td>
                    <td>{e.reason}</td>
                    <td>{dateTime(e.raisedAt)}</td>
                    <td>
                      <span className={`pill ${unresolved(e) ? 'p-bad' : 'p-ok'}`}>
                        {t(`x03.exStatus.${e.status}`)}
                      </span>
                      {e.writtenOffReason && <div className="sub2">{e.writtenOffReason}</div>}
                    </td>
                    <td className="acts">
                      <button
                        type="button"
                        className={`ia${whyReprocess ? ' off' : ''}`}
                        disabled={whyReprocess !== null}
                        title={whyReprocess ? t(whyReprocess) : undefined}
                        aria-label={t('x03.reprocessOf', [e.id])}
                        onClick={() => setAction({ kind: 'reprocess', item: e })}
                      >
                        {t('action.x03.reprocess')}
                      </button>
                      <button
                        type="button"
                        className={`ia${whyWriteOff ? ' off' : ''}`}
                        disabled={whyWriteOff !== null}
                        title={whyWriteOff ? t(whyWriteOff) : undefined}
                        aria-label={t('x03.writeOffOf', [e.id])}
                        onClick={() => setAction({ kind: 'writeOff', item: e })}
                      >
                        {t('action.x03.writeOff')}
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </ServerPagination>
      {action && (
        <ReasonDialog
          maxLength={1000}
          title={
            action.kind === 'reprocess'
              ? t('x03.reprocessTitle', [action.item.id])
              : t('x03.writeOffTitle', [action.item.id])
          }
          onCancel={() => setAction(null)}
          onConfirm={(reason) => {
            const target = action;
            setAction(null);
            setError(null);
            (target.kind === 'reprocess'
              ? reprocess.mutateAsync({ id: target.item.id, reason })
              : writeOff.mutateAsync({ id: target.item.id, reason })
            )
              .then(() =>
                toast(
                  target.kind === 'reprocess'
                    ? t('x03.reprocessed', [target.item.id])
                    : t('x03.writtenOff', [target.item.id]),
                ),
              )
              .catch((cause: unknown) => setError(cause));
          }}
        />
      )}
    </Card>
  );
}

const NO_FILTER: HrmsApiFilter = { selectionClass: '', direction: '', configured: '', status: '', days: '' };

/**
 * The APIs of the HRMS Postman collection (SRS-INT-6, SRS-INT-16, SRS-INT-32), read-only for every
 * X-03 viewer: class, direction, configured or not, the path (never a full URL), the retry, time
 * limit and bulkhead settings, the last call with its outcome and the counts over the chosen window.
 * URLs are deployment settings and are not edited here.
 */
function HrmsApis() {
  const { t } = useI18n();
  const [filter, setFilter] = useState<HrmsApiFilter>(NO_FILTER);
  const set = (key: keyof HrmsApiFilter) => (e: { target: { value: string } }) =>
    setFilter((f) => ({ ...f, [key]: e.target.value }));
  return (
    <div className="card fl-mt" data-testid="x03-hrms-apis">
      <div className="hd">
        <h3>{t('x03.hrms.title')}</h3>
        {/* i18n-ignore: requirement references, not labels */}
        <span className="frs">SRS-INT-6</span>
        <span className="sp" />
      </div>
      <p className="fl-hint">{t('x03.hrms.hint')}</p>
      <div className="fl-row">
        <label className="sub2">
          {t('x03.hrms.class')}{' '}
          <select value={filter.selectionClass} onChange={set('selectionClass')}>
            <option value="">{t('gen.all')}</option>
            <option value="REQUIRED">{t('x03.hrms.class.REQUIRED')}</option>
            <option value="RULE_PENDING">{t('x03.hrms.class.RULE_PENDING')}</option>
          </select>
        </label>
        <label className="sub2">
          {t('x03.hrms.direction')}{' '}
          <select value={filter.direction} onChange={set('direction')}>
            <option value="">{t('gen.all')}</option>
            <option value="INBOUND">{t('x03.hrms.direction.INBOUND')}</option>
            <option value="OUTBOUND">{t('x03.hrms.direction.OUTBOUND')}</option>
          </select>
        </label>
        <label className="sub2">
          {t('x03.hrms.configured')}{' '}
          <select value={filter.configured} onChange={set('configured')}>
            <option value="">{t('gen.all')}</option>
            <option value="true">{t('x03.hrms.configured')}</option>
            <option value="false">{t('x03.hrms.notConfigured')}</option>
          </select>
        </label>
        <label className="sub2">
          {t('x03.hrms.lastStatus')}{' '}
          <select value={filter.status} onChange={set('status')}>
            <option value="">{t('gen.all')}</option>
            {HRMS_STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`x03.hrms.status.${s}`)}
              </option>
            ))}
          </select>
        </label>
        <label className="sub2">
          {t('x03.hrms.days')}{' '}
          <select value={filter.days} onChange={set('days')}>
            <option value="">7</option>
            {HRMS_WINDOWS.map((d) => (
              <option key={d} value={d}>
                {d}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="wrap">
        <ServerPagination
          queryKey={[...integrationKeys.hrmsApis, filter]}
          fetchPage={integrationPages.hrmsApis(filter)}
          personal={false}
          empty={<p className="fl-hint">{t('x03.hrms.noApis')}</p>}
        >
          {(rows) => (
            <table className="t" aria-label={t('x03.hrms.title')}>
              <thead>
                <tr>
                  <Th label={t('x03.hrms.name')} s="SYS" />
                  <Th label={t('x03.hrms.class')} s="SYS" />
                  <Th label={t('x03.hrms.direction')} s="RULE" />
                  <Th label={t('x03.hrms.path')} s="RULE" />
                  <Th label={t('x03.settings')} s="RULE" />
                  <Th label={t('x03.hrms.lastCall')} s="SYS" />
                  <Th label={t('x03.counts')} s="SYS" num />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.code} data-api={r.code}>
                    <td>
                      <b>{t(`x03.hrmsApi.${r.code}`)}</b>
                      <div className="sub2">
                        {r.method} · {r.code}
                      </div>
                    </td>
                    <td>{t(`x03.hrms.class.${r.selectionClass}`)}</td>
                    <td>{t(`x03.hrms.direction.${r.direction}`)}</td>
                    <td>
                      {r.configured ? (
                        <>
                          <span className="pill p-ok">{t('x03.hrms.configured')}</span>
                          <div className="sub2">{r.path}</div>
                        </>
                      ) : (
                        <span className="pill p-warn">{t('x03.hrms.notConfigured')}</span>
                      )}
                    </td>
                    <td className="sub2">{hrmsSettings(r, t)}</td>
                    <td>
                      <span className={`pill ${statusTone(r.lastStatus === 'NONE' ? null : r.lastStatus)}`}>
                        {t(`x03.hrms.status.${r.lastStatus}`)}
                      </span>
                      {r.lastCallAt && <div className="sub2">{dateTime(r.lastCallAt)}</div>}
                    </td>
                    <td className="num">
                      {r.received === null
                        ? t('x03.hrms.noCounts')
                        : `${r.received} / ${r.accepted ?? 0} / ${r.rejected ?? 0}`}
                      {r.refused !== null && r.refused > 0 && (
                        <div className="sub2">
                          {t('x03.hrms.refused')}: {r.refused}
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </div>
    </div>
  );
}

/** SRS-INT-16: the retry, back-off, time limit and bulkhead in force for one API, "—" when none is. */
function hrmsSettings(r: HrmsApiRow, t: Translate): string {
  if (r.retryCount === null && r.bulkheadMaxConcurrentCalls === null) return '—';
  const parts: string[] = [];
  if (r.retryCount !== null) {
    parts.push(`${t('x03.hrms.retry')}: ${r.retryCount}`);
    if (r.backoffSeconds !== null) parts.push(`${t('x03.hrms.backoff')}: ${r.backoffSeconds}`);
    if (r.timeoutSeconds !== null) parts.push(`${t('x03.hrms.timeout')}: ${r.timeoutSeconds}`);
  }
  if (r.bulkheadMaxConcurrentCalls !== null) {
    parts.push(`${t('x03.hrms.bulkheadCalls')}: ${r.bulkheadMaxConcurrentCalls}`);
  }
  if (r.bulkheadMaxWaitMillis !== null) {
    parts.push(`${t('x03.hrms.bulkheadWait')}: ${r.bulkheadMaxWaitMillis}`);
  }
  return parts.join(' · ');
}

/**
 * SRS-INT-16: an interface's resilience parameters, read-only (settings held in configuration, set
 * as per FCI's IT policy at go-live; BR-INT-008); none in force reads "not configured".
 */
function Resilience({ settings }: { settings: InterfaceSettings | null }) {
  const { t } = useI18n();
  if (!settings) {
    return <p className="fl-hint">{t('x03.notConfiguredHint')}</p>;
  }
  return (
    <dl className="kv" aria-label={t('x03.resilience')}>
      <dt>{t('x03.retry')}</dt>
      <dd>{settings.retryCount}</dd>
      <dt>{t('x03.backoff')}</dt>
      <dd>
        {t('x03.seconds', [settings.backoffSeconds])} · {t(`x03.strategy.${settings.backoffStrategy}`)}
      </dd>
      <dt>{t('x03.timeout')}</dt>
      <dd>{t('x03.seconds', [settings.timeoutSeconds])}</dd>
      <dt>{t('x03.threshold')}</dt>
      <dd>{settings.circuitBreakerRequired ? settings.circuitBreakerThreshold : t('x03.noBreaker')}</dd>
      <dt>{t('x03.ackTimeout')}</dt>
      <dd>{settings.ackTimeoutSeconds === null ? '—' : t('x03.seconds', [settings.ackTimeoutSeconds])}</dd>
      <dt>{t('x03.inForceFrom')}</dt>
      <dd>
        {date(settings.effectiveFrom)}
        {!settings.confirmed && ` · ${t('x03.unconfirmed')}`}
      </dd>
    </dl>
  );
}
