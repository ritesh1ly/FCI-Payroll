import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, onStepUpRequired } from '@/shared/api';
import { I18nProvider } from '@/shared/i18n';
import { correctiveAction, date, dateTime, dayOf, errorMessage, Problem, StatusPill } from '@/shared/ui';
import { t } from './support/harness';

const translate = (key: string, args?: readonly unknown[]) => t(key, args ? [...args] : []);

/**
 * The shared pieces at their edges: a status the pill palette does not know, a failure that is no
 * API problem, dates that are empty or malformed, a problem answer without a title or code, and
 * the step-up retry of the API client (SRS-G-48).
 */

afterEach(() => {
  vi.unstubAllGlobals();
  onStepUpRequired(null);
});

describe('shared primitives', () => {
  it('an unknown status is a grey pill and a failure that is no API problem reads as a network failure', () => {
    render(
      <I18nProvider>
        <StatusPill status="ARCHIVED" />
        <Problem error={new Error('boom')} />
      </I18nProvider>,
    );
    expect(screen.getByText(t('status.ARCHIVED')).className).toBe('pill p-gray');
    expect(screen.getByRole('alert').textContent).toContain(t('error.network'));
    expect(errorMessage(translate, new Error('boom'))).toBe(t('error.unexpected'));
    expect(
      errorMessage(translate, new ApiError(404, { messageKey: 'error.COM-404-NOT-FOUND' }, undefined)),
    ).toBe(t('error.COM-404-NOT-FOUND'));
  });

  it('SRS-G-54 (US-GAP-111): a status is shown by its text as well as its colour', () => {
    render(
      <I18nProvider>
        <StatusPill status="REJECTED" />
        <StatusPill status="APPROVED" />
      </I18nProvider>,
    );
    for (const status of ['REJECTED', 'APPROVED']) {
      const pill = screen.getByText(t(`status.${status}`));
      expect(pill.textContent?.trim().length).toBeGreaterThan(0);
      expect(pill.getAttribute('aria-hidden')).toBeNull();
    }
    expect(screen.getByText(t('status.REJECTED')).className).toBe('pill p-bad');
    expect(screen.getByText(t('status.APPROVED')).className).toBe('pill p-ok');
  });

  it('dates that are empty or malformed are shown as a dash or as they came', () => {
    expect(date('')).toBe('—');
    expect(date(null)).toBe('—');
    expect(date('2026-13')).toBe('2026-13');
    expect(date('next week')).toBe('next week');
    expect(dateTime(undefined)).toBe('—');
    expect(dateTime('not a time')).toBe('not a time');
  });

  it('a received instant is shown as its date in India, not in UTC (I-11 / M-13 Received, SRS l.4200)', () => {
    // 18:29 UTC is 23:59 IST the same day; 18:30 UTC is already the next day in India.
    expect(dayOf('2026-10-21T18:29:00Z')).toBe('21 Oct 2026');
    expect(dayOf('2026-10-21T18:30:00Z')).toBe('22 Oct 2026');
    expect(dayOf('2026-10-21T00:00:00+05:30')).toBe('21 Oct 2026');
    expect(dayOf(null)).toBe('—');
    expect(dayOf('')).toBe('—');
    expect(dayOf('yesterday')).toBe('yesterday');
  });
});

describe('SRS-EH-1, SRS-EH-2: message severity and corrective action (US-GAP-22)', () => {
  it('SRS-EH-2: a refusal shows the corrective action of its status, with the severity held against the message', () => {
    render(
      <I18nProvider>
        <Problem
          error={
            new ApiError(
              403,
              {
                errorCode: 'AUTH-403-SCOPE',
                severity: 'SECURITY',
                actionKey: 'error.AUTH-403-SCOPE.action',
              },
              'c-1',
            )
          }
        />
      </I18nProvider>,
    );
    const alert = screen.getByRole('alert');
    expect(alert.getAttribute('data-severity')).toBe('SECURITY');
    expect(alert.textContent).toContain(t('error.action.label'));
    expect(alert.textContent).toContain(t('error.action.403'));
    expect(alert.textContent).toContain('c-1');
  });

  it('SRS-EH-2: the own action of a message wins over its status; none for a status without one or a network failure', () => {
    const own = new ApiError(422, { actionKey: 'error.action.409' }, undefined);
    expect(correctiveAction(translate, own)).toBe(t('error.action.409'));
    expect(correctiveAction(translate, new ApiError(422, {}, undefined))).toBe(t('error.action.422'));
    expect(
      correctiveAction(translate, new ApiError(422, { actionKey: 'error.none.action' }, undefined)),
    ).toBe(t('error.action.422'));
    expect(correctiveAction(translate, new ApiError(418, {}, undefined))).toBeNull();
    expect(correctiveAction(translate, new ApiError(0, {}, undefined))).toBeNull();
    expect(new ApiError(409, { severity: 7 }, undefined).severity).toBeUndefined();
    expect(new ApiError(409, { actionKey: 7 }, undefined).actionKey).toBeUndefined();
  });

  it('SRS-EH-3: a failure that never reached the server shows no corrective action', () => {
    render(
      <I18nProvider>
        <Problem error={new Error('boom')} />
      </I18nProvider>,
    );
    expect(screen.getByRole('alert').textContent).not.toContain(t('error.action.label'));
  });
});

describe('API client problems', () => {
  it('a problem without a title, code or message key still reads: network for status 0, unexpected otherwise', () => {
    expect(new ApiError(0, {}, undefined).messageKey).toBe('error.network');
    const bare = new ApiError(502, {}, 'c-9');
    expect(bare.message).toBe('HTTP 502');
    expect(bare.messageKey).toBe('error.unexpected');
    expect(bare.correlationId).toBe('c-9');
    expect(
      new ApiError(428, { title: 'OTP', errorCode: 'AUTH-428-OTP', args: ['7'] }, undefined).message,
    ).toBe('OTP');
    // A challenge id that is no positive integer is no challenge.
    expect(
      new ApiError(428, { errorCode: 'AUTH-428-OTP', args: ['x'] }, undefined).otpChallengeId,
    ).toBeNull();
    expect(
      new ApiError(428, { errorCode: 'AUTH-428-OTP', args: ['0'] }, undefined).otpChallengeId,
    ).toBeNull();
    expect(new ApiError(428, { errorCode: 'AUTH-428-OTP', args: [] }, undefined).otpChallengeId).toBeNull();
  });

  it('SRS-G-48: a step-up refusal is retried once the handler verified, and stands when it declines, is absent or opted out', async () => {
    const refused = { errorCode: 'AUTH-403-STEP-UP', messageKey: 'error.AUTH-403-STEP-UP', args: [] };
    let calls = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        calls += 1;
        return calls === 1
          ? new Response(JSON.stringify(refused), { status: 403 })
          : new Response(JSON.stringify({ ok: true }), { status: 200 });
      }),
    );
    onStepUpRequired(async () => true);
    expect(await api('/v1/secret')).toEqual({ ok: true });
    expect(calls).toBe(2);

    calls = 0;
    onStepUpRequired(async () => false);
    await expect(api('/v1/secret')).rejects.toMatchObject({ code: 'AUTH-403-STEP-UP' });
    expect(calls).toBe(1);

    calls = 0;
    onStepUpRequired(async () => true);
    await expect(api('/v1/secret', { noStepUp: true })).rejects.toMatchObject({ code: 'AUTH-403-STEP-UP' });
    expect(calls).toBe(1);

    calls = 0;
    onStepUpRequired(null);
    await expect(api('/v1/secret')).rejects.toMatchObject({ code: 'AUTH-403-STEP-UP' });
    expect(calls).toBe(1);
  });
});
