import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PenaltyRecovery } from '@/modules/penalty';
import { backend, me, paged, referenceLists, sent, t, wrap } from './support/harness';

/**
 * I-06 order form for a labour penalty that recovers nothing (SRS-14-1, SRS-14-8, Q-12-1): the nature is chosen
 * from its M-16 list, the amount, instalments and start month give way to the period end, and the check sends the
 * period end or none.
 */

afterEach(() => vi.unstubAllGlobals());

const ALL = ['I-06:VIEW', 'I-06:CREATE'];

function routes() {
  return backend({
    'GET /v1/reference/lists': () =>
      referenceLists({
        PENALTY_NATURE: [
          ['RECOVERY', 'Recovery'],
          ['INCREMENT_STOP', 'Stoppage of increment'],
        ],
      }),
    'GET /v1/penalties?': (url: string) => paged([], 0, url),
    'GET /v1/penalties/requests': (url: string) => paged([], 0, url),
    'GET /v1/penalties/over-and-above': (url: string) => paged([], 0, url),
    'GET /v1/document-types': () => [],
    'POST /v1/penalties/previews': () => ({
      eis: '104882',
      name: 'Rakesh Sharma',
      currentBasic: 50000,
      nature: 'INCREMENT_STOP',
      amount: null,
      instalments: null,
      schedule: [],
      adjusted: false,
      latestNet: 20000,
      netAfter: 20000,
      floor: null,
      belowFloor: false,
      backdated: false,
      proposedBasic: null,
      deferredToFnf: false,
    }),
  });
}

describe('I-06 labour order that recovers nothing', () => {
  it('SRS_14_8_aNatureOtherThanRecoveryAsksThePeriodEndAndChecksWithIt', async () => {
    const calls = routes();
    wrap(<PenaltyRecovery />, me(ALL));
    const nature = (await screen.findByLabelText(/Nature/, { selector: '#i06-nature' })) as HTMLSelectElement;
    await screen.findByRole('option', { name: 'Stoppage of increment' });
    expect(screen.getByLabelText(t('i06.amount'), { selector: '#i06-amount' })).toBeTruthy();
    expect(document.getElementById('i06-periodTo')).toBeNull();
    await userEvent.selectOptions(nature, 'INCREMENT_STOP');
    expect(document.getElementById('i06-amount')).toBeNull();
    expect(document.getElementById('i06-startYymm')).toBeNull();
    await userEvent.type(screen.getByLabelText(t('i06.memberEis'), { selector: '#i06-eis' }), ' 104882 ');
    await userEvent.type(screen.getByLabelText(t('i06.orderReference')), 'VIG/9');
    fireEvent.change(screen.getByLabelText(t('i06.effectiveDate')), { target: { value: '2026-10-01' } });
    const check = screen.getByRole('button', { name: t('i06.check') });
    expect(check).toBeEnabled();
    expect(check).not.toHaveAttribute('title');
    await userEvent.click(check);
    await waitFor(() => expect(sent(calls, 'POST', '/v1/penalties/previews')).toHaveLength(1));
    expect(sent(calls, 'POST', '/v1/penalties/previews')[0]).toEqual({
      eis: '104882',
      nature: 'INCREMENT_STOP',
      orderReference: 'VIG/9',
      effectiveDate: '2026-10-01',
      periodTo: null,
    });
    expect(await screen.findByLabelText(t('i06.preview'))).toBeTruthy();
    fireEvent.change(document.getElementById('i06-periodTo') as HTMLElement, {
      target: { value: '2027-03-31' },
    });
    await userEvent.click(check);
    await waitFor(() => expect(sent(calls, 'POST', '/v1/penalties/previews')).toHaveLength(2));
    expect(sent(calls, 'POST', '/v1/penalties/previews')[1]).toMatchObject({ periodTo: '2027-03-31' });
  });

  it('SRS_14_1_changingTheNatureDropsThePreviewAlreadyShown', async () => {
    routes();
    wrap(<PenaltyRecovery />, me(ALL));
    const nature = (await screen.findByLabelText(/Nature/, { selector: '#i06-nature' })) as HTMLSelectElement;
    await screen.findByRole('option', { name: 'Stoppage of increment' });
    await userEvent.selectOptions(nature, 'INCREMENT_STOP');
    await userEvent.type(screen.getByLabelText(t('i06.memberEis'), { selector: '#i06-eis' }), '104882');
    await userEvent.type(screen.getByLabelText(t('i06.orderReference')), 'VIG/9');
    fireEvent.change(screen.getByLabelText(t('i06.effectiveDate')), { target: { value: '2026-10-01' } });
    await userEvent.click(screen.getByRole('button', { name: t('i06.check') }));
    expect(await screen.findByLabelText(t('i06.preview'))).toBeTruthy();
    await userEvent.selectOptions(nature, 'RECOVERY');
    expect(screen.queryByLabelText(t('i06.preview'))).toBeNull();
    // A recovery needs its amount, instalments and start month again: the check is off with the first reason.
    const check = screen.getByRole('button', { name: t('i06.check') });
    expect(check).toBeDisabled();
    expect(check.getAttribute('title')).toBeTruthy();
  });
});
