import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { needsStepUp } from '@/modules/iam';
import { IdleGuard } from '@/modules/shell/components/IdleGuard';
import { I18nProvider } from '@/shared/i18n';

/**
 * Session guards of the workspace: the OTP step-up rule of sensitive screens (SRS-G-48, Module 02
 * D-2) and the inactivity sign-out with its 30-second warning (SRS-SEC-14), edges included.
 */

describe('needsStepUp', () => {
  const NOW = Date.parse('2026-10-21T10:00:00Z');
  const me = (validUntil: string | null) => ({
    sensitiveScreens: ['W-01', 'M-01'],
    stepUpValidUntil: validUntil,
  });

  it('never asks on a screen without a sensitive action, nor without a screen', () => {
    expect(needsStepUp(me(null), 'R-01', NOW)).toBe(false);
    expect(needsStepUp(me(null), null, NOW)).toBe(false);
  });

  it('asks on a sensitive screen without a confirmation', () => {
    expect(needsStepUp(me(null), 'W-01', NOW)).toBe(true);
  });

  it('does not ask again within the validity, and asks exactly at its end', () => {
    expect(needsStepUp(me('2026-10-21T10:00:01Z'), 'W-01', NOW)).toBe(false);
    expect(needsStepUp(me('2026-10-21T10:00:00Z'), 'W-01', NOW)).toBe(true);
    expect(needsStepUp(me('2026-10-21T09:59:59Z'), 'M-01', NOW)).toBe(true);
  });
});

describe('IdleGuard', () => {
  beforeEach(() => {
    vi.useFakeTimers({ now: Date.parse('2026-10-21T10:00:00Z') });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  function guard(onKeepAlive = vi.fn(async () => undefined), onExpire = vi.fn()) {
    render(
      <I18nProvider>
        <IdleGuard idleTimeoutSeconds={60} onKeepAlive={onKeepAlive} onExpire={onExpire} />
      </I18nProvider>,
    );
    return { onKeepAlive, onExpire };
  }

  const advance = (seconds: number) =>
    act(() => {
      vi.advanceTimersByTime(seconds * 1000);
    });

  // SC-01-0153: the session notice warns before the inactivity logout.
  it('warns 30 seconds before the timeout, not earlier, with a countdown', () => {
    guard();
    advance(29);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    advance(1);
    expect(screen.getByRole('alertdialog')).toBeTruthy();
    expect(screen.getByRole('timer').textContent).toContain('30');
    advance(10);
    expect(screen.getByRole('timer').textContent).toContain('20');
  });

  it('ignores a mouse moving past the warning; only the button keeps the session', async () => {
    const { onKeepAlive, onExpire } = guard();
    advance(35);
    fireEvent.pointerDown(window);
    advance(1);
    expect(screen.getByRole('alertdialog')).toBeTruthy();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Stay signed in' }));
    });
    expect(onKeepAlive).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    advance(29);
    expect(onExpire).not.toHaveBeenCalled();
  });

  it('signs out once at zero when nobody answers', () => {
    const { onExpire } = guard();
    advance(60);
    expect(onExpire).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    advance(120);
    expect(onExpire).toHaveBeenCalledTimes(1);
  });

  it('counts the user working before the warning as activity and keeps the server alive at most once a minute', () => {
    const { onKeepAlive, onExpire } = guard();
    advance(25);
    fireEvent.keyDown(window, { key: 'a' });
    advance(25);
    // 50 s since sign-in but only 25 s since the key: no warning yet, and the server was called
    // less than a minute ago: no keep-alive.
    expect(screen.queryByRole('alertdialog')).toBeNull();
    fireEvent.keyDown(window, { key: 'b' });
    expect(onKeepAlive).not.toHaveBeenCalled();
    advance(15);
    // 65 s since the last server call: the next key keeps the server session alive.
    fireEvent.keyDown(window, { key: 'c' });
    expect(onKeepAlive).toHaveBeenCalledTimes(1);
    expect(onExpire).not.toHaveBeenCalled();
  });
  it('closes the warning when a longer timeout arrives while it shows', () => {
    const props = { onKeepAlive: vi.fn(async () => undefined), onExpire: vi.fn() };
    const { rerender } = render(
      <I18nProvider>
        <IdleGuard idleTimeoutSeconds={60} {...props} />
      </I18nProvider>,
    );
    advance(35);
    expect(screen.getByRole('alertdialog')).toBeTruthy();
    rerender(
      <I18nProvider>
        <IdleGuard idleTimeoutSeconds={600} {...props} />
      </I18nProvider>,
    );
    advance(1);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(props.onExpire).not.toHaveBeenCalled();
  });
});
