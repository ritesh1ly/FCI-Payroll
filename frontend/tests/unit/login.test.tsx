import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginPage, useSignOut } from '@/modules/iam';
import { I18nProvider } from '@/shared/i18n';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: push }) }));

/** /auth/login-options: the demo flag and whether the OTP follows the password. */
const OPTIONS = { demo: false, otpAtLogin: true, mobileOtp: true };
const SENT = { otpRequired: true, channel: 'SMS', destination: '******3210', validSeconds: 300 };
const SIGNED = { route: '/payroll' };
const json = (body: object, status = 200) => new Response(JSON.stringify(body), { status });
const problem = (code: string, status: number) =>
  json({ errorCode: code, messageKey: `error.${code}` }, status);

type Answers = Partial<Record<'options' | 'login' | 'mobile' | 'otp' | 'resend' | 'logout', () => Response>>;
let calls: { url: string; init?: RequestInit }[] = [];
function stub(answers: Answers = {}) {
  calls = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      if (url.endsWith('/auth/login-options')) return answers.options?.() ?? json(OPTIONS);
      if (url.endsWith('/auth/login')) return answers.login?.() ?? json(SIGNED);
      if (url.endsWith('/auth/login/otp')) return answers.otp?.() ?? json(SIGNED);
      if (url.endsWith('/auth/login/otp/resend')) return answers.resend?.() ?? json(SENT, 202);
      if (url.endsWith('/auth/login/mobile')) return answers.mobile?.() ?? json(SENT, 202);
      if (url.endsWith('/auth/logout')) return answers.logout?.() ?? new Response(null, { status: 204 });
      if (url.endsWith('/i18n')) return json({ locale: 'en' });
      return new Response(null, { status: 204 });
    }),
  );
}
const bodyOf = (suffix: string) =>
  JSON.parse(String(calls.filter((c) => c.url.endsWith(suffix)).at(-1)?.init?.body ?? 'null'));

function wrap(node: ReactNode) {
  return (
    <I18nProvider>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        {node}
      </QueryClientProvider>
    </I18nProvider>
  );
}

const emailField = () => screen.findByPlaceholderText('Enter your email address');
const passwordField = () => screen.getByPlaceholderText('Enter your password');
const signInButton = () => screen.getByRole('button', { name: /^Sign in$/ });

async function enter(id: string, password: string) {
  await userEvent.type(await emailField(), id);
  await userEvent.type(passwordField(), password);
  await userEvent.click(signInButton());
}

function clearRemembered() {
  document.cookie = 'FCI_REMEMBER=; Path=/; Max-Age=0';
}

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=t; Path=/';
  clearRemembered();
  push.mockReset();
  window.history.replaceState({}, '', '/');
});
afterEach(() => vi.unstubAllGlobals());

describe('the sign-in card (login wireframe)', () => {
  it('SRS-G-35: Email and Employee ID tabs, the password, no persona or location, no credentials shown', async () => {
    stub({ options: () => json({ demo: true, otpAtLogin: true }) });
    render(wrap(<LoginPage />));
    expect(await emailField()).toHaveAttribute('type', 'email');
    expect(screen.getByRole('tab', { name: 'Email' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Employee ID' })).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeTruthy();
    expect(screen.getByText('Payroll Management System')).toBeTruthy();
    expect(screen.queryByRole('group', { name: 'Sign in as' })).toBeNull();
    expect(screen.queryByLabelText('Login location')).toBeNull();
    expect(screen.queryByText(/Fci@Demo2026|Demo preview/)).toBeNull();
    expect(screen.getByRole('list', { name: 'Assurances' })).toHaveTextContent('SecureReliableCompliant');
    // No build-module numbers (OD-35); the story is the wireframe's own, with no logo image (OD-44).
    expect(document.body.textContent).not.toMatch(/module\s*\d/i);
    expect(screen.getByRole('list', { name: 'What FCI Lekha does' })).toHaveTextContent(
      'EmployeeManagementPayrollProcessingLeaveManagementReports &Analytics',
    );
    expect(document.querySelector('img[alt*="logo" i]')).toBeNull();
  });

  it('SRS-G-48: before hydration sign-in cannot submit natively, and never by GET (no password in a URL)', async () => {
    stub();
    const { renderToString } = await import('react-dom/server');
    const html = renderToString(wrap(<LoginPage />));
    const ssr = new DOMParser().parseFromString(html, 'text/html');
    expect(ssr.querySelector('#lx-form')?.getAttribute('method')).toBe('post');
    expect(ssr.querySelector('button[type=submit]')?.hasAttribute('disabled')).toBe(true);
    render(wrap(<LoginPage />));
    await waitFor(() => expect(signInButton()).not.toBeDisabled());
  });

  it('SRS-G-35: signs in with the e-mail and the password when no OTP is asked, and opens the workspace', async () => {
    stub({ options: () => json({ demo: false, otpAtLogin: false }) });
    render(wrap(<LoginPage />));
    await enter('  finance.mumbai@fci-demo.test ', 'Fci@Demo2026');
    await waitFor(() => expect(push).toHaveBeenCalledWith('/payroll'));
    expect(bodyOf('/auth/login')).toEqual({
      loginId: 'finance.mumbai@fci-demo.test',
      password: 'Fci@Demo2026',
      idType: 'USER_ID',
    });
    // The wireframe still offers mobile OTP as a separate sign-in method.
    expect(screen.getByRole('button', { name: /Sign in with OTP/ })).toBeVisible();
  });

  it('SRS-G-35: inline validation names what is missing or wrong and sends nothing until it is right', async () => {
    stub();
    render(wrap(<LoginPage />));
    await emailField();
    await waitFor(() => expect(signInButton()).not.toBeDisabled());
    await userEvent.click(signInButton());
    expect(screen.getByText('Please enter your email address.')).toBeTruthy();
    expect(screen.getByText('Please enter your password.')).toBeTruthy();
    expect(await emailField()).toHaveAttribute('aria-invalid', 'true');
    expect(document.activeElement).toBe(await emailField());

    await userEvent.type(await emailField(), 'name@fci');
    expect(screen.getByText('Enter a valid email address (e.g. name@fci.gov.in).')).toBeTruthy();
    await userEvent.type(await emailField(), '.gov.in');
    expect(screen.queryByText(/valid email address/)).toBeNull();
    await userEvent.click(signInButton());
    expect(document.activeElement).toBe(passwordField());
    await userEvent.type(passwordField(), 'x');
    expect(screen.queryByText('Please enter your password.')).toBeNull();
    await userEvent.clear(passwordField());
    expect(calls.some((c) => c.url.endsWith('/auth/login'))).toBe(false);
  });

  it('SRS-G-35: a user ID is checked when the field is left, not while it is first typed', async () => {
    stub();
    render(wrap(<LoginPage />));
    await userEvent.type(await emailField(), 'not-an-email');
    expect(screen.queryByText(/valid email address/)).toBeNull();
    await userEvent.tab();
    expect(screen.getByText('Enter a valid email address (e.g. name@fci.gov.in).')).toBeTruthy();
    // Leaving an empty field says nothing yet.
    await userEvent.clear(await emailField());
    await userEvent.tab();
    expect(screen.getByText('Please enter your email address.')).toBeTruthy();
  });

  it('R-ASCII: a user ID outside printable ASCII is refused on the page', async () => {
    stub();
    render(wrap(<LoginPage />));
    await enter('नाम@fci.gov.in', 'pw');
    expect(screen.getByText('Use English letters, digits and symbols only.')).toBeTruthy();
    expect(calls.some((c) => c.url.endsWith('/auth/login'))).toBe(false);
  });

  it('OD-37: the Employee ID tab asks for the EIS number, clears the field and sends EMPLOYEE_ID', async () => {
    stub({ login: () => json(SENT, 202) });
    render(wrap(<LoginPage />));
    await userEvent.type(await emailField(), 'typed@fci.gov.in');
    await userEvent.click(screen.getByRole('tab', { name: 'Employee ID' }));
    const eis = screen.getByPlaceholderText('Enter your employee ID (EIS number)');
    expect(eis).toHaveValue('');
    expect(eis).toHaveAttribute('type', 'text');
    expect(document.activeElement).toBe(eis);
    expect(screen.getByLabelText('Employee ID', { selector: 'input' })).toBe(eis);
    await userEvent.click(signInButton());
    expect(screen.getByText('Please enter your employee ID.')).toBeTruthy();
    // Choosing the tab already chosen changes nothing.
    await userEvent.click(screen.getByRole('tab', { name: 'Employee ID' }));
    expect(screen.getByText('Please enter your employee ID.')).toBeTruthy();
    await userEvent.type(eis, 'EIS0012345');
    await userEvent.type(passwordField(), 'Fci@Demo2026');
    await userEvent.click(signInButton());
    expect(await screen.findByText(/A six-digit code has been sent by SMS/)).toBeTruthy();
    expect(bodyOf('/auth/login')).toMatchObject({ loginId: 'EIS0012345', idType: 'EMPLOYEE_ID' });
  });

  it('the tabs follow the arrow keys (WAI-ARIA tabs) and ignore other keys', async () => {
    stub();
    render(wrap(<LoginPage />));
    await emailField();
    const email = screen.getByRole('tab', { name: 'Email' });
    fireEvent.keyDown(email, { key: 'Enter' });
    expect(email).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(email, { key: 'ArrowRight' });
    const employee = screen.getByRole('tab', { name: 'Employee ID' });
    expect(employee).toHaveAttribute('aria-selected', 'true');
    expect(document.activeElement).toBe(employee);
    expect(employee).toHaveAttribute('tabindex', '0');
    expect(email).toHaveAttribute('tabindex', '-1');
    fireEvent.keyDown(employee, { key: 'ArrowLeft' });
    expect(email).toHaveAttribute('aria-selected', 'true');
  });

  it('toggles the password and warns while Caps Lock is on', async () => {
    stub();
    render(wrap(<LoginPage />));
    await emailField();
    await userEvent.click(screen.getByRole('button', { name: 'Show password' }));
    expect(passwordField()).toHaveAttribute('type', 'text');
    expect(screen.getByRole('button', { name: 'Hide password' })).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(passwordField()).toHaveAttribute('type', 'password');

    fireEvent.keyDown(passwordField(), { key: 'A', modifierCapsLock: true });
    expect(screen.getByText('Caps Lock is on')).toBeTruthy();
    fireEvent.keyUp(passwordField(), { key: 'CapsLock', modifierCapsLock: false });
    expect(screen.queryByText('Caps Lock is on')).toBeNull();
    fireEvent.keyDown(passwordField(), { key: 'A', modifierCapsLock: true });
    fireEvent.blur(passwordField());
    expect(screen.queryByText('Caps Lock is on')).toBeNull();
  });

  it('SRS-G-49: renders a refused sign-in from its message key, and a lost connection as a network error', async () => {
    stub({ login: () => problem('AUTH-401-CREDENTIALS', 401) });
    render(wrap(<LoginPage />));
    await enter('x@y.in', 'wrong');
    expect(await screen.findByRole('alert')).toHaveTextContent('The login ID or password is incorrect.');
    expect(bodyOf('/auth/login').location).toBeUndefined();

    push.mockImplementationOnce(() => {
      throw new TypeError('navigation failed');
    });
    vi.mocked(fetch).mockImplementation(async (url) =>
      String(url).endsWith('/auth/login') ? json(SIGNED) : json(OPTIONS),
    );
    await userEvent.click(signInButton());
    expect(await screen.findByRole('alert')).toHaveTextContent(/network|connection/i);
  });

  it('says once why the user was signed out after inactivity', async () => {
    stub();
    window.history.replaceState({}, '', '/?signedOut=idle');
    render(wrap(<LoginPage />));
    expect(await screen.findByRole('status')).toHaveTextContent(/inactiv/i);
  });

  it('OD_130_says why the session was ended for an unauthorized access attempt', async () => {
    stub();
    window.history.replaceState({}, '', '/?signedOut=offence');
    render(wrap(<LoginPage />));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Your session was ended: an unauthorized access attempt was recorded.',
    );
    window.history.replaceState({}, '', '/');
  });

  it('the options failing to load shows the network message', async () => {
    stub({ options: () => problem('COM-500', 500) });
    render(wrap(<LoginPage />));
    expect(await screen.findByText(/network|connection/i)).toBeTruthy();
  });
});

describe('the OTP on the registered mobile (SRS-G-48, OD-36)', () => {
  it('OD-41: "Sign in with OTP on registered mobile" asks only for the mobile, no password, and sends the code there', async () => {
    stub();
    render(wrap(<LoginPage />));
    await userEvent.type(await emailField(), 'typed@fci.gov.in');
    await userEvent.click(
      await screen.findByRole('button', { name: /Sign in with OTP on registered mobile/ }),
    );
    // The mode: its title, no tabs, no e-mail, no password, no Forgot password.
    expect(screen.getByRole('heading', { name: /Sign in with OTP on registered mobile/ })).toBeTruthy();
    expect(screen.queryByRole('tablist')).toBeNull();
    expect(screen.queryByPlaceholderText('Enter your email address')).toBeNull();
    expect(screen.queryByPlaceholderText('Enter your password')).toBeNull();
    expect(screen.queryByLabelText('Password')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Forgot password?' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Show password' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Sign in with OTP on registered mobile/ })).toBeNull();
    expect(
      screen.getByText('We will send a six-digit code to this number. No password is needed.'),
    ).toBeTruthy();
    const mobile = screen.getByLabelText('Registered mobile number');
    expect(mobile).toHaveValue('');
    expect(mobile).toHaveAttribute('type', 'tel');
    expect(mobile).toHaveAttribute('inputmode', 'tel');
    expect(mobile).toHaveAttribute('autocomplete', 'tel');
    expect(mobile).toHaveAttribute('placeholder', 'Enter your 10-digit mobile number');
    expect(document.activeElement).toBe(mobile);

    await userEvent.click(screen.getByRole('button', { name: /^Send OTP$/ }));
    expect(screen.getByText('Please enter your registered mobile number.')).toBeTruthy();
    await userEvent.type(mobile, '12345');
    expect(screen.getByText('Enter a 10-digit mobile number (e.g. 98765 43210).')).toBeTruthy();
    expect(calls.some((c) => c.url.endsWith('/auth/login/mobile'))).toBe(false);
    await userEvent.clear(mobile);
    await userEvent.type(mobile, '+91 98765 43210');
    await userEvent.click(screen.getByRole('button', { name: /^Send OTP$/ }));
    expect(await screen.findByText('A six-digit code has been sent by SMS to ******3210.')).toBeTruthy();
    expect(bodyOf('/auth/login/mobile')).toEqual({ mobile: '+91 98765 43210' });
    expect(calls.some((c) => c.url.endsWith('/auth/login'))).toBe(false);
    await userEvent.type(screen.getByLabelText('One-time password'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Verify and sign in' }));
    await waitFor(() => expect(push).toHaveBeenCalledWith('/payroll'));
  });

  it('OD-41: a refused mobile request (rate limit) is shown and the mode stays', async () => {
    stub({ mobile: () => problem('COM-429-RATE-LIMIT', 429) });
    render(wrap(<LoginPage />));
    await userEvent.click(
      await screen.findByRole('button', { name: /Sign in with OTP on registered mobile/ }),
    );
    await userEvent.type(screen.getByLabelText('Registered mobile number'), '9876543210');
    await userEvent.click(screen.getByRole('button', { name: /^Send OTP$/ }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByLabelText('Registered mobile number')).toHaveValue('9876543210');
  });

  it('OD-41: "Use email or Employee ID" leaves the mobile mode for the tabs', async () => {
    stub();
    render(wrap(<LoginPage />));
    await userEvent.click(
      await screen.findByRole('button', { name: /Sign in with OTP on registered mobile/ }),
    );
    await userEvent.type(screen.getByLabelText('Registered mobile number'), '9876543210');
    await userEvent.click(screen.getByRole('button', { name: 'Use email or Employee ID' }));
    expect(screen.getByRole('tab', { name: 'Email' })).toHaveAttribute('aria-selected', 'true');
    expect(await emailField()).toHaveValue('');
    expect(screen.getByRole('button', { name: /^Sign in$/ })).toBeTruthy();
  });

  it.each(['disabled', 'omitted', 'unavailable'] as const)(
    'keeps the wireframe mobile OTP option visible when login options are %s',
    async (state) => {
      stub({
        options: () =>
          state === 'unavailable'
            ? problem('COM-503-UNAVAILABLE', 503)
            : json({ demo: false, otpAtLogin: true, ...(state === 'disabled' ? { mobileOtp: false } : {}) }),
      });
      render(wrap(<LoginPage />));
      await emailField();
      if (state === 'unavailable') await screen.findByRole('alert');
      await userEvent.click(screen.getByRole('button', { name: /Sign in with OTP on registered mobile/ }));
      expect(screen.getByLabelText('Registered mobile number')).toBeVisible();
      expect(screen.getByRole('button', { name: /^Send OTP$/ })).toBeVisible();
      expect(screen.queryByLabelText('Password')).toBeNull();
    },
  );

  it('the password sends the code; the masked mobile and the validity are shown; the code opens the workspace', async () => {
    stub({ login: () => json(SENT, 202) });
    render(wrap(<LoginPage />));
    await enter('agm.west@fci-demo.test', 'Fci@Demo2026');
    expect(await screen.findByText('A six-digit code has been sent by SMS to ******3210.')).toBeTruthy();
    expect(screen.getByText('Valid for 5:00')).toBeTruthy();
    expect(screen.queryByPlaceholderText('Enter your password')).toBeNull();
    const code = screen.getByLabelText('One-time password');
    expect(document.activeElement).toBe(code);
    expect(code).toHaveAttribute('autocomplete', 'one-time-code');
    expect(code).toHaveAttribute('inputmode', 'numeric');
    expect(push).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Verify and sign in' }));
    expect(screen.getByText('Enter the six-digit code.')).toBeTruthy();
    expect(calls.some((c) => c.url.endsWith('/auth/login/otp'))).toBe(false);
    await userEvent.type(code, '12a3-45 678');
    expect(code).toHaveValue('123456');
    expect(screen.queryByText('Enter the six-digit code.')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Verify and sign in' }));
    await waitFor(() => expect(push).toHaveBeenCalledWith('/payroll'));
    expect(bodyOf('/auth/login/otp')).toEqual({ code: '123456' });
  });

  it('a user without a mobile is told the code went by e-mail', async () => {
    stub({ login: () => json({ ...SENT, channel: 'EMAIL', destination: 'a***@fci-demo.test' }, 202) });
    render(wrap(<LoginPage />));
    await enter('agm.south@fci-demo.test', 'Fci@Demo2026');
    expect(
      await screen.findByText('A six-digit code has been sent by e-mail to a***@fci-demo.test.'),
    ).toBeTruthy();
  });

  it('SRS-G-49: a wrong code is refused and cleared for another try; a resend says the earlier one stopped', async () => {
    let wrong = true;
    stub({
      login: () => json(SENT, 202),
      otp: () => (wrong ? problem('AUTH-400-OTP', 400) : json(SIGNED)),
      resend: () => json({ ...SENT, destination: '******9999', validSeconds: 120 }, 202),
    });
    render(wrap(<LoginPage />));
    await enter('agm.west@fci-demo.test', 'Fci@Demo2026');
    const code = await screen.findByLabelText('One-time password');
    await userEvent.type(code, '000000');
    await userEvent.click(screen.getByRole('button', { name: 'Verify and sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The one-time password is incorrect or has expired.',
    );
    expect(code).toHaveValue('');

    await userEvent.type(code, '11');
    await userEvent.click(screen.getByRole('button', { name: 'Resend code' }));
    expect(await screen.findByText(/A new code has been sent/)).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText('A six-digit code has been sent by SMS to ******9999.')).toBeTruthy();
    expect(screen.getByText('Valid for 2:00')).toBeTruthy();
    expect(code).toHaveValue('');
    wrong = false;
    await userEvent.type(code, '654321');
    await userEvent.click(screen.getByRole('button', { name: 'Verify and sign in' }));
    await waitFor(() => expect(push).toHaveBeenCalledWith('/payroll'));
  });

  it('a refused resend (rate limit) is shown and the step stays', async () => {
    stub({ login: () => json(SENT, 202), resend: () => problem('COM-429-RATE-LIMIT', 429) });
    render(wrap(<LoginPage />));
    await enter('agm.west@fci-demo.test', 'Fci@Demo2026');
    await userEvent.click(await screen.findByRole('button', { name: 'Resend code' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(/A new code has been sent/)).toBeNull();
    expect(screen.getByLabelText('One-time password')).toBeTruthy();
  });

  it('an expired code cannot be verified; "Use another account" returns to an empty password', async () => {
    stub({ login: () => json({ ...SENT, validSeconds: 0 }, 202) });
    render(wrap(<LoginPage />));
    await enter('agm.west@fci-demo.test', 'Fci@Demo2026');
    expect(await screen.findByText('The code has expired. Ask for a new one.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Verify and sign in' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Use another account' }));
    expect(passwordField()).toHaveValue('');
    expect(await emailField()).toHaveValue('agm.west@fci-demo.test');
  });

  it('the validity counts down every second', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      stub({ login: () => json({ ...SENT, validSeconds: 3 }, 202) });
      render(wrap(<LoginPage />));
      await enter('agm.west@fci-demo.test', 'Fci@Demo2026');
      expect(await screen.findByText('Valid for 0:03')).toBeTruthy();
      await act(async () => {
        vi.advanceTimersByTime(3100);
      });
      expect(screen.getByText('The code has expired. Ask for a new one.')).toBeTruthy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('a failure after the code was accepted is shown as a network error', async () => {
    stub({ login: () => json(SENT, 202) });
    render(wrap(<LoginPage />));
    await enter('agm.west@fci-demo.test', 'Fci@Demo2026');
    push.mockImplementationOnce(() => {
      throw new TypeError('navigation failed');
    });
    await userEvent.type(await screen.findByLabelText('One-time password'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Verify and sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/network|connection/i);
  });

  it('a resend that fails without an answer is shown as a network error', async () => {
    stub({ login: () => json(SENT, 202) });
    render(wrap(<LoginPage />));
    await enter('agm.west@fci-demo.test', 'Fci@Demo2026');
    await screen.findByLabelText('One-time password');
    vi.mocked(fetch).mockRejectedValue(new TypeError('offline'));
    await userEvent.click(screen.getByRole('button', { name: 'Resend code' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/network|connection/i);
  });
});

describe('every sign-in method (OD-37, OD-41)', () => {
  const withPassword: [
    string,
    (u: ReturnType<typeof userEvent.setup>) => Promise<void>,
    string,
    string,
    string,
  ][] = [
    ['email', async () => undefined, 'Email Address', 'agm.north@fci-demo.test', 'USER_ID'],
    [
      'Employee ID',
      async (u) => u.click(screen.getByRole('tab', { name: 'Employee ID' })),
      'Employee ID',
      '107506',
      'EMPLOYEE_ID',
    ],
  ];
  for (const [name, open, label, id, idType] of withPassword) {
    it(`${name}: with the password, sent with its type; a refusal is shown; then the code step`, async () => {
      let refuse = true;
      stub({ login: () => (refuse ? problem('AUTH-401-CREDENTIALS', 401) : json(SENT, 202)) });
      const u = userEvent.setup();
      render(wrap(<LoginPage />));
      await emailField();
      await open(u);
      await u.type(screen.getByLabelText(label, { selector: 'input' }), id);
      await u.type(passwordField(), 'Fci@Demo2026');
      expect(screen.getByRole('button', { name: 'Forgot password?' })).toBeTruthy();
      const submit = screen.getByRole('button', { name: /^Sign in$/ });
      await u.click(submit);
      expect(await screen.findByRole('alert')).toHaveTextContent('The login ID or password is incorrect.');
      expect(bodyOf('/auth/login')).toEqual({ loginId: id, password: 'Fci@Demo2026', idType });
      refuse = false;
      await u.click(submit);
      expect(await screen.findByLabelText('One-time password')).toBeTruthy();
    });
  }

  it('mobile: the number alone, then its code; a wrong code is refused as for the others', async () => {
    stub({ otp: () => problem('AUTH-400-OTP', 400) });
    const u = userEvent.setup();
    render(wrap(<LoginPage />));
    await u.click(await screen.findByRole('button', { name: /Sign in with OTP on registered mobile/ }));
    await u.type(screen.getByLabelText('Registered mobile number'), '9000010008');
    await u.click(screen.getByRole('button', { name: /^Send OTP$/ }));
    await u.type(await screen.findByLabelText('One-time password'), '000000');
    await u.click(screen.getByRole('button', { name: 'Verify and sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The one-time password is incorrect or has expired.',
    );
    expect(bodyOf('/auth/login/mobile')).toEqual({ mobile: '9000010008' });
  });
});

describe('remember this device (OD-38)', () => {
  it('keeps the method and the user ID after a sign-in, prefills them next time, and forgets when unticked', async () => {
    stub({ login: () => json(SENT, 202) });
    const first = render(wrap(<LoginPage />));
    await userEvent.click(await screen.findByRole('tab', { name: 'Employee ID' }));
    await userEvent.type(screen.getByPlaceholderText('Enter your employee ID (EIS number)'), 'EIS0012345');
    await userEvent.type(passwordField(), 'Fci@Demo2026');
    await userEvent.click(screen.getByLabelText('Remember this device'));
    expect(screen.getByText(/Never the password/)).toBeTruthy();
    await userEvent.click(signInButton());
    // Nothing is remembered before the sign-in has succeeded.
    expect(document.cookie).not.toContain('FCI_REMEMBER');
    await userEvent.type(await screen.findByLabelText('One-time password'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Verify and sign in' }));
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(document.cookie).toContain('FCI_REMEMBER=employee%7CEIS0012345');
    expect(document.cookie).not.toMatch(/Fci%40Demo|Fci@Demo/);
    first.unmount();

    stub({ login: () => json(SIGNED) });
    render(wrap(<LoginPage />));
    const eis = await screen.findByPlaceholderText('Enter your employee ID (EIS number)');
    await waitFor(() => expect(eis).toHaveValue('EIS0012345'));
    expect(screen.getByRole('tab', { name: 'Employee ID' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByLabelText('Remember this device')).toBeChecked();
    await userEvent.click(screen.getByLabelText('Remember this device'));
    await userEvent.type(passwordField(), 'Fci@Demo2026');
    await userEvent.click(signInButton());
    await waitFor(() => expect(push).toHaveBeenCalledTimes(2));
    expect(document.cookie).not.toContain('FCI_REMEMBER');
  });

  it('sign-out (which clears every cookie of the site) keeps a remembered sign-in, and only that', async () => {
    document.cookie = 'FCI_REMEMBER=email%7Ca.b%40fci.gov.in; Path=/';
    stub({
      logout: () => {
        // As Clear-Site-Data "cookies" does in a browser.
        clearRemembered();
        return new Response(null, { status: 204 });
      },
    });
    const queryClient = new QueryClient();
    const { result } = renderHook(() => useSignOut(), {
      wrapper: ({ children }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>,
    });
    await act(() => result.current());
    expect(document.cookie).toContain('FCI_REMEMBER=email%7Ca.b%40fci.gov.in');

    clearRemembered();
    await act(() => result.current());
    expect(document.cookie).not.toContain('FCI_REMEMBER');
  });
});

describe('help, privacy and password reset', () => {
  it('opens help from the footer, the privacy note, and the OTP reset dialog', async () => {
    stub();
    render(wrap(<LoginPage />));
    await emailField();
    await userEvent.click(screen.getByRole('button', { name: 'Need help? Contact your HR / IT helpdesk' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('Extension 5000');
    await userEvent.click(screen.getByRole('button', { name: /Got it/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Privacy policy' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('How sign-in handles your data');
    expect(screen.getByRole('dialog')).toHaveTextContent(/never stored in the browser/);
    expect(screen.getByRole('dialog')).not.toHaveTextContent('Extension 5000');
    await userEvent.click(screen.getByRole('button', { name: 'Close help' }));
    expect(screen.queryByRole('dialog')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Forgot password?' }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(
      dialog.querySelector('input[name="loginId"]') as HTMLInputElement,
      'ldh.depot@fci-demo.test',
    );
    await userEvent.click(screen.getByRole('button', { name: /Send one-time password/ }));
    expect(await screen.findByText(/valid for 10 minutes/)).toBeTruthy();
    await userEvent.type(screen.getByLabelText('New password'), 'Neem#Tree-Shade-42');
    await userEvent.type(screen.getByLabelText('Confirm new password'), 'Different#Tree-1');
    await userEvent.click(screen.getByRole('button', { name: /Set new password/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('do not match');
  });

  it('SRS-SEC-6: a completed reset returns to the sign-in', async () => {
    stub();
    render(wrap(<LoginPage />));
    await emailField();
    await userEvent.click(screen.getByRole('button', { name: 'Forgot password?' }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(
      dialog.querySelector('input[name="loginId"]') as HTMLInputElement,
      'ldh.depot@fci-demo.test',
    );
    await userEvent.click(screen.getByRole('button', { name: /Send one-time password/ }));
    await screen.findByText(/valid for 10 minutes/);
    await userEvent.type(dialog.querySelector('input[name="code"]') as HTMLInputElement, '123456');
    await userEvent.type(screen.getByLabelText('New password'), 'Neem#Tree-Shade-42');
    await userEvent.type(screen.getByLabelText('Confirm new password'), 'Neem#Tree-Shade-42');
    await userEvent.click(screen.getByRole('button', { name: /Set new password/ }));
    await userEvent.click(await screen.findByRole('button', { name: /Got it/ }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(calls.some((c) => c.url.endsWith('/auth/password/reset'))).toBe(true);
  });

  it('the language toggle relabels the card in Hindi', async () => {
    stub();
    render(wrap(<LoginPage />));
    await emailField();
    await userEvent.click(screen.getByRole('button', { name: 'हिं' }));
    expect(await screen.findByRole('tab', { name: 'कर्मचारी आईडी' })).toBeTruthy();
    expect(screen.getByText('वेतन प्रबंधन प्रणाली')).toBeTruthy();
  });
});
