import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MyVpf, VpfRequests } from '@/modules/cpf';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/** E-01 VPF subscription and E-02 VPF change requests (plan 14 phase 14c; BR-ESEVA-006). */

afterEach(() => vi.unstubAllGlobals());

const REQUEST = {
  id: 4,
  eis: '104882',
  member: 'Rakesh Sharma',
  office: 'DH001 - Delhi',
  currentAmount: 2500,
  requestedAmount: 3250,
  effectiveFromYymm: '202611',
  status: 'SUBMITTED',
  awaitingChecker: false,
  decisionReason: null,
  submittedAt: '2026-10-28T10:00:00',
};

describe('E-01 VPF', () => {
  it('BR-ESEVA-006: the member asks for a new amount, then waits for the verification', async () => {
    let requests: unknown[] = [];
    const calls = backend({
      'GET /v1/cpf/vpf-changes/mine': () => ({
        finYear: '2026-27',
        currentAmount: 2500,
        cpfMember: true,
        requests,
      }),
      'POST /v1/cpf/vpf-changes/mine': () => {
        requests = [REQUEST];
        return { status: 201, body: REQUEST };
      },
    });
    wrap(<MyVpf />, me(['E-01:VIEW', 'E-01:CREATE']));
    const ask = await screen.findByRole('button', { name: t('action.vpf.request') });
    expect(ask).toBeDisabled();
    expect(ask.getAttribute('title')).toBe(t('vpf.why.amount'));
    await userEvent.type(screen.getByLabelText(t('vpf.newAmount')), '2500');
    expect(ask.getAttribute('title')).toBe(t('vpf.why.unchanged'));
    await userEvent.clear(screen.getByLabelText(t('vpf.newAmount')));
    await userEvent.type(screen.getByLabelText(t('vpf.newAmount')), '3250');
    await userEvent.click(ask);
    expect(await screen.findByText(t('vpf.asked'))).toBeTruthy();
    expect(sent(calls, 'POST', '/vpf-changes/mine')).toEqual([{ amount: 3250 }]);
    const table = await screen.findByRole('table', { name: t('vpf.requests') });
    expect(within(table).getByText(t('vpf.status.SUBMITTED'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('action.vpf.request') }).getAttribute('title')).toBe(
      t('vpf.why.pending'),
    );
  });

  it('BR-ESEVA-006: a request verified by the CPF desk shows it is with the Checker', async () => {
    backend({
      'GET /v1/cpf/vpf-changes/mine': () => ({
        finYear: '2026-27',
        currentAmount: 2500,
        cpfMember: true,
        requests: [{ ...REQUEST, awaitingChecker: true }],
      }),
    });
    wrap(<MyVpf />, me(['E-01:VIEW', 'E-01:CREATE']));
    expect(await screen.findByText(t('vpf.status.WITH_CHECKER'))).toBeTruthy();
  });

  it('BR-ESEVA-006: a refusal is shown; a member without the right asks nothing; staff see nothing', async () => {
    backend({
      'GET /v1/cpf/vpf-changes/mine': () => ({
        finYear: '2026-27',
        currentAmount: 0,
        cpfMember: true,
        requests: [{ ...REQUEST, status: 'RETURNED', decisionReason: 'Out of limit' }],
      }),
      'POST /v1/cpf/vpf-changes/mine': () => problem(422, 'CPF-422-VPF-CHANGE', ['AMOUNT']),
    });
    wrap(<MyVpf />, me(['E-01:VIEW', 'E-01:CREATE']));
    expect(await screen.findByText('Out of limit')).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('vpf.newAmount')), '100');
    await userEvent.click(screen.getByRole('button', { name: t('action.vpf.request') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    vi.unstubAllGlobals();
    backend({
      'GET /v1/cpf/vpf-changes/mine': () => ({
        finYear: '2026-27',
        currentAmount: 0,
        cpfMember: true,
        requests: [],
      }),
    });
    wrap(<MyVpf />, me(['E-01:VIEW']));
    await vi.waitFor(() =>
      expect(
        screen
          .getAllByRole('button', { name: t('action.vpf.request') })
          .at(-1)
          ?.getAttribute('title'),
      ).toBe(t('vpf.why.memberOnly')),
    );
    const staff = wrap(<MyVpf />, me(['E-02:VIEW']));
    expect(staff.container.textContent).toBe('');
  });
});

describe('E-02 VPF change requests', () => {
  it('BR-ESEVA-006: the Maker sends a request to the Checker or returns it with a reason', async () => {
    const calls = backend({
      'GET /v1/cpf/vpf-changes?': (url) =>
        paged([REQUEST, { ...REQUEST, id: 5, awaitingChecker: true }], 2, url),
      'POST /v1/cpf/vpf-changes/4/verifications': () => ({ ...REQUEST, awaitingChecker: true }),
      'POST /v1/cpf/vpf-changes/4/returns': () => ({ ...REQUEST, status: 'RETURNED' }),
    });
    wrap(<VpfRequests />, me(['E-02:VIEW', 'E-02:SUBMIT']));
    const table = await screen.findByRole('table', { name: t('vpf.staffTitle') });
    const verify = within(table).getAllByRole('button', { name: t('action.e02.verifyVpf') });
    expect(verify[1]).toBeDisabled();
    expect(verify[1]?.getAttribute('title')).toBe(t('vpf.why.notWithMaker'));
    expect(within(table).getByText(t('vpf.status.WITH_CHECKER'))).toBeTruthy();
    await userEvent.click(verify[0] as HTMLElement);
    expect(await screen.findByText(t('vpf.sentToChecker'))).toBeTruthy();
    await userEvent.click(
      within(table).getAllByRole('button', { name: t('action.vpf.return') })[0] as HTMLElement,
    );
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Above the limit');
    await userEvent.click(within(dialog).getByRole('button', { name: t('action.vpf.return') }));
    expect(await screen.findByText(t('vpf.returned'))).toBeTruthy();
    expect(sent(calls, 'POST', '/returns')).toEqual([{ reason: 'Above the limit' }]);
    await userEvent.selectOptions(screen.getByLabelText(t('vpf.status')), '');
    await screen.findByRole('table', { name: t('vpf.staffTitle') });
    expect(calls.some((c) => c.url.includes('/v1/cpf/vpf-changes?page'))).toBe(true);
  });

  it('BR-ESEVA-006: a viewer acts on nothing', async () => {
    backend({ 'GET /v1/cpf/vpf-changes?': (url) => paged([REQUEST], 1, url) });
    wrap(<VpfRequests />, me(['E-02:VIEW']));
    const table = await screen.findByRole('table', { name: t('vpf.staffTitle') });
    const verify = within(table).getByRole('button', { name: t('action.e02.verifyVpf') });
    expect(verify).toBeDisabled();
    expect(verify.getAttribute('title')).toBe(t('reason.makerOnly'));
  });

  it('BR-ESEVA-006: a refused verification is shown', async () => {
    backend({
      'GET /v1/cpf/vpf-changes?': (url) => paged([REQUEST], 1, url),
      'POST /v1/cpf/vpf-changes/4/verifications': () =>
        problem(409, 'CPF-409-VPF-STATE', ['APPLIED', 'SUBMITTED']),
      'POST /v1/cpf/vpf-changes/4/returns': () => problem(409, 'CPF-409-VPF-STATE', ['APPLIED', 'SUBMITTED']),
    });
    wrap(<VpfRequests />, me(['E-02:VIEW', 'E-02:SUBMIT']));
    const table = await screen.findByRole('table', { name: t('vpf.staffTitle') });
    await userEvent.click(within(table).getByRole('button', { name: t('action.e02.verifyVpf') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    await userEvent.click(within(table).getByRole('button', { name: t('action.vpf.return') }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    // A return refused by the server is shown too.
    await userEvent.click(within(table).getByRole('button', { name: t('action.vpf.return') }));
    const again = await screen.findByRole('dialog');
    await userEvent.type(within(again).getByRole('textbox'), 'Above the limit');
    await userEvent.click(within(again).getByRole('button', { name: t('action.vpf.return') }));
    expect(await screen.findByText(t('error.CPF-409-VPF-STATE', ['APPLIED', 'SUBMITTED']))).toBeTruthy();
  });

  it('BR-ESEVA-006: a failed load of the member VPF shows the problem in the card instead of hiding it', async () => {
    backend({ 'GET /v1/cpf/vpf-changes/mine': () => problem(503, 'COM-503-UNAVAILABLE') });
    wrap(<MyVpf />, me(['E-01:VIEW', 'E-01:CREATE']));
    expect(await screen.findByText(t('vpf.title'))).toBeTruthy();
    expect(await screen.findByRole('alert')).toBeTruthy();
    // Nothing can be asked while the subscription is unknown.
    expect(screen.queryByRole('button', { name: t('action.vpf.request') })).toBeNull();
  });
});
