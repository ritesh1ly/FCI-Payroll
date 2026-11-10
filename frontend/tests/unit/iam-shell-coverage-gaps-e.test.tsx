import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { UsersAdmin } from '@/modules/iam';
import { SCREENS } from '@/modules/shell/components/screenRegistry';
import { backend, me, paged, referenceLists, sent, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/payroll',
  useSearchParams: () => new URLSearchParams(),
}));

/**
 * Coverage of A-01 and the I-04 composite's remaining paths (OD-130; SRS I-04 "One screen serves both"): a reinstatement
 * dismissed sends nothing, and the composite's closed section draws the CPF register.
 */
afterEach(() => vi.unstubAllGlobals());

describe('A-01 remaining paths', () => {
  it('OD_130_aReinstatementDismissedSendsNothing', async () => {
    const calls = backend({
      'GET /v1/reference/lists': () => referenceLists({ USER_STATUS: [], USER_CATEGORY: [] }),
      'GET /v1/admin/users': () => ({
        items: [
          {
            id: 12,
            loginId: 'suspended@fci-demo.test',
            displayName: 'Amit Saxena',
            category: 'OPERATIONS',
            status: 'SUSPENDED',
            employeeEis: null,
            locked: false,
            mustChangePassword: false,
            lastLoginAt: null,
          },
        ],
        total: 1,
      }),
    });
    wrap(<UsersAdmin />, ['A-01:VIEW', 'A-01:EDIT']);
    const row = (await screen.findByText('suspended@fci-demo.test')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: t('a01.reinstate') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sent(calls, 'POST', '/users/12/status')).toHaveLength(0);
  });
});

describe('I-04 composite', () => {
  it('SRS_6_4_theClosedSectionDrawsTheCpfRegisterBesideTheLoans', async () => {
    backend({
      'GET /v1/loans/summary': () => ({
        yymm: '202610',
        activeLoans: 0,
        outstanding: 0,
        recoveryPrincipal: 0,
        recoveryInterest: 0,
        importFailures: 0,
        failingInterface: null,
      }),
      'GET /v1/loans?': (url) => paged([], 0, url),
      'GET /v1/loans/disbursements': (url) => paged([], 0, url),
      'GET /v1/loans/types': () => [],
      'GET /v1/loans/requests': (url) => paged([], 0, url),
      'GET /v1/cpf/sanctions': (url) => paged([], 0, url),
      'GET /v1/cpf/sanctions/pending-summary': () => ({
        pending: 0,
        advance: 0,
        partFinal: 0,
        finalPayment: 0,
      }),
      'GET /v1/document-types': () => [],
    });
    const Screen = SCREENS['I-04']?.component;
    if (!Screen) throw new Error('I-04 has no component');
    wrap(<Screen />, me(['I-04:VIEW', 'I-03:VIEW']));
    await screen.findByText(t('i04l.none'));
    await userEvent.click(screen.getByText(t('i04w.register')));
    await waitFor(() => expect(screen.getByTestId('i04-register')).toHaveAttribute('open'));
    // The CPF register of the closed section: its stage filter and hint, empty here.
    const section = screen.getByTestId('i04-register');
    expect(await within(section).findByText(t('i04.hint'))).toBeInTheDocument();
    expect(await within(section).findByText(t('i04.none'))).toBeInTheDocument();
    // Beside it the loans register of the closed section, with no loan either.
    expect((await within(section).findAllByText(t('i04l.none'))).length).toBeGreaterThan(0);
  });
});
