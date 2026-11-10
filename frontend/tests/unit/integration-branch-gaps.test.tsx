import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IntegrationMonitor } from '@/modules/integration';
import { backend, me, paged, referenceLists, t, wrap } from './support/harness';

/**
 * X-03 Integration Monitor branch gap (X-03 l.5173, OD-89): HRMS changes awaiting I-21 whose oldest
 * receipt time the server does not give: the pay-effect tile (Wireframes v6.1, OD-121) shows the count
 * with its drawn sub-line, never a guessed age.
 */

afterEach(() => vi.unstubAllGlobals());

describe('X-03 branch gaps', () => {
  it('X-03 l.5173: changes awaiting I-21 without the oldest time show no age', async () => {
    backend({
      'GET /v1/reference/lists': () => referenceLists({ INTERFACE_RUN_STATUS: [['OK', 'OK']] }),
      'GET /v1/integration/interfaces?': (url) =>
        paged(
          url.includes('scope=HRMS')
            ? [
                {
                  code: 'HRMS_EMP_OTA',
                  name: 'HRMS Employee overtime allowance',
                  counterparty: 'HRMS',
                  direction: 'INBOUND',
                  transport: null,
                  endpointObject: null,
                  stagingObject: null,
                  consumingScreen: null,
                  specSignedOff: true,
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
                  settings: null,
                },
              ]
            : [],
          undefined,
          url,
        ),
      'GET /v1/integration/summary': () => ({
        interfaces: 1,
        notConfigured: 1,
        openExceptions: 0,
        failedWindows: 0,
        awaitingI21: 4,
        oldestAwaitingI21: null,
      }),
    });
    wrap(<IntegrationMonitor />, me(['X-03:VIEW']));
    const label = await screen.findByText(t('x03.tile.payEffect'));
    const tile = label.closest('.tile') as HTMLElement;
    await vi.waitFor(() => expect(tile.querySelector('.big')?.textContent).toBe('4'));
    expect(tile.querySelector('.sub')?.textContent).toBe(t('x03.tile.payEffectSub'));
    // A row never exchanged: no counts, no consuming screen, "no exchange yet".
    const row = (await screen.findByText('HRMS_EMP_OTA')).closest('tr') as HTMLElement;
    expect(row.textContent).toContain(t('x03.noExchange'));
    expect(row.querySelectorAll('td.num')).toHaveLength(3);
    for (const cell of row.querySelectorAll('td.num')) expect(cell.textContent).toBe('—');
    expect(row.querySelectorAll('td')[2]?.textContent).toBe('—');
    expect(screen.getByText('0 / 1')).toBeTruthy();
  });
});
