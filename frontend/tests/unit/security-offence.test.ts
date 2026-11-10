import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, onPermissions, onUnauthorized, refusedGate } from '@/shared/api';
import { translate } from '@/shared/i18n';

function answer(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers });
}

describe('OD-130: the client never sends what the session may not, and says why it was signed out', () => {
  const fetchMock = vi.fn();
  const signedOut = vi.fn();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockReset();
    signedOut.mockReset();
    onUnauthorized(signedOut);
  });

  afterEach(() => {
    onUnauthorized(null);
    onPermissions(null);
    vi.unstubAllGlobals();
  });

  it('SRS_G_25_OD_130_a call the role cannot pass is refused here and never sent', async () => {
    onPermissions(() => ['M-01:VIEW']);

    const refused = await api('/v1/admin/users?page=0').catch((e: unknown) => e);

    expect(refused).toBeInstanceOf(ApiError);
    expect((refused as ApiError).status).toBe(403);
    expect((refused as ApiError).code).toBe('AUTHZ-403-PERMISSION');
    expect((refused as ApiError).args).toEqual(['A-01', 'VIEW']);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('OD_130_a call the role holds goes out', async () => {
    onPermissions(() => ['A-01:VIEW']);
    fetchMock.mockResolvedValue(answer(200, { items: [] }));

    await api('/v1/admin/users');

    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('OD_130_either permission of a gateEither passes it', () => {
    onPermissions(() => ['A-04:VIEW']);
    expect(refusedGate('GET', '/v1/admin/org')).toBeNull();
    onPermissions(() => ['M-01:VIEW']);
    expect(refusedGate('GET', '/v1/admin/org')).toEqual(['A-03', 'VIEW']);
  });

  it('OD_130_a literal route wins over one with a path value', () => {
    onPermissions(() => ['M-01:VIEW']);
    expect(refusedGate('GET', '/v1/rules/pending')).toEqual(['M-08', 'VIEW']);
  });

  it('OD_130_the method matters', () => {
    onPermissions(() => ['A-01:VIEW']);
    expect(refusedGate('GET', '/v1/admin/users/7/logins')).toBeNull();
    expect(refusedGate('POST', '/v1/admin/users/7/status')).toEqual(['A-01', 'EDIT']);
  });

  it('OD_130_an unknown route or an unknown session is left to the server', () => {
    onPermissions(() => ['M-01:VIEW']);
    expect(refusedGate('GET', '/v1/no/such/route')).toBeNull();
    expect(refusedGate('GET', '/auth/me')).toBeNull();
    onPermissions(() => []);
    expect(refusedGate('GET', '/v1/admin/users')).toBeNull();
    onPermissions(() => undefined);
    expect(refusedGate('GET', '/v1/admin/users')).toBeNull();
    onPermissions(null);
    expect(refusedGate('GET', '/v1/admin/users')).toBeNull();
  });

  it('OD_130_a refused response that ended the session goes to sign-in saying why', async () => {
    fetchMock.mockResolvedValue(
      answer(403, { errorCode: 'AUTHZ-403-PERMISSION' }, { 'X-FCI-Session-Ended': 'AUTH-401-OFFENCE' }),
    );

    await expect(api('/v1/anything')).rejects.toBeInstanceOf(ApiError);

    expect(signedOut).toHaveBeenCalledWith('offence');
  });

  it('OD_130_the 401 of the next call says the same, even for a silent call', async () => {
    fetchMock.mockResolvedValue(answer(401, { errorCode: 'AUTH-401-OFFENCE' }));

    await expect(api('/auth/me', { silent: true })).rejects.toBeInstanceOf(ApiError);

    expect(signedOut).toHaveBeenCalledWith('offence');
    expect(signedOut).toHaveBeenCalledOnce();
  });

  it('OD_130_an ordinary expiry stays an ordinary expiry', async () => {
    fetchMock.mockResolvedValue(answer(401, { errorCode: 'AUTH-401-SESSION-EXPIRED' }));

    await expect(api('/v1/anything')).rejects.toBeInstanceOf(ApiError);

    expect(signedOut).toHaveBeenCalledWith('expired');
  });

  it('OD_130_an ordinary refusal signs nobody out', async () => {
    fetchMock.mockResolvedValue(answer(403, { errorCode: 'AUTH-403-STEP-UP' }));

    await expect(api('/v1/anything', { noStepUp: true })).rejects.toBeInstanceOf(ApiError);

    expect(signedOut).not.toHaveBeenCalled();
  });

  it('OD_130_the message says why, in the words the owner asked for', () => {
    expect(translate('en', 'error.AUTH-401-OFFENCE')).toBe(
      'Your session was ended: an unauthorized access attempt was recorded.',
    );
  });
});
