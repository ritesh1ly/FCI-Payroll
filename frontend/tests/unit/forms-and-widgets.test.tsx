import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChangePassword } from '@/modules/iam/components/ChangePassword';
import { ResetPassword } from '@/modules/iam/components/ResetPassword';
import { Dropdown, formText } from '@/shared/ui';
import { backend, problem, sent, t, wrap } from './support/harness';

/**
 * Shared form reading, the live dropdown's remaining states, and the forgot-password dialog
 * (SRS-G-48: the first step never reveals whether the login ID exists; a refusal shows its reason).
 */

afterEach(() => vi.unstubAllGlobals());

describe('formText', () => {
  it('reads a text field, falls back for a missing one and never reads a file as text', () => {
    const form = new FormData();
    form.set('reason', 'Arrears');
    form.set('empty', '');
    form.set('file', new File(['x'], 'order.pdf'));
    expect(formText(form, 'reason')).toBe('Arrears');
    expect(formText(form, 'empty', 'unused')).toBe('');
    expect(formText(form, 'missing')).toBe('');
    expect(formText(form, 'missing', 'FULL')).toBe('FULL');
    expect(formText(form, 'file')).toBe('');
  });
});

describe('Dropdown', () => {
  const options = [
    { value: 'a', label: 'Alpha' },
    { value: 'b', label: 'Beta', subLabel: 'second' },
  ];

  it('a disabled dropdown never opens; without a match it shows the placeholder or nothing', async () => {
    const { rerender } = render(
      <Dropdown label="Pick" value="z" onChange={vi.fn()} options={options} placeholder="Choose" disabled />,
    );
    const button = screen.getByRole('button', { name: 'Pick' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.textContent).toContain('Choose');
    fireEvent.click(button);
    expect(screen.queryByRole('listbox')).toBeNull();
    rerender(<Dropdown label="Pick" value="z" onChange={vi.fn()} options={options} variant="form" />);
    expect(screen.getByRole('button', { name: 'Pick' }).textContent).toBe('');
  });

  it('closes on a click outside, stays open for a click inside, aligns right and shows an icon', async () => {
    render(
      <div>
        <span>outside</span>
        <Dropdown
          label="Pick"
          value="b"
          onChange={vi.fn()}
          options={options}
          align="right"
          variant="form"
          icon={<i data-testid="icon" />}
        />
      </div>,
    );
    expect(screen.getByTestId('icon')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Pick' }));
    const list = screen.getByRole('listbox');
    expect(list.className).toContain('right-0');
    expect(screen.getByRole('option', { name: /Beta/ }).getAttribute('aria-selected')).toBe('true');
    fireEvent.mouseDown(list);
    expect(screen.getByRole('listbox')).toBeTruthy();
    fireEvent.mouseDown(screen.getByText('outside'));
    expect(screen.queryByRole('listbox')).toBeNull();
    // Toggling closes it again.
    await userEvent.click(screen.getByRole('button', { name: 'Pick' }));
    await userEvent.click(screen.getByRole('button', { name: 'Pick' }));
    expect(screen.queryByRole('listbox')).toBeNull();
  });
});

describe('SRS-G-54 accessibility of the shared widgets (US-GAP-111)', () => {
  const options = [
    { value: 'a', label: 'Alpha' },
    { value: 'b', label: 'Beta' },
    { value: 'c', label: 'Gamma' },
  ];

  it('the dropdown works from the keyboard: arrows, Home, End, Enter, Escape back to the button, Tab', async () => {
    const onChange = vi.fn();
    render(<Dropdown label="Pick" value="b" onChange={onChange} options={options} />);
    const button = screen.getByRole('button', { name: 'Pick' });
    button.focus();
    await userEvent.keyboard('{ArrowDown}');
    // Opened on the selected option, marked by aria-selected (not by colour alone).
    const beta = screen.getByRole('option', { name: /Beta/ });
    expect(document.activeElement).toBe(beta);
    expect(beta.getAttribute('aria-selected')).toBe('true');
    await userEvent.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(screen.getByRole('option', { name: /Gamma/ }));
    await userEvent.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(screen.getByRole('option', { name: /Gamma/ }));
    await userEvent.keyboard('{Home}');
    expect(document.activeElement).toBe(screen.getByRole('option', { name: /Alpha/ }));
    await userEvent.keyboard('{ArrowUp}');
    expect(document.activeElement).toBe(screen.getByRole('option', { name: /Alpha/ }));
    await userEvent.keyboard('{End}{ArrowUp}');
    expect(document.activeElement).toBe(screen.getByRole('option', { name: /Beta/ }));
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(document.activeElement).toBe(button);
    expect(onChange).not.toHaveBeenCalled();

    await userEvent.keyboard('{ArrowUp}');
    expect(screen.getByRole('listbox')).toBeTruthy();
    await userEvent.keyboard('{Home}{Enter}');
    expect(onChange).toHaveBeenCalledWith('a');
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(document.activeElement).toBe(button);

    await userEvent.keyboard('{ArrowDown}');
    await userEvent.keyboard('{Tab}');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('a closed, an open or a disabled dropdown ignores other keys; the list has its label', async () => {
    const { rerender } = render(<Dropdown label="Pick" value="z" onChange={vi.fn()} options={options} />);
    const button = screen.getByRole('button', { name: 'Pick' });
    button.focus();
    await userEvent.keyboard('x');
    expect(screen.queryByRole('listbox')).toBeNull();
    await userEvent.keyboard('{ArrowDown}');
    // Nothing selected: the focus starts on the first option.
    expect(document.activeElement).toBe(screen.getByRole('option', { name: /Alpha/ }));
    expect(screen.getByRole('listbox', { name: 'Pick' })).toBeTruthy();
    await userEvent.keyboard('x');
    expect(screen.getByRole('listbox')).toBeTruthy();
    fireEvent.keyDown(button, { key: 'ArrowDown' });
    expect(screen.getByRole('listbox')).toBeTruthy();
    await userEvent.keyboard('{Escape}');
    rerender(<Dropdown label="Pick" value="z" onChange={vi.fn()} options={options} disabled />);
    fireEvent.keyDown(screen.getByRole('button', { name: 'Pick' }), { key: 'ArrowDown' });
    expect(screen.queryByRole('listbox')).toBeNull();
  });
});

describe('Change password (X-08)', () => {
  it('SRS-G-21 (US-GAP-128): the own password change asks for the OTP, then sends it with the passwords', async () => {
    const onDone = vi.fn();
    const calls = backend({
      'POST /auth/password/change': (_url, init) => {
        const body = JSON.parse(String(init?.body)) as { otp?: { challengeId: number; code: string } };
        return body.otp ? { status: 204, body: null } : problem(428, 'AUTH-428-OTP', ['31']);
      },
    });
    wrap(<ChangePassword forced onDone={onDone} onCancel={vi.fn()} />);
    await userEvent.type(screen.getByLabelText(t('password.current')), 'Old#Passw0rd12');
    await userEvent.type(screen.getByLabelText(t('password.new')), 'N3w#Passw0rd12');
    await userEvent.type(screen.getByLabelText(t('password.confirm')), 'N3w#Passw0rd12');
    await userEvent.click(screen.getByRole('button', { name: t('password.submit') }));
    await userEvent.type(await screen.findByLabelText('One-time password'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Verify and continue' }));
    await vi.waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(sent(calls, 'POST', '/auth/password/change')).toEqual([
      { currentPassword: 'Old#Passw0rd12', newPassword: 'N3w#Passw0rd12' },
      {
        currentPassword: 'Old#Passw0rd12',
        newPassword: 'N3w#Passw0rd12',
        otp: { challengeId: 31, code: '123456' },
      },
    ]);
  });
});

describe('Forgot password', () => {
  async function requestOtp(loginId = 'maker.delhi@fci-demo.test') {
    const submit = screen.getByRole('button', { name: new RegExp(t('reset.sendOtp')) }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    await userEvent.type(screen.getByRole('textbox'), loginId);
    await userEvent.click(submit);
  }

  it('SRS-G-48: OTP, then OTP + new password; a mismatch is refused before anything is sent', async () => {
    const onDone = vi.fn();
    const calls = backend({
      'POST /auth/password/forgot': () => ({ status: 204, body: null }),
      'POST /auth/password/reset': () => ({ status: 204, body: null }),
    });
    wrap(<ResetPassword onDone={onDone} />);
    await requestOtp();
    expect(await screen.findByText(t('reset.otpSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/auth/password/forgot')).toEqual([{ loginId: 'maker.delhi@fci-demo.test' }]);

    const [code, password, confirm] = [
      screen.getByRole('textbox'),
      ...Array.from(document.querySelectorAll<HTMLInputElement>('input[type=password]')),
    ];
    await userEvent.type(code as HTMLElement, '123456');
    await userEvent.type(password as HTMLElement, 'Example#Pass1');
    await userEvent.type(confirm as HTMLElement, 'Example#Pass2');
    await userEvent.click(screen.getByRole('button', { name: new RegExp(t('reset.submit')) }));
    expect(screen.getByRole('alert').textContent).toBe(t('reset.mismatch'));
    expect(sent(calls, 'POST', '/auth/password/reset')).toEqual([]);

    await userEvent.clear(confirm as HTMLElement);
    await userEvent.type(confirm as HTMLElement, 'Example#Pass1');
    await userEvent.click(screen.getByRole('button', { name: new RegExp(t('reset.submit')) }));
    expect(await screen.findByText(t('reset.done'))).toBeTruthy();
    expect(sent(calls, 'POST', '/auth/password/reset')).toEqual([
      { loginId: 'maker.delhi@fci-demo.test', code: '123456', newPassword: 'Example#Pass1' },
    ]);
    await userEvent.click(screen.getByRole('button', { name: new RegExp(t('login.gotIt')) }));
    expect(onDone).toHaveBeenCalledOnce();
  });

  it('a refused OTP shows the server reason and keeps the step', async () => {
    backend({
      'POST /auth/password/forgot': () => ({ status: 204, body: null }),
      'POST /auth/password/reset': () => problem(400, 'AUTH-400-OTP'),
    });
    wrap(<ResetPassword onDone={vi.fn()} />);
    await requestOtp();
    await screen.findByText(t('reset.otpSent'));
    await userEvent.click(screen.getByRole('button', { name: new RegExp(t('reset.submit')) }));
    expect((await screen.findByRole('alert')).textContent).toBe(t('error.AUTH-400-OTP'));
    expect(screen.getByText(t('reset.otpSent'))).toBeTruthy();
  });

  it('a network failure on the first step is shown as such, and the form can be sent again', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url.endsWith('/auth/csrf')) {
          document.cookie = 'XSRF-TOKEN=t; Path=/';
          return new Response(null, { status: 204 });
        }
        throw new TypeError('offline');
      }),
    );
    wrap(<ResetPassword onDone={vi.fn()} />);
    await requestOtp();
    expect((await screen.findByRole('alert')).textContent).toBe(t('error.network'));
    const submit = screen.getByRole('button', { name: new RegExp(t('reset.sendOtp')) }) as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
  });
});
