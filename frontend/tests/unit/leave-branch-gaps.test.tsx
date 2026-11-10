import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LeaveEncashmentArrears, LeavePayParameters } from '@/modules/leave';
import { leaveApi } from '@/modules/leave/api/leaveApi';
import { backend, me, paged, problem, t, wrap } from './support/harness';

/**
 * Leave module branch gaps (M-20 Leave Encashment Parameters, I-24 Leave Encashment Arrear;
 * SRS-LV-14, SRS-8-23): a superseded version of a code the form does not know, a version without a
 * leave type, a circular without a subject or the circulars not loaded, the versions of every code,
 * an old pay that is not known.
 */

afterEach(() => vi.unstubAllGlobals());

const VERSION = {
  id: 5,
  code: 'LWP_COMPONENTS',
  leaveType: null,
  number: null,
  text: 'BASIC,DA',
  versionNo: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  status: 'ACTIVE',
  approvalId: 7,
  circularNumber: null,
};

describe('M-20 branch gaps', () => {
  it('SRS-LV-14: superseding a code the form does not know checks it as a divisor and refuses it', async () => {
    backend({
      'GET /v1/leave-pay-parameters?': (url) => paged([VERSION], 1, url),
      'GET /v1/masters/circulars/current': () => [{ id: 12, number: 'HQ/LV/2026/3', subject: null }],
    });
    wrap(<LeavePayParameters />, me(['M-20:VIEW', 'M-20:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.M-20') });
    await userEvent.click(within(table).getByRole('button', { name: t('m20.supersede') }));
    // Taken as a divisor: no leave type is asked (a version without one leaves it empty).
    expect(screen.queryByLabelText(t('m20.leaveType'))).toBeNull();
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect(submit).toBeDisabled();
    expect(submit.getAttribute('title')).toBe(t('m20.problem.parameter'));
    // A circular without a subject reads by its number alone.
    const circular = screen.getByLabelText(t('m20.circular'));
    await vi.waitFor(() =>
      expect(within(circular).getByRole('option', { name: 'HQ/LV/2026/3' })).toBeTruthy(),
    );
  });

  it('SRS-LV-14: with the circulars not loaded only "no circular" is offered', async () => {
    backend({
      'GET /v1/leave-pay-parameters?': (url) => paged([], 0, url),
      'GET /v1/masters/circulars/current': () => problem(503, 'SYS-503'),
    });
    wrap(<LeavePayParameters />, me(['M-20:VIEW', 'M-20:CREATE']));
    expect(await screen.findByText(t('m20.none'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('m20.newVersion') }));
    const circular = screen.getByLabelText(t('m20.circular'));
    expect(
      within(circular)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual([t('m20.noCircular')]);
  });

  it('SRS-LV-14: the versions of every code are asked without a code filter', async () => {
    const calls = backend({ 'GET /v1/leave-pay-parameters': (url) => paged([], 0, url) });
    await leaveApi.versions(undefined, 0, 20);
    await leaveApi.versions('LWP_DIVISOR', 0, 20);
    expect(calls[0]?.url).not.toContain('code=');
    expect(calls[1]?.url).toContain('code=LWP_DIVISOR');
  });
});

describe('I-24 branch gaps', () => {
  it('SRS-8-23: an old pay with its Basic unknown reads as a dash', async () => {
    backend({
      'GET /v1/leave-encashment-arrears?': (url) =>
        paged(
          [
            {
              id: 3,
              recordNumber: 'LEA-2026-00003',
              eis: '104882',
              name: 'Rakesh Sharma',
              separationDate: null,
              order: null,
              revisionEffective: '2026-07-01',
              month: '202608',
              days: 10,
              oldBasic: null,
              oldDa: 0,
              revisedBasic: 62400,
              revisedDa: 0,
              paid: 20000,
              due: 20800,
              arrear: 800,
              tds: 0,
              status: 'CALCULATED',
              ineligibleReason: null,
              batch: null,
            },
          ],
          1,
          url,
        ),
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW']));
    const list = await screen.findByRole('table', { name: t('screen.I-24') });
    const row = (await within(list).findByText('LEA-2026-00003')).closest('tr') as HTMLElement;
    const numbers = row.querySelectorAll('td.num');
    // Days, then the old pay (unknown), then the revised pay.
    expect(numbers[1]?.textContent).toBe('—');
    expect(numbers[2]?.textContent).not.toBe('—');
  });
});
