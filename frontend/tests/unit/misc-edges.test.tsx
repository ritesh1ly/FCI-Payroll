import { act, fireEvent, render, renderHook, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { authApi } from '@/modules/iam/api/authApi';
import { IdleGuard } from '@/modules/shell/components/IdleGuard';
import { workflowApi } from '@/modules/workflow/api/workflowApi';
import { api } from '@/shared/api';
import { I18nProvider, useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { Dropdown } from '@/shared/ui';
import { backend, referenceLists, sent, wrap } from './support/harness';

/**
 * Small shared pieces at their edges: the request bodies of a scope switch, step-up and decision
 * with and without an OTP or reason; Hindi labels that fall back to English; a dropdown closed by
 * Escape; a server call during the idle warning that does not count as activity; a locale adopted
 * from the server only until the user chose one; the i18n hook outside its provider.
 */

afterEach(() => vi.unstubAllGlobals());

describe('API request bodies', () => {
  it('a scope switch names the office and the OTP only when given; a step-up sends its OTP only when it has one', async () => {
    const calls = backend({});
    await authApi.switchScope('EMPLOYEE_ESEVA', null);
    await authApi.switchScope('PAYROLL_MAKER', 'DH001', { challengeId: 7, code: '123456' });
    await authApi.stepUp();
    await authApi.stepUp({ challengeId: 8, code: '654321' });
    expect(sent(calls, 'POST', '/auth/switch-scope')).toEqual([
      { role: 'EMPLOYEE_ESEVA' },
      { role: 'PAYROLL_MAKER', location: 'DH001', otp: { challengeId: 7, code: '123456' } },
    ]);
    expect(sent(calls, 'POST', '/auth/step-up')).toEqual([{}, { otp: { challengeId: 8, code: '654321' } }]);
  });

  it('a decision without a reason sends none; the OTP goes with a decision and a governed action only when given', async () => {
    const calls = backend({});
    await workflowApi.decide(9, 'APPROVE', '');
    await workflowApi.decide(9, 'REJECT', 'Wrong period', { challengeId: 7, code: '123456' });
    await workflowApi.performAction('M-01', 'CREATE', null);
    await workflowApi.performAction('M-01', 'CREATE', 'New hire', { challengeId: 7, code: '123456' });
    expect(sent(calls, 'POST', '/v1/approvals/9/decision')).toEqual([
      { decision: 'APPROVE', reason: null },
      { decision: 'REJECT', reason: 'Wrong period', otp: { challengeId: 7, code: '123456' } },
    ]);
    expect(sent(calls, 'POST', '/v1/actions')).toEqual([
      { screen: 'M-01', action: 'CREATE', reason: null },
      { screen: 'M-01', action: 'CREATE', reason: 'New hire', otp: { challengeId: 7, code: '123456' } },
    ]);
  });
});

function Labels() {
  const reference = useReference();
  const { setLocale } = useI18n();
  return (
    <div>
      <button type="button" onClick={() => void setLocale('hi')}>
        hi
      </button>
      <span data-testid="with">{reference.label('CLAIM_TYPE', 'LABOUR_TA')}</span>
      <span data-testid="without">{reference.label('CLAIM_TYPE', 'TRANSFER_TA')}</span>
      <span data-testid="none">{reference.label('CLAIM_TYPE', null)}</span>
    </div>
  );
}

describe('reference labels in Hindi', () => {
  it('a value with a Hindi label shows it; one without keeps its English label; no code is no label', async () => {
    const lists = referenceLists({
      CLAIM_TYPE: [
        ['LABOUR_TA', 'Labour TA'],
        ['TRANSFER_TA', 'Transfer TA'],
      ],
    });
    const claimType = lists.lists.CLAIM_TYPE as { values: { labelHi: string | null }[] };
    (claimType.values[0] as { labelHi: string | null }).labelHi = 'Labour TA (hi)';
    backend({ 'GET /v1/reference/lists': () => lists });
    wrap(<Labels />, ['M-15:VIEW']);
    await screen.findByText('Labour TA');
    expect(screen.getByTestId('none').textContent).toBe('');
    await userEvent.click(screen.getByRole('button', { name: 'hi' }));
    expect(await screen.findByText('Labour TA (hi)')).toBeTruthy();
    expect(screen.getByTestId('without').textContent).toBe('Transfer TA');
  });
});

describe('Dropdown', () => {
  it('Escape closes it; a form dropdown that is disabled stays shut; an unselected option shows its sub-label plainly', async () => {
    const options = [
      { value: 'a', label: 'Alpha' },
      { value: 'b', label: 'Beta', subLabel: 'second' },
      { value: 'c', label: 'Gamma', subLabel: 'third' },
    ];
    const { rerender } = render(
      <Dropdown label="Pick" value="b" onChange={vi.fn()} options={options} variant="form" />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Pick' }));
    expect(screen.getByRole('listbox')).toBeTruthy();
    expect(screen.getByRole('option', { name: /Gamma/ }).getAttribute('aria-selected')).toBe('false');
    expect(screen.getByText('third')).toBeTruthy();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('listbox')).toBeNull();
    rerender(
      <Dropdown label="Pick" value="b" onChange={vi.fn()} options={options} variant="form" disabled />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Pick' }));
    expect(screen.queryByRole('listbox')).toBeNull();
  });
});

describe('IdleGuard during the warning', () => {
  it('a server call while the warning shows does not count as the user working', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      backend({});
      const onExpire = vi.fn();
      render(
        <I18nProvider>
          <IdleGuard idleTimeoutSeconds={60} onKeepAlive={() => Promise.resolve()} onExpire={onExpire} />
        </I18nProvider>,
      );
      act(() => {
        vi.advanceTimersByTime(35 * 1000);
      });
      expect(screen.getByRole('alertdialog')).toBeTruthy();
      await api('/v1/ping');
      act(() => {
        vi.advanceTimersByTime(30 * 1000);
      });
      expect(onExpire).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });
});

function Adopter() {
  const { locale, adopt, setLocale } = useI18n();
  return (
    <div>
      <span data-testid="locale">{locale}</span>
      <button type="button" onClick={() => adopt('hi')}>
        adopt hi
      </button>
      <button type="button" onClick={() => adopt('xx')}>
        adopt xx
      </button>
      <button type="button" onClick={() => void setLocale('en')}>
        choose en
      </button>
    </div>
  );
}

describe('the i18n provider', () => {
  it('adopts a known locale from the server until the user chose one; an unknown one is ignored', async () => {
    backend({});
    render(
      <I18nProvider>
        <Adopter />
      </I18nProvider>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'adopt xx' }));
    expect(screen.getByTestId('locale').textContent).toBe('en');
    await userEvent.click(screen.getByRole('button', { name: 'adopt hi' }));
    expect(screen.getByTestId('locale').textContent).toBe('hi');
    await userEvent.click(screen.getByRole('button', { name: 'choose en' }));
    await userEvent.click(screen.getByRole('button', { name: 'adopt hi' }));
    expect(screen.getByTestId('locale').textContent).toBe('en');
  });

  it('the hook outside its provider is a programming error', () => {
    expect(() => renderHook(() => useI18n())).toThrow('useI18n outside I18nProvider');
  });
});
