import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArrearEvents } from '@/modules/arrear';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * Edge cases of I-08 Arrears & Retrospective Processing (SRS-8-6, SRS-8-13, SRS-21-9): an event
 * raised without a source module, a recompute refused without a reason, a Headquarters order
 * without a subject.
 */

afterEach(() => vi.unstubAllGlobals());

/** The drawn screen's own reads (OD-121): tiles, retro controls, event types, the selected event's periods. */
const SUMMARY = {
  openEvents: 37,
  awaitingApproval: 12,
  arrears: 68200000,
  arrearMembers: 4118,
  recoveries: 4120000,
  recoveryMembers: 318,
  statutory: null,
};
const CONTROLS = { maxRetroMonths: null, postRetirementMonths: null };
const TYPES = [
  { code: 'DA_REVISION', name: 'DA revision', category: 'RATE_REVISION' },
  { code: 'PROMOTION', name: 'Promotion / pay fixation', category: 'PAY_FIXATION' },
];
const I08 = {
  'GET /v1/arrear-events/summary': () => SUMMARY,
  'GET /v1/arrear-events/controls': () => CONTROLS,
  'GET /v1/arrear-events/types': () => TYPES,
  'GET /v1/arrear-events/5/periods': () => [],
  'GET /v1/arrear-events/6/periods': () => [],
  'GET /v1/arrear-events/8/periods': () => [],
  'GET /v1/arrear-events/12/periods': () => [],
};

/** The Arrear / Retro Event Register table (the card titled as drawn). */
async function register(): Promise<HTMLElement> {
  const title = await screen.findByText(t('i08.registerTitle'));
  const table = (title.closest('.card') as HTMLElement).querySelector('table') as HTMLElement;
  // The card is drawn at once; its rows arrive with the page (the list is server-paginated).
  await vi.waitFor(() => {
    if (table.querySelector('.fl-state-loading')) throw new Error('still loading');
  });
  return table;
}
const VIEW = new RegExp('^' + t('common.view') + ' ');

const EVENT = {
  id: 8,
  eventCode: 'DA_REVISION',
  eventName: 'DA revision',
  reference: 'LEAVE HCA-1',
  effectiveFrom: '2026-07-01',
  effectiveTo: null,
  office: 'DH001 - Delhi Division',
  status: 'RAISED',
  postedToYymm: null,
  members: 1,
  arrears: 1200,
  recoveries: 0,
  raisedBy: 'job',
  raisedAt: '2026-10-01T10:00:00',
  source: null,
  singleMember: true,
  stated: false,
  beyondWindow: false,
  hqException: null,
};

const DETAIL = {
  eis: '104882',
  name: 'Rakesh Sharma',
  yymm: '202607',
  payCode: 111,
  component: 'Dearness Allowance',
  original: 10000,
  recomputed: 11200,
  arrear: 1200,
  status: 'CALCULATED',
};

describe('I-08 edges', () => {
  it('SRS-8-11 / BR-LEAVE-003 (I08-3): with no manual event type configured the dialog says so and raises nothing', async () => {
    const calls = backend({
      ...I08,
      'GET /v1/arrear-events/types': () => [],
      'GET /v1/masters/circulars/current': () => [],
      'GET /v1/arrear-events?': (url) => paged([], 0, url),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    await userEvent.click(await screen.findByRole('button', { name: t('i08.newEvent') }));
    const dialog = screen.getByRole('dialog', { name: t('i08.new.title') });
    expect(await within(dialog).findByText(t('i08.new.notConfigured'))).toBeTruthy();
    expect(within(dialog).getByRole('button', { name: t('i08.new.raise') })).toBeDisabled();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('SRS-21-9 / SRS-8-13: an event without a source shows a dash; a refusal without a reason still says so', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) => paged([EVENT], 1, url),
      'GET /v1/arrear-events/8/details?': (url) => paged([DETAIL], 1, url),
      'POST /v1/arrear-events/8/computations': () => ({ computed: false, reason: null }),
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    const table = await register();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    const header = await screen.findByLabelText(t('i08.eventHeader'));
    const source = within(header).getByText(t('i08.sourceModule')).nextElementSibling as HTMLElement;
    expect(source.textContent).toBe('—');
    await userEvent.click(screen.getByRole('button', { name: t('action.i08.recompute') }));
    const refused = t('i08.notComputable', ['']);
    expect(
      await screen.findByText((_, el) => el?.tagName === 'P' && el.textContent === refused),
    ).toBeTruthy();
  });

  it('SRS-8-6: a Headquarters order without a subject is offered by its number alone', async () => {
    backend({
      ...I08,
      'GET /v1/arrear-events?': (url) =>
        paged(
          [{ ...EVENT, id: 12, effectiveFrom: '2020-01-01', beyondWindow: true, source: 'LEAVE' }],
          1,
          url,
        ),
      'GET /v1/arrear-events/12/details?': (url) => paged([], 0, url),
      'GET /v1/masters/circulars/current': () => [{ id: 41, number: 'HQ/ARR/41', subject: null }],
    });
    wrap(<ArrearEvents />, me(['I-08:VIEW', 'I-08:SUBMIT']));
    const table = await register();
    await userEvent.click(within(table).getByRole('button', { name: VIEW }));
    const order = await screen.findByLabelText(t('i08.hqException.order'));
    expect(await within(order).findByRole('option', { name: 'HQ/ARR/41' })).toBeTruthy();
  });
});
