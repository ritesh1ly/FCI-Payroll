import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IntegrationMonitor } from '@/modules/integration';
import { integrationPages } from '@/modules/integration/orchestration/integrationQueries';
import { backend, me, paged, referenceLists, t, wrap } from './support/harness';

/**
 * X-03 Integration Monitor (SRS-INT-6, SRS-INT-16, SRS-INT-31 .. 33): the Payroll Checker's ✔ on an interface row
 * leads to the Approvals Inbox; the Exception Queue, Reprocess Failed, Contract and Test connection sections close
 * from their Close; an HRMS row with changes awaiting I-21 but no age known reads 0 days; the register reads by
 * counterparty and the fallback uploads read their page.
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

const ROW = {
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
};

function routes(summary: object = {}) {
  return backend({
    'GET /v1/reference/lists': () => referenceLists({ INTERFACE_RUN_STATUS: [['OK', 'OK']] }),
    'GET /v1/integration/hrms-apis?': (url) => paged([], 0, url),
    'GET /v1/integration/summary': () => ({
      interfaces: 1,
      notConfigured: 0,
      openExceptions: 0,
      failedWindows: 0,
      awaitingI21: 2,
      oldestAwaitingI21: null,
      ...summary,
    }),
    'GET /v1/integration/interfaces?': (url) =>
      url.includes('scope=ENTERPRISE') ? paged([], 0, url) : paged([ROW], 1, url),
    'GET /v1/integration/windows?': (url) => paged([], 0, url),
    'GET /v1/integration/exceptions?': (url) => paged([], 0, url),
  });
}

describe('X-03 the Checker and the sections', () => {
  it('R-MC: the Payroll Checker’s ✔ on an interface opens the Approvals Inbox and sends nothing', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign, search: '' });
    const calls = routes();
    wrap(<IntegrationMonitor />, me(['X-03:VIEW'], { role: 'PAYROLL_CHECKER' }));
    const cell = await screen.findByText('HRMS_BULK_RECONCILE');
    const row = cell.closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledWith('/payroll/?s=W-01');
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);
  });

  it('OD-89: changes awaiting I-21 with no oldest date read 0 days on the HRMS row', async () => {
    routes();
    wrap(<IntegrationMonitor />, ['X-03:VIEW']);
    const awaiting = await screen.findByLabelText(t('x03.awaitingOf', ['HRMS_BULK_RECONCILE']));
    expect(awaiting.textContent).toBe(t('x03.awaitingRow', [2, 0]));
  });

  it('SRS-INT-31: the Exception Queue and Reprocess Failed close from their Close buttons', async () => {
    routes();
    wrap(<IntegrationMonitor />, ['X-03:VIEW', 'X-03:EDIT']);
    await screen.findByText('HRMS_BULK_RECONCILE');
    await userEvent.click(screen.getByRole('button', { name: t('x03.queueAct') }));
    let closes = await screen.findAllByRole('button', { name: t('common.close') });
    expect(closes.length).toBeGreaterThanOrEqual(2);
    await userEvent.click(closes[0] as HTMLElement);
    await waitFor(() => expect(screen.queryAllByRole('button', { name: t('common.close') })).toHaveLength(0));
    await userEvent.click(screen.getByRole('button', { name: t('x03.queueAct') }));
    closes = await screen.findAllByRole('button', { name: t('common.close') });
    await userEvent.click(closes[1] as HTMLElement);
    await waitFor(() => expect(screen.queryAllByRole('button', { name: t('common.close') })).toHaveLength(0));
    await userEvent.click(screen.getByRole('button', { name: t('x03.reprocessAct') }));
    await userEvent.click(await screen.findByRole('button', { name: t('common.close') }));
    await waitFor(() => expect(screen.queryAllByRole('button', { name: t('common.close') })).toHaveLength(0));
  });

  it('SRS-INT-6: Contract and Test connection open for the Administrator and close again', async () => {
    routes();
    wrap(<IntegrationMonitor />, ['X-03:VIEW', 'X-03:EDIT']);
    await screen.findByText('HRMS_BULK_RECONCILE');
    await userEvent.click(screen.getByRole('button', { name: t('x03.contractAct') }));
    expect(await screen.findByRole('heading', { name: t('x03.contractAct') })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await waitFor(() => expect(screen.queryByRole('heading', { name: t('x03.contractAct') })).toBeNull());
    await userEvent.click(screen.getByRole('button', { name: t('x03.testAct') }));
    expect(await screen.findByRole('heading', { name: t('x03.testAct') })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await waitFor(() => expect(screen.queryByRole('heading', { name: t('x03.testAct') })).toBeNull());
  });
});

describe('X-03 reads', () => {
  it('SRS-INT-6: the register of one counterparty sends it; every counterparty sends none', async () => {
    const calls = backend({ 'GET /v1/integration/': (url) => paged([], 0, url) });
    await integrationPages.interfaces('EPFO')(0, 20);
    await integrationPages.interfaces('')(1, 50);
    await integrationPages.fallbackUploads(0, 10);
    expect(calls[0]?.url).toMatch(/\/v1\/integration\/interfaces\?counterparty=EPFO&page=0&size=20$/);
    expect(calls[1]?.url).toMatch(/\/v1\/integration\/interfaces\?page=1&size=50$/);
    expect(calls[2]?.url).toMatch(/\/v1\/integration\/hrms-fallback-uploads\?page=0&size=10$/);
  });
});
