import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IntegrationMonitor } from '@/modules/integration';
import type { HrmsApiRow, InterfaceRow, QueueItem, Window } from '@/modules/integration/api/integrationApi';
import {
  ageDays,
  balanced,
  configured,
  reprocessBlocked,
  statusTone,
  unresolved,
  writeOffBlocked,
} from '@/modules/integration/business/integrationRules';
import { withBase } from '@/shared/ui';
import { backend, exportedUrl, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * X-03 Integration Monitor as Wireframes v6.1 draws it (OD-121; plan 23 phase 23b; SRS-INT-6, SRS-INT-16,
 * SRS-INT-17, SRS-INT-31 … SRS-INT-33): the header actions (Exception Queue for every viewer, Reprocess
 * Failed for the Administrator, the X-07 fallback link), the five tiles, the status filter, search and
 * export, the HRMS -> Payroll and the enterprise interfaces, the static panels; the resilience parameters
 * read-only for every viewer (SRS-INT-16; US-GAP-345); Contract, Test connection, Reprocess and Write off
 * for the Administrator alone, drawn disabled with the reason for others.
 */

afterEach(() => vi.unstubAllGlobals());

const SETTINGS = {
  retryCount: 2,
  backoffSeconds: 30,
  backoffStrategy: 'FIXED',
  timeoutSeconds: 30,
  circuitBreakerRequired: true,
  circuitBreakerThreshold: 5,
  ackTimeoutSeconds: 300,
  counterpartySla: 'Demo values',
  effectiveFrom: '2020-04-01',
  confirmed: false,
};

function row(over: Partial<InterfaceRow> = {}): InterfaceRow {
  return {
    code: 'HRMS_BULK_RECONCILE',
    name: 'HRMS bulk reconciliation load',
    counterparty: 'HRMS',
    direction: 'INBOUND',
    transport: null,
    endpointObject: null,
    stagingObject: null,
    consumingScreen: 'X-03',
    specSignedOff: false,
    scopeStatus: 'CONFIRMED',
    active: true,
    status: 'OK',
    lastExchangeAt: '2026-10-28T01:30:00Z',
    lastGoodExchangeAt: '2026-10-28T01:30:00Z',
    received: 10,
    accepted: 10,
    rejected: 0,
    openExceptions: 0,
    breakerState: 'CLOSED',
    adapter: false,
    settings: SETTINGS,
    ...over,
  };
}

function item(over: Partial<QueueItem> = {}): QueueItem {
  return {
    id: 7,
    interfaceCode: 'HRMS_EMP_OTA',
    runId: 3,
    stagingId: 11,
    txnType: 'EMP_OTA',
    sourceKey: 'DEMO-OTA-0002',
    hrmsRequestId: 'DEMO-OTA-0002',
    reason: 'Overtime hours must be more than 0 (demo rejection)',
    status: 'OPEN',
    raisedAt: '2026-10-28T02:00:00Z',
    resolvedAt: null,
    writtenOffBy: null,
    writtenOffReason: null,
    reprocessedStagingId: null,
    adapter: false,
    ...over,
  };
}

function win(over: Partial<Window> = {}): Window {
  return {
    runId: 3,
    interfaceCode: 'HRMS_EMP_OTA',
    direction: 'INBOUND',
    batchReference: 'DEMO-HRMS-EMP_OTA-1',
    windowStart: null,
    windowEnd: null,
    received: 3,
    accepted: 1,
    rejected: 1,
    status: 'FAILED',
    retryAttempt: 0,
    startedAt: '2026-10-28T01:30:00Z',
    completedAt: '2026-10-28T01:31:00Z',
    failureDetail: 'Received 3 is not accepted 1 + rejected 1: records lost in the window',
    ...over,
  };
}

describe('X-03 rules', () => {
  it('SRS-INT-32: a window that does not balance is shown as losing records', () => {
    expect(statusTone('OK')).toBe('p-ok');
    expect(statusTone('PARTIAL')).toBe('p-warn');
    expect(statusTone('FAILED')).toBe('p-bad');
    expect(statusTone(null)).toBe('p-info');
    expect(balanced({ received: 10, accepted: 8, rejected: 2 })).toBe(true);
    expect(balanced({ received: 10, accepted: 7, rejected: 2 })).toBe(false);
    expect(balanced({ received: 0, accepted: 0, rejected: 0 })).toBe(true);
  });

  it('SRS-INT-33: only the Administrator reprocesses or writes off an open item', () => {
    expect(unresolved(item())).toBe(true);
    expect(unresolved(item({ status: 'IN_PROGRESS' }))).toBe(true);
    expect(unresolved(item({ status: 'WRITTEN_OFF' }))).toBe(false);
    expect(reprocessBlocked(item({ adapter: true }), true)).toBeNull();
    expect(reprocessBlocked(item({ adapter: true }), false)).toBe('x03.reason.adminOnly');
    expect(reprocessBlocked(item({ adapter: true, status: 'REPROCESSED' }), true)).toBe('x03.why.resolved');
    expect(reprocessBlocked(item(), true)).toBe('x03.why.noAdapter');
    expect(reprocessBlocked(item({ adapter: true, stagingId: null }), true)).toBe('x03.why.noRecord');
    expect(writeOffBlocked(item(), true)).toBeNull();
    expect(writeOffBlocked(item(), false)).toBe('x03.reason.adminOnly');
    expect(writeOffBlocked(item({ status: 'WRITTEN_OFF' }), true)).toBe('x03.why.resolved');
  });

  it('SRS-INT-16: an interface without settings in force is not configured; the I-21 age in days', () => {
    expect(configured(row())).toBe(true);
    expect(configured(row({ settings: null }))).toBe(false);
    const now = new Date('2026-10-28T12:00:00Z');
    expect(ageDays(null, now)).toBeNull();
    expect(ageDays('2026-10-25T11:00:00Z', now)).toBe(3);
    expect(ageDays('2026-10-28T11:59:00Z', now)).toBe(0);
    expect(ageDays('2026-10-29T00:00:00Z', now)).toBe(0);
  });
});

function hrmsApi(over: Partial<HrmsApiRow> = {}): HrmsApiRow {
  return {
    code: 'request_ota',
    method: 'POST',
    selectionClass: 'REQUIRED',
    direction: 'INBOUND',
    configured: true,
    path: '/api/request-ota',
    interfaceCode: 'HRMS_EMP_OTA',
    retryCount: 3,
    backoffSeconds: 2,
    backoffStrategy: 'EXPONENTIAL',
    timeoutSeconds: 30,
    bulkheadMaxConcurrentCalls: 10,
    bulkheadMaxWaitMillis: 500,
    lastCallAt: '2026-11-04T08:30:00Z',
    lastStatus: 'PARTIAL',
    received: 5,
    accepted: 3,
    rejected: 2,
    refused: 4,
    specSignedOff: false,
    days: 7,
    ...over,
  };
}

const HRMS_APIS: HrmsApiRow[] = [
  hrmsApi(),
  hrmsApi({
    code: 'get_ota_status',
    method: 'GET',
    selectionClass: 'RULE_PENDING',
    configured: false,
    path: null,
    interfaceCode: null,
    retryCount: null,
    backoffSeconds: null,
    backoffStrategy: null,
    timeoutSeconds: null,
    bulkheadMaxConcurrentCalls: null,
    bulkheadMaxWaitMillis: null,
    lastCallAt: null,
    lastStatus: 'NONE',
    received: null,
    accepted: null,
    rejected: null,
    refused: null,
  }),
  hrmsApi({
    code: 'cancel_advance_loan',
    method: 'PUT',
    backoffSeconds: null,
    timeoutSeconds: null,
    bulkheadMaxConcurrentCalls: null,
    bulkheadMaxWaitMillis: null,
    lastStatus: 'OK',
    accepted: null,
    rejected: null,
    refused: 0,
  }),
  hrmsApi({
    code: 'token',
    retryCount: null,
    backoffSeconds: null,
    timeoutSeconds: null,
    lastStatus: 'FAILED',
    lastCallAt: null,
  }),
];

/** The HRMS -> Payroll rows (scope=HRMS). */
const HRMS_ROWS = [
  row(),
  row({
    code: 'HRMS_EMP_OTA',
    name: 'HRMS Employee overtime allowance',
    stagingObject: 'INTF_PAY_EMP_OTA',
    consumingScreen: 'I-21',
    specSignedOff: true,
    status: 'FAILED',
    received: 3,
    accepted: 1,
    rejected: 1,
    openExceptions: 1,
    breakerState: 'OPEN',
    settings: null,
  }),
];

/** The enterprise rows (scope=ENTERPRISE). */
const ENTERPRISE_ROWS = [
  row({
    code: 'EPFO_ECR',
    name: 'EPFO ECR data',
    counterparty: 'EPFO',
    direction: 'OUTBOUND',
    transport: 'FILE',
    status: null,
    lastGoodExchangeAt: null,
    settings: null,
  }),
  row({
    code: 'EBS_AP_INVOICE',
    name: 'Invoices to Accounts Payable',
    counterparty: 'EBS_AP',
    direction: 'OUTBOUND',
    endpointObject: 'AP_INVOICES_INTERFACE',
    status: 'PARTIAL',
  }),
];

const SUMMARY = {
  interfaces: 36,
  notConfigured: 35,
  openExceptions: 1,
  failedWindows: 0,
  awaitingI21: 2,
  oldestAwaitingI21: new Date(Date.now() - 3 * 86_400_000).toISOString(),
};

function routes(extra: Record<string, (url: string) => unknown> = {}) {
  return backend({
    'GET /v1/reference/lists': () =>
      referenceLists({
        INTERFACE_RUN_STATUS: [
          ['OK', 'OK'],
          ['PARTIAL', 'Partial'],
          ['FAILED', 'Failed'],
        ],
      }),
    'GET /v1/integration/hrms-apis?': (url) => {
      const none = url.includes('status=FAILED') || url.includes('class=RULE_PENDING');
      return paged(none ? [] : HRMS_APIS, none ? 0 : HRMS_APIS.length, url);
    },
    'GET /v1/integration/summary': () => SUMMARY,
    'GET /v1/integration/interfaces?': (url) => {
      const rows = url.includes('scope=HRMS')
        ? HRMS_ROWS
        : url.includes('scope=ENTERPRISE')
          ? ENTERPRISE_ROWS
          : [...HRMS_ROWS, ...ENTERPRISE_ROWS];
      return paged(rows, rows.length, url);
    },
    'GET /v1/integration/interfaces/HRMS_BULK_RECONCILE': () => ({
      row: row(),
      frequency: null,
      idempotencyKey: null,
      reconciliation: null,
    }),
    'GET /v1/integration/interfaces/HRMS_EMP_OTA': () => ({
      row: row({ code: 'HRMS_EMP_OTA', settings: null, specSignedOff: true, transport: 'REST' }),
      frequency: 'Daily',
      idempotencyKey: 'HRMS request id',
      reconciliation: 'Counts per window',
    }),
    'GET /v1/integration/windows?': (url) => paged([win()], 1, url),
    'GET /v1/integration/exceptions?': (url) =>
      paged(
        url.includes('open=false')
          ? [
              item(),
              item({
                id: 8,
                status: 'WRITTEN_OFF',
                writtenOffReason: 'Duplicate at source',
                sourceKey: null,
              }),
            ]
          : [item()],
        undefined,
        url,
      ),
    ...extra,
  });
}

const VIEWER = ['X-03:VIEW'];
const ADMIN = ['X-03:VIEW', 'X-03:EDIT'];

/** The table of a drawn card (Tbl) by its title. */
async function tableOf(title: string): Promise<HTMLElement> {
  const heading = await screen.findByRole('heading', { name: title });
  return (heading.closest('.card') as HTMLElement).querySelector('table') as HTMLElement;
}

/** One interface row of a drawn table, once its rows have loaded. */
async function rowOf(table: HTMLElement, code: string): Promise<HTMLElement> {
  await vi.waitFor(() => expect(table.querySelector(`tr[data-code="${code}"]`)).not.toBeNull());
  return table.querySelector(`tr[data-code="${code}"]`) as HTMLElement;
}

/** A tile by its label: its big figure and its sub-line. */
function tile(label: string) {
  const el = screen.getByText(label).closest('.tile') as HTMLElement;
  return {
    big: (el.querySelector('.big') as HTMLElement).textContent,
    sub: (el.querySelector('.sub') as HTMLElement).textContent,
  };
}

const press = (name: string) => userEvent.click(screen.getByRole('button', { name }));

describe('X-03 Integration Monitor', () => {
  // SC-23-0024 (Enterprise Interfaces, HRMS changes awaiting I-21 with their age), SC-23-0025 (X-07 linked)
  it('SRS-INT-32: a viewer reads the tiles, the HRMS and enterprise interfaces; the Administrator actions are drawn locked', async () => {
    const calls = routes();
    wrap(<IntegrationMonitor />, me(VIEWER));
    const hrms = await tableOf(t('x03.hrmsTitle'));
    const bulk = await rowOf(hrms, 'HRMS_BULK_RECONCILE');
    expect(within(bulk).getByText('HRMS_BULK_RECONCILE')).toBeTruthy();
    expect(within(bulk).getByText('HRMS bulk reconciliation load')).toBeTruthy();
    expect(within(bulk).getByText('X-03')).toBeTruthy();
    expect(within(bulk).getByText(t('x03.status.OK'))).toBeTruthy();
    expect(within(bulk).getByText(t('x03.specNotHeld'))).toBeTruthy();
    expect(within(bulk).getAllByText('10')).toHaveLength(2);
    expect(within(bulk).queryByText(t('x03.unbalanced'))).toBeNull();
    // The staging object names the row; counts that do not balance and open exceptions are flagged.
    const ota = await rowOf(hrms, 'HRMS_EMP_OTA');
    expect(within(ota).getByText('INTF_PAY_EMP_OTA')).toBeTruthy();
    expect(within(ota).queryByText('HRMS_EMP_OTA')).toBeNull();
    expect(within(ota).getByText('I-21')).toBeTruthy();
    expect(within(ota).queryByText(t('x03.specNotHeld'))).toBeNull();
    expect(within(ota).getByText(t('x03.unbalanced'))).toBeTruthy();
    expect(within(ota).getByText(t('x03.openOf', [1]))).toBeTruthy();
    expect(within(ota).getByText(t('x03.status.FAILED'))).toBeTruthy();
    // The enterprise interfaces: system, object or endpoint, direction, status.
    const enterprise = await tableOf(t('x03.enterpriseTitle'));
    const epfo = await rowOf(enterprise, 'EPFO_ECR');
    expect(within(epfo).getByText(`EPFO - ${t('x03.cp.EPFO')}`)).toBeTruthy();
    expect(within(epfo).getByText('EPFO ECR data')).toBeTruthy();
    expect(within(epfo).getByText(t('x03.dir.OUTBOUND'))).toBeTruthy();
    expect(within(epfo).getByText(t('x03.noExchange'))).toBeTruthy();
    const ap = await rowOf(enterprise, 'EBS_AP_INVOICE');
    expect(within(ap).getByText('AP_INVOICES_INTERFACE')).toBeTruthy();
    expect(within(ap).getByText(t('x03.status.PARTIAL'))).toBeTruthy();
    expect(within(enterprise).queryByText('HRMS_BULK_RECONCILE')).toBeNull();
    expect(calls.some((c) => c.url.includes('/interfaces?scope=HRMS&page=0'))).toBe(true);
    expect(calls.some((c) => c.url.includes('/interfaces?scope=ENTERPRISE&page=0'))).toBe(true);
    // The five tiles from the summary; the EBS and CPF SSP figures are not reported yet.
    await vi.waitFor(() => expect(tile(t('x03.tile.hrmsApis')).big).toBe('1 / 36'));
    expect(tile(t('x03.tile.hrmsApis')).sub).toBe(t('x03.tile.hrmsApisSub'));
    expect(tile(t('x03.tile.exceptions')).big).toBe('1');
    expect(tile(t('x03.tile.payEffect'))).toEqual({ big: '2', sub: t('x03.tile.payEffectSub') });
    expect(tile(t('x03.tile.ebs')).big).toBe('—');
    expect(tile(t('x03.tile.cpfSsp')).big).toBe('—');
    // Reprocess Failed, Contract and Test connection are the Administrator's: drawn, disabled, with the reason.
    for (const label of [t('x03.reprocessAct'), t('x03.contractAct'), t('x03.testAct')]) {
      const locked = screen.getByRole('button', { name: label });
      expect(locked).toBeDisabled();
      expect(locked.getAttribute('data-why')).toBe(t('x03.why.admin'));
      expect(locked.getAttribute('title')).toBe(t('x03.why.admin'));
    }
    // Nothing the Administrator opens is shown; the fallback uploads are worked on X-07.
    expect(screen.queryByRole('table', { name: t('x03.windows') })).toBeNull();
    expect(screen.queryByRole('table', { name: t('x03.hrms.title') })).toBeNull();
    expect(screen.queryByTestId('x03-hrms-console')).toBeNull();
    expect(screen.getByRole('link', { name: t('x03.fallbackAct') }).getAttribute('href')).toBe(
      withBase('/payroll/?s=X-07'),
    );
    // The static panels as drawn.
    expect(screen.getByRole('heading', { name: t('x03.unavailable') })).toBeTruthy();
    expect(screen.getByText(t('x03.un.noOtherText'))).toBeTruthy();
    expect(screen.getByRole('heading', { name: t('x03.handling') })).toBeTruthy();
    expect(screen.getByText(t('x03.h.neverText'))).toBeTruthy();
    const rules = await tableOf(t('x03.postingRules'));
    expect(within(rules).getAllByRole('row')).toHaveLength(4);
    expect(within(rules).getByText(t('x03.rule.invoiceText'))).toBeTruthy();
  });

  it('SRS-INT-31: every viewer opens the Integration Failure Report from Exception Queue; its actions are disabled with the reason', async () => {
    routes();
    wrap(<IntegrationMonitor />, me(VIEWER));
    await tableOf(t('x03.hrmsTitle'));
    expect(screen.queryByRole('table', { name: t('x03.queue') })).toBeNull();
    await press(t('x03.queueAct'));
    expect(screen.getByRole('heading', { name: t('x03.failureReport') })).toBeTruthy();
    const queue = await screen.findByRole('table', { name: t('x03.queue') });
    const reprocess = within(queue).getByRole('button', { name: t('x03.reprocessOf', [7]) });
    expect(reprocess).toBeDisabled();
    expect(reprocess.getAttribute('title')).toBe(t('x03.reason.adminOnly'));
    const writeOff = within(queue).getByRole('button', { name: t('x03.writeOffOf', [7]) });
    expect(writeOff).toBeDisabled();
    expect(writeOff.getAttribute('title')).toBe(t('x03.reason.adminOnly'));
    expect(within(queue).getByText(`EMP_OTA · DEMO-OTA-0002`)).toBeTruthy();
    // The button toggles the card; so does Close.
    await press(t('x03.queueAct'));
    expect(screen.queryByRole('table', { name: t('x03.queue') })).toBeNull();
    await press(t('x03.queueAct'));
    const report = (await screen.findByRole('table', { name: t('x03.queue') })).closest(
      '.card',
    ) as HTMLElement;
    // SRS-INT-32: the exchange windows open with it for every viewer, their reprocess disabled with the reason.
    expect(await screen.findByRole('table', { name: t('x03.windows') })).toBeTruthy();
    await userEvent.click(within(report).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('table', { name: t('x03.queue') })).toBeNull();
  });

  it('SRS-INT-32: the status filter (INTERFACE_RUN_STATUS) and the search narrow both interface tables', async () => {
    const calls = routes();
    wrap(<IntegrationMonitor />, me(VIEWER));
    await tableOf(t('x03.hrmsTitle'));
    const status = screen.getByRole('combobox', { name: t('x03.statusFilter') });
    expect(within(status).getAllByRole('option')[0]?.textContent).toBe(t('x03.allStatuses'));
    await within(status).findByRole('option', { name: 'Failed' });
    expect(
      within(status)
        .getAllByRole('option')
        .map((o) => (o as HTMLOptionElement).value),
    ).toEqual(['', 'OK', 'PARTIAL', 'FAILED']);
    await userEvent.selectOptions(status, 'FAILED');
    await vi.waitFor(() =>
      expect(calls.some((c) => c.url.includes('/interfaces?scope=HRMS&status=FAILED&page=0'))).toBe(true),
    );
    expect(calls.some((c) => c.url.includes('/interfaces?scope=ENTERPRISE&status=FAILED&page=0'))).toBe(true);
    // The search is trimmed and sent as q, with the status still in force.
    await userEvent.type(screen.getByRole('searchbox', { name: t('wf.searchInList') }), ' ota ');
    await vi.waitFor(() =>
      expect(calls.some((c) => c.url.includes('/interfaces?scope=HRMS&status=FAILED&q=ota&page=0'))).toBe(
        true,
      ),
    );
    expect(calls.some((c) => c.url.includes('/interfaces?scope=ENTERPRISE&status=FAILED&q=ota&page=0'))).toBe(
      true,
    );
    // Back to every status: the parameter is not sent.
    await userEvent.selectOptions(status, '');
    await vi.waitFor(() =>
      expect(calls.some((c) => c.url.includes('/interfaces?scope=HRMS&q=ota&page=0'))).toBe(true),
    );
  });

  // SRS-INT-6, SRS-INT-16, SRS-INT-32: the HRMS Postman APIs, read-only, one setting per API
  it('SRS-INT-6: the Administrator opens the HRMS API catalogue from Contract: path, settings, last outcome and counts, read-only', async () => {
    const calls = routes();
    wrap(<IntegrationMonitor />, me(ADMIN));
    await tableOf(t('x03.hrmsTitle'));
    expect(screen.queryByRole('table', { name: t('x03.hrms.title') })).toBeNull();
    await press(t('x03.contractAct'));
    const table = await screen.findByRole('table', { name: t('x03.hrms.title') });
    const ota = within(table).getByText('POST · request_ota').closest('tr') as HTMLElement;
    expect(within(ota).getByText(t('x03.hrmsApi.request_ota'))).toBeTruthy();
    expect(within(ota).getByText(t('x03.hrms.configured'))).toBeTruthy();
    expect(within(ota).getByText('/api/request-ota')).toBeTruthy();
    expect(within(ota).getByText(t('x03.hrms.class.REQUIRED'))).toBeTruthy();
    expect(within(ota).getByText(t('x03.hrms.direction.INBOUND'))).toBeTruthy();
    expect(within(ota).getByText(t('x03.hrms.status.PARTIAL'))).toBeTruthy();
    expect(within(ota).getByText(/5 \/ 3 \/ 2/)).toBeTruthy();
    expect(within(ota).getByText(`${t('x03.hrms.refused')}: 4`)).toBeTruthy();
    expect(
      within(ota).getByText(
        /Retries: 3 · Back-off \(s\): 2 · Time limit \(s\): 30 · Bulkhead: concurrent calls: 10 · Bulkhead: wait \(ms\): 500/,
      ),
    ).toBeTruthy();
    // Not configured: the badge only, no path; no settings in force reads a dash; no per-API counts.
    const status = within(table).getByText('GET · get_ota_status').closest('tr') as HTMLElement;
    expect(within(status).getByText(t('x03.hrms.notConfigured'))).toBeTruthy();
    expect(within(status).getByText(t('x03.hrms.class.RULE_PENDING'))).toBeTruthy();
    expect(within(status).getByText(t('x03.hrms.status.NONE'))).toBeTruthy();
    expect(within(status).getByText(t('x03.hrms.noCounts'))).toBeTruthy();
    expect(within(status).getByText('—')).toBeTruthy();
    // Partial settings: retry only, then bulkhead only; zero refusals are not shown.
    const cancel = within(table).getByText('PUT · cancel_advance_loan').closest('tr') as HTMLElement;
    expect(within(cancel).getByText('Retries: 3')).toBeTruthy();
    expect(within(cancel).queryByText(new RegExp(`${t('x03.hrms.refused')}:`))).toBeNull();
    expect(within(cancel).getByText(/5 \/ 0 \/ 0/)).toBeTruthy();
    const token = within(table).getByText('POST · token').closest('tr') as HTMLElement;
    expect(
      within(token).getByText(/^Bulkhead: concurrent calls: 10 · Bulkhead: wait \(ms\): 500$/),
    ).toBeTruthy();
    expect(within(token).getByText(t('x03.hrms.status.FAILED'))).toBeTruthy();
    // Read-only: nothing on the list acts.
    expect(within(table).queryAllByRole('button')).toHaveLength(0);
    expect(calls.some((c) => c.url.includes('/v1/integration/hrms-apis') && c.method === 'GET')).toBe(true);
    // Contract toggles the catalogue closed again.
    await press(t('x03.contractAct'));
    expect(screen.queryByRole('table', { name: t('x03.hrms.title') })).toBeNull();
  });

  it('SRS-INT-6: the HRMS APIs are narrowed by class, direction, configured, outcome and window, with a reasoned empty state', async () => {
    const calls = routes();
    wrap(<IntegrationMonitor />, me(ADMIN));
    await tableOf(t('x03.hrmsTitle'));
    await press(t('x03.contractAct'));
    await screen.findByRole('table', { name: t('x03.hrms.title') });
    const card = screen.getByTestId('x03-hrms-apis');
    const pick = async (label: string, value: string) => {
      await userEvent.selectOptions(within(card).getByLabelText(label), value);
    };
    await pick(t('x03.hrms.direction'), 'OUTBOUND');
    await pick(t('x03.hrms.configured'), 'false');
    await pick(t('x03.hrms.lastStatus'), 'NONE');
    await pick(t('x03.hrms.days'), '30');
    await vi.waitFor(() =>
      expect(
        calls.some(
          (c) =>
            c.url.includes('/v1/integration/hrms-apis') &&
            c.url.includes('direction=OUTBOUND') &&
            c.url.includes('configured=false') &&
            c.url.includes('status=NONE') &&
            c.url.includes('days=30'),
        ),
      ).toBe(true),
    );
    await pick(t('x03.hrms.class'), 'RULE_PENDING');
    expect(await within(card).findByText(t('x03.hrms.noApis'))).toBeTruthy();
    expect(calls.some((c) => c.url.includes('class=RULE_PENDING'))).toBe(true);
  });

  it('SRS-INT-32: the Integration Audit Trail is exported with X-03 EXPORT only; without it Export says why and sends nothing', async () => {
    const calls = routes();
    const view = wrap(<IntegrationMonitor />, me(VIEWER));
    await tableOf(t('x03.hrmsTitle'));
    const before = calls.length;
    await press(t('wf.export'));
    expect(await screen.findByText(t('x03.why.export'))).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(calls.slice(before).some((c) => c.url.includes('/export'))).toBe(false);
    view.unmount();
    routes();
    wrap(<IntegrationMonitor />, me(['X-03:VIEW', 'X-03:EXPORT']));
    await tableOf(t('x03.hrmsTitle'));
    expect(await exportedUrl('CSV')).toBe('/nginx/fci-payroll/v1/integration/windows/export?format=CSV');
    const { integrationApi } = await import('@/modules/integration/api/integrationApi');
    expect(integrationApi.auditTrailUrl('')).toBe('/nginx/fci-payroll/v1/integration/windows/export');
    expect(integrationApi.auditTrailUrl('HRMS_EMP_OTA')).toBe(
      '/nginx/fci-payroll/v1/integration/windows/export?interface=HRMS_EMP_OTA',
    );
  });

  it('SRS-INT-22: the HRMS fallback uploads are worked on X-07, linked from the screen; X-03 no longer lists them', async () => {
    const calls = routes();
    wrap(<IntegrationMonitor />, me(VIEWER));
    await tableOf(t('x03.hrmsTitle'));
    const link = screen.getByRole('link', { name: t('x03.fallbackAct') });
    expect(link.getAttribute('href')).toBe(withBase('/payroll/?s=X-07'));
    expect(screen.queryByRole('heading', { name: t('x03.fallback') })).toBeNull();
    expect(calls.some((c) => c.url.includes('/hrms-fallback-uploads'))).toBe(false);
    // The fallback rules are still stated in the "HRMS unavailable" panel.
    expect(screen.getByText(t('x03.un.fallbackA'))).toBeTruthy();
    expect(screen.getByText(t('x03.un.fallbackB'))).toBeTruthy();
  });

  it('SRS-INT-33: Contract is for the Administrator alone, disabled with the reason for a viewer', async () => {
    routes();
    wrap(<IntegrationMonitor />, me(VIEWER));
    const table = await tableOf(t('x03.hrmsTitle'));
    const bulk = await rowOf(table, 'HRMS_BULK_RECONCILE');
    const contract = within(bulk).getByRole('button', {
      name: t('x03.contractOf', ['HRMS_BULK_RECONCILE']),
    });
    expect(contract).toBeDisabled();
    expect(contract.getAttribute('data-why')).toBe(t('x03.why.rateAdmin'));
    expect(contract.getAttribute('title')).toBe(t('wf.editOff', [t('x03.why.rateAdmin')]));
    const header = screen.getByRole('button', { name: t('x03.contractAct') });
    expect(header).toBeDisabled();
    expect(header.getAttribute('data-why')).toBe(t('x03.why.admin'));
  });

  it('SRS-INT-16 (US-GAP-345): a viewer who is not the Administrator reads the resilience parameters read-only', async () => {
    const calls = routes();
    wrap(<IntegrationMonitor />, me(VIEWER));
    const table = await tableOf(t('x03.hrmsTitle'));
    const bulk = await rowOf(table, 'HRMS_BULK_RECONCILE');
    const view = within(bulk).getByRole('button', { name: t('x03.resilienceOf', ['HRMS_BULK_RECONCILE']) });
    expect(view).toBeEnabled();
    expect(view.textContent).toBe(t('wf.view'));
    await userEvent.click(view);
    expect(
      screen.getByRole('heading', { name: t('x03.resilienceOf', ['HRMS_BULK_RECONCILE']) }),
    ).toBeTruthy();
    const params = screen.getByLabelText(t('x03.resilience'));
    expect(within(params).getByText('2')).toBeTruthy();
    expect(within(params).getByText(t('x03.seconds', [30]) + ' · ' + t('x03.strategy.FIXED'))).toBeTruthy();
    expect(within(params).getAllByText(t('x03.seconds', [30]))).toHaveLength(1);
    expect(within(params).getByText('5')).toBeTruthy();
    expect(within(params).getByText(t('x03.seconds', [300]))).toBeTruthy();
    expect(within(params).getByText(new RegExp(t('x03.unconfirmed')))).toBeTruthy();
    // The in-force date is shown as a date (UI #93), not raw ISO text.
    expect(within(params).getByText(/Apr.2020/)).toBeTruthy();
    // Read-only: no input, and the Contract read (X-03 EDIT) is never called.
    expect(within(params).queryByRole('textbox')).toBeNull();
    expect(calls.some((c) => c.url.includes('/v1/integration/interfaces/'))).toBe(false);
    // An interface with no settings in force says so; Close hides the parameters.
    const ota = await rowOf(table, 'HRMS_EMP_OTA');
    await userEvent.click(within(ota).getByRole('button', { name: t('x03.resilienceOf', ['HRMS_EMP_OTA']) }));
    expect(screen.getByText(t('x03.notConfiguredHint'))).toBeTruthy();
    await press(t('common.close'));
    expect(screen.queryByText(t('x03.notConfiguredHint'))).toBeNull();
  });

  it('SRS-INT-33: Contract shows the specification held; the parameters stay in the list for every viewer', async () => {
    const calls = routes();
    wrap(<IntegrationMonitor />, me(ADMIN));
    const table = await tableOf(t('x03.hrmsTitle'));
    const bulk = await rowOf(table, 'HRMS_BULK_RECONCILE');
    await userEvent.click(
      within(bulk).getByRole('button', { name: t('x03.contractOf', ['HRMS_BULK_RECONCILE']) }),
    );
    expect(await screen.findByText(t('x03.resilienceInList'))).toBeTruthy();
    expect(
      screen.getByRole('heading', { name: t('x03.contractTitle', ['HRMS_BULK_RECONCILE']) }),
    ).toBeTruthy();
    expect(screen.queryByLabelText(t('x03.resilience'))).toBeNull();
    expect(screen.getAllByText(t('x03.notStated')).length).toBeGreaterThan(0);
    expect(screen.getByText(t('x03.specNotHeld', []), { selector: 'dd' })).toBeTruthy();
    expect(calls.some((c) => c.url.endsWith('/v1/integration/interfaces/HRMS_BULK_RECONCILE'))).toBe(true);
    const ota = await rowOf(table, 'HRMS_EMP_OTA');
    await userEvent.click(within(ota).getByRole('button', { name: t('x03.contractOf', ['HRMS_EMP_OTA']) }));
    expect(await screen.findByText('HRMS request id')).toBeTruthy();
    expect(screen.getByText('Counts per window')).toBeTruthy();
    expect(screen.getByText('Daily')).toBeTruthy();
    expect(screen.getByText('REST')).toBeTruthy();
    expect(screen.getByText(t('x03.specHeld'))).toBeTruthy();
    await press(t('common.close'));
    expect(screen.queryByText('HRMS request id')).toBeNull();
  });

  it('SRS-INT-16: without a breaker or an acknowledgement limit the parameters say so; a window without a batch shows a dash', async () => {
    routes({
      'GET /v1/integration/interfaces?': (url: string) =>
        paged(
          url.includes('scope=HRMS')
            ? [
                row({
                  settings: {
                    ...SETTINGS,
                    circuitBreakerRequired: false,
                    circuitBreakerThreshold: null,
                    ackTimeoutSeconds: null,
                    confirmed: true,
                  },
                }),
              ]
            : [],
          undefined,
          url,
        ),
      'GET /v1/integration/windows?': (url: string) =>
        paged(
          [
            win({
              runId: 4,
              interfaceCode: 'HRMS_BULK_RECONCILE',
              batchReference: null,
              received: 1,
              accepted: 1,
              rejected: 0,
              status: 'OK',
              failureDetail: null,
            }),
          ],
          1,
          url,
        ),
    });
    wrap(<IntegrationMonitor />, me(ADMIN));
    await tableOf(t('x03.hrmsTitle'));
    await press(t('x03.reprocessAct'));
    const windows = await screen.findByRole('table', { name: t('x03.windows') });
    expect(within(windows).getByText('—')).toBeTruthy();
    // A window with nothing rejected or failed has nothing to reprocess.
    const nothing = within(windows).getByRole('button', { name: t('x03.reprocessWindowOf', [4]) });
    expect(nothing).toBeDisabled();
    expect(nothing.getAttribute('title')).toBe(t('x03.why.nothingFailed'));
    const table = await tableOf(t('x03.hrmsTitle'));
    const bulk = await rowOf(table, 'HRMS_BULK_RECONCILE');
    await userEvent.click(
      within(bulk).getByRole('button', { name: t('x03.resilienceOf', ['HRMS_BULK_RECONCILE']) }),
    );
    const params = await screen.findByLabelText(t('x03.resilience'));
    expect(within(params).getByText(t('x03.noBreaker'))).toBeTruthy();
    expect(within(params).getByText('—')).toBeTruthy();
    expect(within(params).queryByText(new RegExp(t('x03.unconfirmed')))).toBeNull();
  });

  it('SRS-INT-33: Reprocess Failed opens the exchange windows; a failed window is reprocessed with a reason once it has an adapter', async () => {
    const calls = routes({
      'GET /v1/integration/windows?': (url: string) =>
        paged([win(), win({ runId: 5, adapter: true })], 2, url),
      'POST /v1/integration/windows/5/reprocess': () => ({
        runId: 5,
        interfaceCode: 'HRMS_EMP_OTA',
        reprocessed: [11, 12],
      }),
    });
    wrap(<IntegrationMonitor />, me(['X-03:VIEW', 'X-03:EDIT', 'X-03:EXPORT']));
    await tableOf(t('x03.hrmsTitle'));
    await press(t('x03.reprocessAct'));
    const windows = await screen.findByRole('table', { name: t('x03.windows') });
    expect(within(windows).getAllByText(/records lost in the window/)).toHaveLength(2);
    expect(within(windows).getAllByText('3 / 1 / 1')).toHaveLength(2);
    const blocked = within(windows).getByRole('button', { name: t('x03.reprocessWindowOf', [3]) });
    expect(blocked).toBeDisabled();
    expect(blocked.getAttribute('title')).toBe(t('x03.why.noAdapter'));
    // The windows card exports the audit trail of the interface it is narrowed to.
    expect(screen.getByRole('button', { name: t('x03.auditTrail') })).toBeTruthy();
    // A cancelled reason sends nothing.
    await userEvent.click(within(windows).getByRole('button', { name: t('x03.reprocessWindowOf', [5]) }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(sent(calls, 'POST', '/windows/5/reprocess')).toHaveLength(0);
    await userEvent.click(within(windows).getByRole('button', { name: t('x03.reprocessWindowOf', [5]) }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(t('x03.reprocessWindowTitle', [5, 'HRMS_EMP_OTA']))).toBeTruthy();
    await userEvent.type(within(dialog).getByRole('textbox'), 'Source fixed');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByText(t('x03.reprocessedWindow', [2, 5]))).toBeTruthy();
    expect(sent(calls, 'POST', '/windows/5/reprocess')).toEqual([{ reason: 'Source fixed' }]);
    // The button toggles the card closed.
    await press(t('x03.reprocessAct'));
    expect(screen.queryByRole('table', { name: t('x03.windows') })).toBeNull();
  });

  it('SRS-INT-33: a failed window reprocess shows the refusal', async () => {
    routes({
      'GET /v1/integration/windows?': (url: string) => paged([win({ adapter: true })], 1, url),
      'POST /v1/integration/windows/3/reprocess': () =>
        problem(502, 'INT-502-CALL-FAILED', ['HRMS_EMP_OTA', 1, 'down']),
    });
    wrap(<IntegrationMonitor />, me(ADMIN));
    await tableOf(t('x03.hrmsTitle'));
    await press(t('x03.reprocessAct'));
    const windows = await screen.findByRole('table', { name: t('x03.windows') });
    // Without X-03 EXPORT the windows card offers no audit trail export.
    expect(screen.queryByRole('button', { name: t('x03.auditTrail') })).toBeNull();
    await userEvent.click(within(windows).getByRole('button', { name: t('x03.reprocessWindowOf', [3]) }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Retry');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByText(t('error.INT-502-CALL-FAILED', ['HRMS_EMP_OTA', 1, 'down']))).toBeTruthy();
  });

  it('SRS-INT-31: the Administrator writes off with a reason; Reprocess waits for an adapter; the whole queue reads', async () => {
    const calls = routes({
      'POST /v1/integration/exceptions/7/write-off': () => item({ status: 'WRITTEN_OFF' }),
    });
    wrap(<IntegrationMonitor />, me(ADMIN));
    await tableOf(t('x03.hrmsTitle'));
    await press(t('x03.queueAct'));
    const queue = await screen.findByRole('table', { name: t('x03.queue') });
    const reprocess = within(queue).getByRole('button', { name: t('x03.reprocessOf', [7]) });
    expect(reprocess).toBeDisabled();
    expect(reprocess.getAttribute('title')).toBe(t('x03.why.noAdapter'));
    await userEvent.click(within(queue).getByRole('button', { name: t('x03.writeOffOf', [7]) }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(t('x03.writeOffTitle', [7]))).toBeTruthy();
    await userEvent.type(within(dialog).getByRole('textbox'), 'Corrected at source');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByText(t('x03.writtenOff', [7]))).toBeTruthy();
    expect(sent(calls, 'POST', '/write-off').at(-1)).toEqual({ reason: 'Corrected at source' });
    await userEvent.click(screen.getByLabelText(t('x03.openOnly')));
    expect(await screen.findByText('Duplicate at source')).toBeTruthy();
    expect(screen.getByRole('heading', { name: t('x03.queue') })).toBeTruthy();
    const all = screen.getByRole('table', { name: t('x03.queue') });
    expect(within(all).getByText(t('x03.windowLevel'))).toBeTruthy();
    const done = within(all).getByRole('button', { name: t('x03.writeOffOf', [8]) });
    expect(done).toBeDisabled();
    expect(done.getAttribute('title')).toBe(t('x03.why.resolved'));
  });

  it('SRS-INT-33: Test connection answers its outcome; a refusal is shown; a reprocess with an adapter is sent; a cancelled reason sends nothing', async () => {
    const calls = routes({
      'POST /v1/integration/interfaces/HRMS_BULK_RECONCILE/test-connection': () => ({
        interfaceCode: 'HRMS_BULK_RECONCILE',
        outcome: 'NO_ADAPTER',
      }),
      'POST /v1/integration/interfaces/EPFO_ECR/test-connection': () =>
        problem(404, 'INT-404-INTERFACE', ['EPFO_ECR']),
      'GET /v1/integration/exceptions?': (url) => paged([item({ adapter: true })], 1, url),
      'POST /v1/integration/exceptions/7/reprocess': () =>
        problem(502, 'INT-502-CALL-FAILED', ['HRMS_EMP_OTA', 1, 'down']),
    });
    wrap(<IntegrationMonitor />, me(ADMIN));
    await tableOf(t('x03.hrmsTitle'));
    await press(t('x03.testAct'));
    // The test card lists every interface (no scope) and holds the HRMS API console.
    const table = await screen.findByRole('table', { name: t('x03.testAct') });
    await within(table).findByText('EPFO_ECR');
    expect(within(table).getByText('HRMS_BULK_RECONCILE')).toBeTruthy();
    expect(within(table).getByText('HRMS_EMP_OTA')).toBeTruthy();
    expect(calls.some((c) => /\/interfaces\?page=0/.test(c.url))).toBe(true);
    expect(screen.getByTestId('x03-hrms-console')).toBeTruthy();
    await userEvent.click(
      within(table).getByRole('button', { name: t('x03.testOf', ['HRMS_BULK_RECONCILE']) }),
    );
    expect(await screen.findByText(t('x03.tested', ['HRMS_BULK_RECONCILE', 'NO_ADAPTER']))).toBeTruthy();
    await userEvent.click(within(table).getByRole('button', { name: t('x03.testOf', ['EPFO_ECR']) }));
    expect(await screen.findByText(t('error.INT-404-INTERFACE', ['EPFO_ECR']))).toBeTruthy();
    await press(t('x03.queueAct'));
    const queue = await screen.findByRole('table', { name: t('x03.queue') });
    const reprocess = within(queue).getByRole('button', { name: t('x03.reprocessOf', [7]) });
    expect(reprocess).toBeEnabled();
    await userEvent.click(reprocess);
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(sent(calls, 'POST', '/reprocess')).toHaveLength(0);
    await userEvent.click(reprocess);
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(t('x03.reprocessTitle', [7]))).toBeTruthy();
    await userEvent.type(within(dialog).getByRole('textbox'), 'Source fixed');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByText(t('error.INT-502-CALL-FAILED', ['HRMS_EMP_OTA', 1, 'down']))).toBeTruthy();
    expect(sent(calls, 'POST', '/reprocess').at(-1)).toEqual({ reason: 'Source fixed' });
  });

  it('SRS-INT-33: a reprocess that succeeds is announced; empty lists say so', async () => {
    routes({
      'GET /v1/integration/interfaces?': (url) => paged([], 0, url),
      'GET /v1/integration/windows?': (url) => paged([], 0, url),
      'GET /v1/integration/exceptions?': (url) => paged([], 0, url),
      'GET /v1/integration/summary': () => ({
        interfaces: 0,
        notConfigured: 0,
        openExceptions: 0,
        failedWindows: 0,
        awaitingI21: 0,
        oldestAwaitingI21: null,
      }),
    });
    wrap(<IntegrationMonitor />, me(ADMIN));
    // Both interface tables say no interface is registered.
    await vi.waitFor(() => expect(screen.getAllByText(t('x03.noInterfaces'))).toHaveLength(2));
    await vi.waitFor(() => expect(tile(t('x03.tile.hrmsApis')).big).toBe('0 / 0'));
    expect(tile(t('x03.tile.payEffect'))).toEqual({ big: '0', sub: t('x03.tile.payEffectSub') });
    expect(tile(t('x03.tile.exceptions')).big).toBe('0');
    await press(t('x03.reprocessAct'));
    expect(await screen.findByText(t('x03.noWindows'))).toBeTruthy();
    await press(t('x03.queueAct'));
    expect(await screen.findByText(t('x03.noExceptions'))).toBeTruthy();
    await press(t('x03.testAct'));
    await vi.waitFor(() => expect(screen.getAllByText(t('x03.noInterfaces'))).toHaveLength(3));
  });

  it('SRS-INT-31: a reprocess through the adapter is announced', async () => {
    const calls = routes({
      'GET /v1/integration/exceptions?': (url) => paged([item({ adapter: true })], 1, url),
      'POST /v1/integration/exceptions/7/reprocess': () => item({ status: 'REPROCESSED', adapter: true }),
    });
    wrap(<IntegrationMonitor />, me(ADMIN));
    await tableOf(t('x03.hrmsTitle'));
    await press(t('x03.queueAct'));
    const queue = await screen.findByRole('table', { name: t('x03.queue') });
    await userEvent.click(within(queue).getByRole('button', { name: t('x03.reprocessOf', [7]) }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Transient failure over');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByText(t('x03.reprocessed', [7]))).toBeTruthy();
    expect(sent(calls, 'POST', '/reprocess')).toEqual([{ reason: 'Transient failure over' }]);
  });

  it('SRS-INT-6: an unknown summary keeps the tiles at a dash', async () => {
    routes({
      'GET /v1/integration/summary': () => problem(503, 'INT-503-NOT-CONFIGURED', ['HRMS_BULK_RECONCILE']),
    });
    wrap(<IntegrationMonitor />, me(VIEWER));
    await tableOf(t('x03.hrmsTitle'));
    expect(await screen.findByText(t('error.INT-503-NOT-CONFIGURED', ['HRMS_BULK_RECONCILE']))).toBeTruthy();
    expect(tile(t('x03.tile.hrmsApis')).big).toBe('—');
    expect(tile(t('x03.tile.exceptions')).big).toBe('—');
    expect(tile(t('x03.tile.payEffect'))).toEqual({ big: '—', sub: t('x03.tile.payEffectSub') });
  });
});

describe('X-03 group g5 fixes', () => {
  // X-03 l.5173 (OD-89): the changes awaiting I-21 with the age of the oldest (the pay-effect tile as drawn);
  // UI #94: every viewer narrows the exception queue, the Administrator also the windows, to one interface.
  it('X-03 l.5173: the pay-effect tile shows the age of changes awaiting I-21; any viewer filters the queue by interface', async () => {
    const calls = routes();
    wrap(<IntegrationMonitor />, me(VIEWER));
    await tableOf(t('x03.hrmsTitle'));
    await vi.waitFor(() =>
      expect(tile(t('x03.tile.payEffect'))).toEqual({ big: '2', sub: t('x03.tile.payEffectSub') }),
    );
    // The HRMS rows show the age of the changes awaiting I-21 too (OD-89).
    expect((await tableOf(t('x03.hrmsTitle'))).textContent).toContain(t('x03.awaitingRow', [2, 3]));
    await press(t('x03.queueAct'));
    const queue = (await screen.findByRole('table', { name: t('x03.queue') })).closest(
      '.card',
    ) as HTMLElement;
    await userEvent.type(within(queue).getByLabelText(t('x03.filterInterface')), 'hrms_emp_ota');
    await vi.waitFor(() =>
      expect(
        calls.some((c) => c.url.includes('/exceptions?') && c.url.includes('interface=HRMS_EMP_OTA')),
      ).toBe(true),
    );
  });

  it('X-03 l.5173: the Administrator narrows the exchange windows to one interface', async () => {
    const calls = routes();
    wrap(<IntegrationMonitor />, me(ADMIN));
    await tableOf(t('x03.hrmsTitle'));
    await press(t('x03.reprocessAct'));
    await screen.findByRole('table', { name: t('x03.windows') });
    await userEvent.type(screen.getByLabelText(t('x03.filterInterface')), ' hrms_emp_ota ');
    await vi.waitFor(() =>
      expect(calls.some((c) => c.url.includes('/windows?') && c.url.includes('interface=HRMS_EMP_OTA'))).toBe(
        true,
      ),
    );
  });

  it('SRS-INT-33: a Test connection refreshes the lists, whatever its outcome', async () => {
    const calls = routes({
      'POST /v1/integration/interfaces/HRMS_BULK_RECONCILE/test-connection': () =>
        problem(503, 'INT-503-NOT-CONFIGURED', ['HRMS_BULK_RECONCILE']),
    });
    wrap(<IntegrationMonitor />, me(ADMIN));
    await tableOf(t('x03.hrmsTitle'));
    await press(t('x03.testAct'));
    const table = await screen.findByRole('table', { name: t('x03.testAct') });
    await within(table).findByText('HRMS_BULK_RECONCILE');
    const before = calls.filter((c) => c.url.includes('/v1/integration/interfaces?scope=HRMS')).length;
    await userEvent.click(
      within(table).getByRole('button', { name: t('x03.testOf', ['HRMS_BULK_RECONCILE']) }),
    );
    expect(await screen.findByText(t('error.INT-503-NOT-CONFIGURED', ['HRMS_BULK_RECONCILE']))).toBeTruthy();
    await vi.waitFor(() =>
      expect(
        calls.filter((c) => c.url.includes('/v1/integration/interfaces?scope=HRMS')).length,
      ).toBeGreaterThan(before),
    );
  });
});
