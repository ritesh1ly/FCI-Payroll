import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ExceptionsWorklist } from '@/modules/general';
import { backend, paged, referenceLists, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * X-05 Exceptions Worklist (SRS-G-9, SRS-15-14): a row is selected by clicking it and Assign / Accept with reason act
 * on the selected condition; with none selected they say so and open nothing; a second click clears the selection; the
 * Checker's ✔ on a row opens its items for a decision.
 */

afterEach(() => vi.unstubAllGlobals());

const ITEM = {
  id: 21,
  cycleInstanceId: null,
  eis: '105220',
  memberName: 'Sourav Banerjee',
  moduleCode: 'C-02',
  exceptionCode: 'MISSING_BANK',
  severity: 'WARNING',
  blocksGate: 'G2',
  entityName: 'employee',
  fieldName: 'bank_account',
  fieldSource: 'ENT',
  resolvableInPayroll: true,
  correctionScreen: 'M-01',
  ownerRoleCode: null,
  detail: 'Bank account missing',
  status: 'OPEN',
  acceptedBy: null,
  acceptedReason: null,
  raisedAt: '2026-10-20T05:00:00Z',
  ageDays: 1,
  resolvedAt: null,
};

const GROUP = {
  moduleCode: 'C-02',
  exceptionCode: 'MISSING_BANK',
  items: 1,
  members: 1,
  blocksG2: false,
  owner: null,
  resolvableInPayroll: true,
  actionable: true,
  correctionScreen: 'M-01',
  detail: 'Bank account missing',
  firstRaisedAt: '2026-10-20T05:00:00Z',
};

function routes() {
  const tiles = { open: 1, blockingG2: 0, acceptedWithReason: 0, upstream: 0 };
  return backend({
    'GET /v1/reference/lists': () =>
      referenceLists({ EXCEPTION_STATUS: [['OPEN', 'Open']], EXCEPTION_SEVERITY: [['WARNING', 'Warning']] }),
    'GET /v1/exceptions/groups': () => ({
      tiles,
      items: [GROUP],
      page: 0,
      size: 20,
      total: 1,
      modules: ['C-02'],
    }),
    'GET /v1/exceptions/offices': () => [],
    'GET /v1/exceptions/summary': () => ({ tiles }),
    'GET /v1/exceptions': (url) => ({ ...paged([ITEM], 1, url), tiles }),
  });
}

const ROLES = ['X-05:VIEW', 'X-05:EDIT', 'X-05:APPROVE'];

describe('X-05 the selected condition', () => {
  it('SRS-G-9: Accept with reason and Assign with no row selected say so and open nothing', async () => {
    const calls = routes();
    wrap(<ExceptionsWorklist />, ROLES);
    await screen.findByText('MISSING_BANK');
    await userEvent.click(screen.getByRole('button', { name: t('x05.acceptWithReason') }));
    expect(await screen.findByText(t('x05.selectCondition'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('x05.assign') }));
    await waitFor(() => expect(screen.getAllByText(t('x05.selectCondition')).length).toBeGreaterThan(0));
    expect(document.querySelector('tr[data-item="21"]')).toBeNull();
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);
  });

  it('SRS-G-9: a clicked row is selected; Accept with reason opens its items; a second click clears it', async () => {
    routes();
    wrap(<ExceptionsWorklist />, ROLES);
    const row = (await screen.findByText('MISSING_BANK')).closest('tr') as HTMLElement;
    expect(row.getAttribute('aria-selected')).toBe('false');
    await userEvent.click(row);
    expect(row.getAttribute('aria-selected')).toBe('true');
    expect(row.className).toBe('fl-row-selected');
    await userEvent.click(screen.getByRole('button', { name: t('x05.acceptWithReason') }));
    expect(await waitFor(() => document.querySelector('tr[data-item="21"]'))).not.toBeNull();
    await userEvent.click(row);
    expect(row.getAttribute('aria-selected')).toBe('false');
    expect(row.className).toBe('');
  });

  it('SRS-15-14: Assign opens the selected condition with its owner choice', async () => {
    routes();
    wrap(<ExceptionsWorklist />, ['X-05:VIEW', 'X-05:EDIT']);
    const row = (await screen.findByText('MISSING_BANK')).closest('tr') as HTMLElement;
    await userEvent.click(row);
    await userEvent.click(screen.getByRole('button', { name: t('x05.assign') }));
    const item = await waitFor(() => {
      const found = document.querySelector('tr[data-item="21"]');
      expect(found).not.toBeNull();
      return found as HTMLElement;
    });
    expect(within(item).getByLabelText(t('x05.assignTo', ['MISSING_BANK']))).toBeTruthy();
  });

  it('SRS-G-9: the Checker opens a condition from its ✔', async () => {
    routes();
    wrap(<ExceptionsWorklist />, ROLES);
    const row = (await screen.findByText('MISSING_BANK')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '✔' }));
    expect(await waitFor(() => document.querySelector('tr[data-item="21"]'))).not.toBeNull();
    // The ✔ sits in the actions cell, which does not select the row.
    expect(row.getAttribute('aria-selected')).toBe('false');
  });
});
