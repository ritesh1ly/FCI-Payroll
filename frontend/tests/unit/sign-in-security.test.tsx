import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { SignInSecurity } from '@/modules/iam';
import { backend, me, problem, sent, t, wrap } from './support/harness';

/**
 * X-08 Sign-in, OTP & Password as Wireframes v6.1 draws it (OD-121; SRS-G-21, SRS-G-35, FRS V0.5 §6.7.7.4,
 * §6.4.2.5): the two sign-in steps say where they act; the own password change with the OTP typed on the card (428
 * with the challenge first, then the code); the session controls; the eighteen OTP actions with their screens and
 * who enters the OTP, as drawn (login always asks, OD-126); the actions without OTP.
 */

const ACTIONS = [
  { code: 'AUTH_LOGIN', screens: ['X-08'], routedScreens: [], roles: [] },
  { code: 'AUTH_OWN_PASSWORD_CHANGE', screens: ['X-08', 'E-01'], routedScreens: [], roles: [] },
  { code: 'CYCLE_FREEZE_LOCK', screens: ['C-01', 'D-01'], routedScreens: ['C-02'], roles: ['PAYROLL_MAKER'] },
  { code: 'PLI_ADJUSTMENT', screens: ['C-02'], routedScreens: [], roles: [] },
];

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({ 'GET /v1/otp/gated-actions': () => ACTIONS, ...over });
}

describe('X-08 Sign-in, OTP & Password as drawn', () => {
  it('SRS-G-21: the OTP list is drawn row by row - the action, the screens where its OTP is entered (linked) and who enters it, whatever the routes say', async () => {
    routes();
    wrap(<SignInSecurity />, me(['X-08:VIEW']));
    const title = await screen.findByText(t('x08.otpListTitle'));
    const table = (title.closest('.card') as HTMLElement).querySelector('table') as HTMLElement;
    await within(table).findByText(t('x08.act.CYCLE_FREEZE_LOCK'));
    const rowOf = (code: string) => table.querySelector(`tr[data-code="${code}"]`) as HTMLElement;
    expect(rowOf('AUTH_LOGIN').textContent).toContain(t('x08.who.AUTH_LOGIN'));
    expect(within(rowOf('AUTH_LOGIN')).getByRole('link', { name: 'X-08' })).toBeTruthy();
    const own = within(rowOf('AUTH_OWN_PASSWORD_CHANGE')).getAllByRole('link');
    expect(own.map((a) => a.textContent)).toEqual(['X-08', 'E-01']);
    expect(rowOf('AUTH_OWN_PASSWORD_CHANGE').textContent).toContain('X-08, E-01');
    // The drawn screens and who, not the configured route (C-02) or its roles.
    const freeze = within(rowOf('CYCLE_FREEZE_LOCK'));
    expect(freeze.getByRole('link', { name: 'C-01' }).getAttribute('href')).toContain('s=C-01');
    expect(freeze.getByRole('link', { name: 'D-01' })).toBeTruthy();
    expect(freeze.queryByRole('link', { name: 'C-02' })).toBeNull();
    expect(rowOf('CYCLE_FREEZE_LOCK').textContent).toContain(t('x08.who.CYCLE_FREEZE_LOCK'));
    expect(rowOf('CYCLE_FREEZE_LOCK').textContent).not.toContain(t('role.PAYROLL_MAKER'));
    // An action no route asks for yet is still listed as drawn.
    expect(within(rowOf('PLI_ADJUSTMENT')).getByRole('link', { name: 'C-02' })).toBeTruthy();
    expect(rowOf('PLI_ADJUSTMENT').textContent).toContain(t('x08.act.PLI_ADJUSTMENT'));
    // Numbered in order.
    expect(within(rowOf('CYCLE_FREEZE_LOCK')).getAllByRole('cell')[0]?.textContent).toBe('3');
    for (const k of [
      'x08.step1',
      'x08.step2',
      'x08.changeTitle',
      'x08.controls',
      'x08.noOtp',
      'x08.no.resume',
      'x08.no.list',
    ])
      expect(screen.getByText(t(k))).toBeTruthy();
  });

  it('SRS-G-35: inside the workspace the sign-in buttons say where they act and send nothing', async () => {
    const calls = routes();
    wrap(<SignInSecurity />, me(['X-08:VIEW']));
    await screen.findByText(t('x08.step1'));
    expect((screen.getByLabelText(t('x08.userId')) as HTMLInputElement).value).toBe('user@fci-demo.test');
    for (const label of ['x08.continue', 'x08.verify', 'x08.resend']) {
      await userEvent.click(screen.getByRole('button', { name: t(label) }));
    }
    expect(
      (await screen.findAllByText(t('x08.why.signedIn', ['user@fci-demo.test']))).length,
    ).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole('button', { name: t('x08.forgot') }));
    expect(await screen.findByText(t('x08.why.forgot'))).toBeTruthy();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('SRS-G-21: the first Change sends the OTP (428), the second carries the challenge and the code typed on the card', async () => {
    let answered = false;
    const calls = routes({
      'POST /auth/password/change': () => {
        if (!answered) {
          answered = true;
          return problem(428, 'AUTH-428-OTP', ['77']);
        }
        return { status: 204, body: null };
      },
    });
    wrap(<SignInSecurity />, me(['X-08:VIEW']));
    await screen.findByText(t('x08.changeTitle'));
    const change = screen.getByRole('button', { name: new RegExp(`^${t('x08.changeAct')}`) });
    // Open as drawn: pressed empty it says what is missing and sends nothing.
    await userEvent.click(change);
    expect(await screen.findByText(t('x08.fillAll'))).toBeTruthy();
    expect(sent(calls, 'POST', '/auth/password/change')).toHaveLength(0);
    await userEvent.type(screen.getByLabelText(t('password.current')), 'Old#Password1');
    await userEvent.type(screen.getByLabelText(t('password.new')), 'New#Password12');
    await userEvent.type(screen.getByLabelText(t('password.confirm')), 'New#Password12');
    await userEvent.click(change);
    expect(await screen.findByText(t('x08.otpSent'))).toBeTruthy();
    expect(screen.getByText(t('x08.enterOtp'))).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('x08.otp')), ' 123456 ');
    await userEvent.click(change);
    expect(await screen.findByText(t('password.changed'))).toBeTruthy();
    expect(sent(calls, 'POST', '/auth/password/change')).toEqual([
      { currentPassword: 'Old#Password1', newPassword: 'New#Password12' },
      {
        currentPassword: 'Old#Password1',
        newPassword: 'New#Password12',
        otp: { challengeId: 77, code: '123456' },
      },
    ]);
    // The card is cleared after the change.
    await waitFor(() =>
      expect((screen.getByLabelText(t('password.current')) as HTMLInputElement).value).toBe(''),
    );
  });

  it('SRS-G-21: a confirmation that differs sends nothing; a refusal (wrong current password) is shown', async () => {
    const calls = routes({ 'POST /auth/password/change': () => problem(422, 'AUTH-422-PASSWORD') });
    wrap(<SignInSecurity />, me(['X-08:VIEW']));
    await screen.findByText(t('x08.changeTitle'));
    await userEvent.type(screen.getByLabelText(t('password.current')), 'Old#Password1');
    await userEvent.type(screen.getByLabelText(t('password.new')), 'New#Password12');
    await userEvent.type(screen.getByLabelText(t('password.confirm')), 'Other#Password12');
    const change = screen.getByRole('button', { name: new RegExp(`^${t('x08.changeAct')}`) });
    await userEvent.click(change);
    expect(await screen.findByText(t('reset.mismatch'))).toBeTruthy();
    expect(sent(calls, 'POST', '/auth/password/change')).toHaveLength(0);
    await userEvent.clear(screen.getByLabelText(t('password.confirm')));
    await userEvent.type(screen.getByLabelText(t('password.confirm')), 'New#Password12');
    await userEvent.click(change);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(sent(calls, 'POST', '/auth/password/change')).toHaveLength(1);
  });

  it('a failed OTP list read is shown and the table stays empty', async () => {
    routes({ 'GET /v1/otp/gated-actions': () => problem(503, 'COM-503-UNAVAILABLE') });
    wrap(<SignInSecurity />, me(['X-08:VIEW']));
    expect(await screen.findByRole('alert')).toBeTruthy();
    const title = screen.getByText(t('x08.otpListTitle'));
    expect((title.closest('.card') as HTMLElement).querySelectorAll('tbody tr')).toHaveLength(0);
  });
});
