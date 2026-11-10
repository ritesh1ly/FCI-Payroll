import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { Dropdown, ReasonDialog, ToastProvider, useToast } from '@/shared/ui';
import { GovernedProvider, useGoverned } from '@/modules/workflow';
import { ApiError } from '@/shared/api';
import { I18nProvider } from '@/shared/i18n';

vi.stubGlobal(
  'fetch',
  vi.fn(async () => new Response(JSON.stringify({ locale: 'en' }), { status: 200 })),
);

function Runner({ call }: { call: (otp?: { challengeId: number; code: string }) => Promise<string> }) {
  const run = useGoverned();
  const [result, setResult] = useState('');
  return (
    <button
      type="button"
      onClick={() => run(call).then(setResult, (e: ApiError) => setResult(`failed:${e.messageKey}`))}
    >
      go {result}
    </button>
  );
}

const otpRequired = () => new ApiError(428, { errorCode: 'AUTH-428-OTP', args: ['7'] }, undefined);
const wrongOtp = () =>
  new ApiError(400, { errorCode: 'AUTH-400-OTP', messageKey: 'error.AUTH-400-OTP' }, undefined);

describe('governed actions', () => {
  it('asks for the OTP on 428, retries with it and re-asks after a wrong code', async () => {
    const call = vi.fn(async (otp?: { challengeId: number; code: string }) => {
      if (!otp) throw otpRequired();
      if (otp.code !== '123456') throw wrongOtp();
      return `ok:${otp.challengeId}`;
    });
    render(
      <I18nProvider>
        <GovernedProvider>
          <Runner call={call} />
        </GovernedProvider>
      </I18nProvider>,
    );
    await userEvent.click(screen.getByRole('button', { name: /go/ }));
    const code = await screen.findByLabelText('One-time password');
    await userEvent.type(code, '000000');
    await userEvent.click(screen.getByRole('button', { name: 'Verify and continue' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('incorrect or has expired');
    await userEvent.clear(screen.getByLabelText('One-time password'));
    await userEvent.type(screen.getByLabelText('One-time password'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Verify and continue' }));
    await waitFor(() => expect(screen.getByRole('button', { name: /go ok:7/ })).toBeTruthy());
    expect(call).toHaveBeenCalledTimes(3);
  });

  it('cancelling the OTP fails the action without retrying, other errors pass through', async () => {
    const call = vi.fn(async () => {
      throw otpRequired();
    });
    render(
      <I18nProvider>
        <GovernedProvider>
          <Runner call={call} />
          <Runner call={async () => Promise.reject(wrongOtp())} />
        </GovernedProvider>
      </I18nProvider>,
    );
    const [first, second] = screen.getAllByRole('button', { name: /go/ });
    await userEvent.click(first as HTMLElement);
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(await screen.findByRole('button', { name: /failed:otp.cancelled/ })).toBeTruthy();
    expect(call).toHaveBeenCalledTimes(1);
    await userEvent.click(second as HTMLElement);
    expect(await screen.findByRole('button', { name: /failed:error.AUTH-400-OTP/ })).toBeTruthy();
  });
});

const challenge = (id: number) =>
  new ApiError(428, { errorCode: 'AUTH-428-OTP', args: [String(id)] }, undefined);

function governed(call: (otp?: { challengeId: number; code: string }) => Promise<string>) {
  render(
    <I18nProvider>
      <GovernedProvider>
        <Runner call={call} />
      </GovernedProvider>
    </I18nProvider>,
  );
}

describe('Resend on the OTP dialog (SRS-EH-13)', () => {
  it('a new code replaces the earlier one: the call is asked again and verified with the new challenge', async () => {
    let issued = 7;
    const call = vi.fn(async (otp?: { challengeId: number; code: string }) => {
      if (!otp) throw challenge(issued++);
      if (otp.challengeId !== 8) throw wrongOtp();
      return `ok:${otp.challengeId}`;
    });
    governed(call);
    await userEvent.click(screen.getByRole('button', { name: /go/ }));
    await userEvent.type(await screen.findByLabelText('One-time password'), '000000');
    await userEvent.click(screen.getByRole('button', { name: 'Verify and continue' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('incorrect or has expired');
    await userEvent.click(screen.getByRole('button', { name: 'Resend code' }));
    expect(
      await screen.findByText('A new code has been sent. The earlier one no longer works.'),
    ).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    await userEvent.clear(screen.getByLabelText('One-time password'));
    await userEvent.type(screen.getByLabelText('One-time password'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Verify and continue' }));
    await waitFor(() => expect(screen.getByRole('button', { name: /go ok:8/ })).toBeTruthy());
    // The first ask, the wrong code, the resend, the right code.
    expect(call).toHaveBeenCalledTimes(4);
  });

  it('a resend refused at the hourly limit is said; the code already sent still works', async () => {
    let asks = 0;
    const call = vi.fn(async (otp?: { challengeId: number; code: string }) => {
      if (!otp) {
        asks += 1;
        if (asks === 1) throw challenge(7);
        throw new ApiError(
          429,
          { errorCode: 'COM-429-RATE-LIMIT', messageKey: 'error.COM-429-RATE-LIMIT' },
          undefined,
        );
      }
      return `ok:${otp.challengeId}`;
    });
    governed(call);
    await userEvent.click(screen.getByRole('button', { name: /go/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Resend code' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(/A new code has been sent/)).toBeNull();
    await userEvent.type(screen.getByLabelText('One-time password'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Verify and continue' }));
    await waitFor(() => expect(screen.getByRole('button', { name: /go ok:7/ })).toBeTruthy());
  });

  it('an action that no longer needs a code is done by the resend itself; a lost connection is said', async () => {
    let asks = 0;
    const done = vi.fn(async (otp?: { challengeId: number; code: string }) => {
      asks += 1;
      if (!otp && asks === 1) throw challenge(7);
      return 'done';
    });
    governed(done);
    await userEvent.click(screen.getByRole('button', { name: /go/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Resend code' }));
    await waitFor(() => expect(screen.getByRole('button', { name: /go done/ })).toBeTruthy());
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('a resend that fails without an answer shows the network message and keeps the dialog', async () => {
    let asks = 0;
    const call = vi.fn(async (otp?: { challengeId: number; code: string }) => {
      if (!otp) {
        asks += 1;
        if (asks === 1) throw challenge(7);
        throw new TypeError('offline');
      }
      return 'ok';
    });
    governed(call);
    await userEvent.click(screen.getByRole('button', { name: /go/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Resend code' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/network|connection/i);
    expect(screen.getByRole('dialog')).toBeTruthy();
  });
});

describe('workspace widgets', () => {
  it('the live dropdown lists options, selects one and closes on Escape', async () => {
    const onChange = vi.fn();
    render(
      <Dropdown
        label="Scope"
        value="a"
        onChange={onChange}
        options={[
          { value: 'a', label: 'DO — Delhi HQ (DH001)', subLabel: 'DDO / Manager' },
          { value: 'b', label: 'DO — Mumbai Regional (MR001)' },
        ]}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Scope' }));
    await userEvent.click(screen.getByRole('option', { name: /Mumbai/ }));
    expect(onChange).toHaveBeenCalledWith('b');
    await userEvent.click(screen.getByRole('button', { name: 'Scope' }));
    expect(screen.getByRole('listbox')).toBeTruthy();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('the reason dialog requires a reason and the toast shows messages', async () => {
    const onConfirm = vi.fn();
    function Toaster() {
      const toast = useToast();
      return (
        <button type="button" onClick={() => toast('saved')}>
          toast
        </button>
      );
    }
    render(
      <I18nProvider>
        <ToastProvider>
          <Toaster />
          <ReasonDialog title="Reject" onConfirm={onConfirm} onCancel={vi.fn()} />
        </ToastProvider>
      </I18nProvider>,
    );
    expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason'), '  wrong amount ');
    await userEvent.click(screen.getByRole('button', { name: 'Submit' }));
    expect(onConfirm).toHaveBeenCalledWith('wrong amount');
    await userEvent.click(screen.getByRole('button', { name: 'toast' }));
    expect(screen.getByRole('status')).toHaveTextContent('saved');
  });

  it('R_ASCII: a reason the US7ASCII database cannot hold is refused before it is sent', async () => {
    const onConfirm = vi.fn();
    render(
      <I18nProvider>
        <ReasonDialog title="Reject" onConfirm={onConfirm} onCancel={vi.fn()} />
      </I18nProvider>,
    );
    await userEvent.type(screen.getByLabelText('Reason'), 'अस्वीकृत');
    expect(screen.getByRole('alert')).toHaveTextContent('Use English letters, digits and punctuation only.');
    expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled();
    await userEvent.clear(screen.getByLabelText('Reason'));
    // Line breaks are plain text too.
    await userEvent.type(screen.getByLabelText('Reason'), 'Wrong amount{enter}see order 17');
    expect(screen.queryByRole('alert')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Submit' }));
    expect(onConfirm).toHaveBeenCalledWith('Wrong amount\nsee order 17');
  });
});
