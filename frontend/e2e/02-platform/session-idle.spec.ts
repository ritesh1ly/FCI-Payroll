import { expect, test } from '@playwright/test';
import { apiProblem, SIGN_IN_PAGE, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';

/**
 * SRS-SEC-14 (review 2026-10-24, A6): the idle timeout as the user meets it. A payroll user's
 * session idles out after 15 minutes (an eSeva member's after 10; NavigationAndSessionIT proves the
 * values). The session store is the fixture: the session's last access is moved 16 minutes back, so
 * the next call finds it expired, answers 401 with the session-expired reason, and the workspace
 * returns to sign-in. Nothing else is touched; the spec re-runs on any stack with the fixture.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };

test.describe('Session idle timeout', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  test(
    'SRS-SEC-14: a session idle for more than 15 minutes is expired; the next request is refused and the workspace goes back to sign-in',
    { tag: ['@SRS-SEC-14', '@SRS-G-48', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'M-01');
      const me = await page.evaluate(async () => {
        const r = await fetch('/nginx/fci-payroll/auth/me', { credentials: 'same-origin' });
        return (await r.json()) as { session: { idleTimeoutSeconds: number } };
      });
      expect(me.session.idleTimeoutSeconds).toBe(900);

      // The session id travels only in an HttpOnly cookie. workAs may reuse the maker's session
      // cached by an earlier spec, so the newest session of the store can be another user's: take
      // the maker's own session (PRINCIPAL_NAME is the user id, WebSessions) that the /auth/me
      // above touched last (Spring Session JDBC, SPRING_SESSION).
      const sessionId = sqlRows(
        `SELECT SESSION_ID FROM SPRING_SESSION WHERE PRINCIPAL_NAME = (SELECT CAST(user_id AS VARCHAR(20)) FROM app_user WHERE login_id = '${MAKER}') ORDER BY LAST_ACCESS_TIME DESC FETCH FIRST 1 ROWS ONLY`,
      )[0]?.[0];
      expect(sessionId, 'the session in the store').toMatch(/^[0-9a-f-]{36}$/);
      const sixteenMinutesAgo = Date.now() - 16 * 60 * 1000;
      sql(
        `UPDATE SPRING_SESSION SET LAST_ACCESS_TIME = ${sixteenMinutesAgo} WHERE SESSION_ID = '${sessionId}'`,
      );

      const refused = await apiProblem(page, '/auth/me');
      expect(refused.status).toBe(401);
      expect(refused.errorCode).toBe('AUTH-401-SESSION-EXPIRED');
      await page.reload();
      await expect(page).toHaveURL(SIGN_IN_PAGE);
      await expect(
        page.getByRole('heading', { level: 1, name: new RegExp(t('en', 'screen.M-01')) }),
      ).toHaveCount(0);
    },
  );
});
