import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IntegrationMonitor } from '@/modules/integration';
import type { InterfaceRow } from '@/modules/integration/api/integrationApi';
import { messages } from '@/generated/messages';
import { backend, me, paged, referenceLists, t, wrap } from './support/harness';

/**
 * X-03 and the member e-mail channel (OD-148; SRS-17-12, SRS-INT-6, SRS-INT-16; WF-2002): the e-mailed
 * payslip and Form 16 go through the MEMBER_EMAIL interface, listed with the enterprise interfaces as
 * drawn (system, object, direction, status). Its status cell says, as the HRMS rows do, whether its settings
 * are in force ("Not configured" until FCI's IT sets them, MAIL-2), a breaker paused after repeated
 * failures or probing after the pause, and the open failures (rejected credentials raise one). The queue,
 * quota and bounce counts are not drawn on X-03: they are read through the API only (WF-2002).
 */

afterEach(() => vi.unstubAllGlobals());

const SETTINGS = {
  retryCount: 3,
  backoffSeconds: 300,
  backoffStrategy: 'EXPONENTIAL',
  timeoutSeconds: 30,
  circuitBreakerRequired: true,
  circuitBreakerThreshold: 5,
  ackTimeoutSeconds: null,
  counterpartySla: 'Demo values (member e-mail), not FCI IT policy',
  effectiveFrom: '2020-04-01',
  confirmed: false,
};

function mail(over: Partial<InterfaceRow> = {}): InterfaceRow {
  return {
    code: 'MEMBER_EMAIL',
    name: 'Member e-mail (payslip, Form 16)',
    counterparty: 'SMS_EMAIL',
    direction: 'OUTBOUND',
    transport: null,
    endpointObject: null,
    stagingObject: null,
    consumingScreen: 'E-01',
    specSignedOff: false,
    scopeStatus: 'CONFIRMED',
    active: true,
    status: null,
    lastExchangeAt: null,
    lastGoodExchangeAt: null,
    received: 0,
    accepted: 0,
    rejected: 0,
    openExceptions: 0,
    breakerState: 'CLOSED',
    adapter: false,
    settings: SETTINGS,
    ...over,
  };
}

function routes(rows: InterfaceRow[]) {
  return backend({
    'GET /v1/reference/lists': () =>
      referenceLists({
        INTERFACE_RUN_STATUS: [
          ['OK', 'OK'],
          ['PARTIAL', 'Partial'],
          ['FAILED', 'Failed'],
        ],
      }),
    'GET /v1/integration/summary': () => ({
      interfaces: 1,
      notConfigured: rows.filter((r) => r.settings === null).length,
      openExceptions: rows.reduce((n, r) => n + r.openExceptions, 0),
      failedWindows: 0,
      awaitingI21: 0,
      oldestAwaitingI21: null,
    }),
    'GET /v1/integration/interfaces?': (url) => {
      const shown = url.includes('scope=HRMS') ? [] : rows;
      return paged(shown, shown.length, url);
    },
    'GET /v1/integration/exceptions?': (url) => paged([], 0, url),
    'GET /v1/integration/windows?': (url) => paged([], 0, url),
    'GET /v1/integration/hrms-apis?': (url) => paged([], 0, url),
  });
}

async function emailRow(): Promise<HTMLElement> {
  const heading = await screen.findByRole('heading', { name: t('x03.enterpriseTitle') });
  const table = (heading.closest('.card') as HTMLElement).querySelector('table') as HTMLElement;
  await vi.waitFor(() => expect(table.querySelector('tr[data-code="MEMBER_EMAIL"]')).not.toBeNull());
  return table.querySelector('tr[data-code="MEMBER_EMAIL"]') as HTMLElement;
}

describe('X-03: the member e-mail interface', () => {
  it('SRS-INT-16: listed by its system and name, never a host; configured and closed shows no pause', async () => {
    routes([mail()]);
    wrap(<IntegrationMonitor />, me(['X-03:VIEW']));
    const row = await emailRow();
    expect(within(row).getByText(`SMS_EMAIL - ${t('x03.cp.SMS_EMAIL')}`)).toBeTruthy();
    expect(within(row).getByText('Member e-mail (payslip, Form 16)')).toBeTruthy();
    expect(within(row).getByText(t('x03.dir.OUTBOUND'))).toBeTruthy();
    expect(within(row).getByText(t('x03.noExchange'))).toBeTruthy();
    expect(within(row).getByText(t('x03.configured'))).toBeTruthy();
    expect(within(row).queryByText(t('x03.notConfigured'))).toBeNull();
    expect(within(row).queryByText(t('x03.breaker.OPEN'))).toBeNull();
    expect(within(row).queryByText(t('x03.breaker.HALF_OPEN'))).toBeNull();
    expect(within(row).queryByText(t('x03.openOf', [0]))).toBeNull();
  });

  it('SRS-INT-16 (MAIL-2): without FCI IT settings the interface reads Not configured', async () => {
    routes([mail({ settings: null })]);
    wrap(<IntegrationMonitor />, me(['X-03:VIEW']));
    const row = await emailRow();
    expect(within(row).getByText(t('x03.notConfigured'))).toBeTruthy();
    expect(within(row).queryByText(t('x03.configured'))).toBeNull();
  });

  it('SRS-INT-6: an outage pauses the channel; rejected credentials show as an open failure', async () => {
    routes([mail({ breakerState: 'OPEN', openExceptions: 2, status: 'FAILED' })]);
    wrap(<IntegrationMonitor />, me(['X-03:VIEW']));
    const row = await emailRow();
    expect(within(row).getByText(t('x03.breaker.OPEN'))).toBeTruthy();
    expect(within(row).getByText(t('x03.openOf', [2]))).toBeTruthy();
    const pill = within(row).getByText(t('x03.status.FAILED'));
    expect(pill.className).toContain('p-bad');
  });

  it('SRS-INT-6: after the pause the breaker probes, and the label says so', async () => {
    routes([mail({ breakerState: 'HALF_OPEN', status: 'OK' })]);
    wrap(<IntegrationMonitor />, me(['X-03:VIEW']));
    const row = await emailRow();
    expect(within(row).getByText(t('x03.breaker.HALF_OPEN'))).toBeTruthy();
    expect(within(row).queryByText(t('x03.breaker.OPEN'))).toBeNull();
    expect(within(row).getByText(t('x03.status.OK')).className).toContain('p-ok');
  });

  it('OD-121: the labels of these states exist in English and in Hindi, and differ', () => {
    const keys = [
      'x03.configured',
      'x03.notConfigured',
      'x03.breaker.OPEN',
      'x03.breaker.HALF_OPEN',
      'x03.openOf',
      'x03.cp.SMS_EMAIL',
    ] as const;
    for (const key of keys) {
      const en = messages.en[key];
      const hi = messages.hi[key];
      expect(en, key).toBeTruthy();
      expect(hi, key).toBeTruthy();
      expect(hi).not.toBe(en);
    }
  });
});
