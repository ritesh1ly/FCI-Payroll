import { test } from '@playwright/test';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from './db';

/**
 * The employee links of the worker clones (e2e/README.md "Suites"), set before each step of a lane run.
 *
 * A clone shares its plain user's employee, so that a parallel worker signed in as emp.rsharma.w3 sees his
 * eSeva record. A sign-in by Employee ID accepts exactly one account (LoginService, OD-37), and
 * sign-in-methods.spec.ts, like every serial spec that resolves an employee's account, needs the plain user
 * alone: the serial lane unlinks the clones, the parallel lane links them again, so a re-run of a parallel
 * suite after the serial lane (npm run e2e:failed) finds the clones as deployed. Without the database fixture
 * both skip: a fresh stack has the clones linked, and the serial lane then needs a redeploy.
 */
test('the serial lane finds one account per employee number', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
  sql(`UPDATE app_user SET employee_id = NULL WHERE created_by = 'e2e-parallel' AND employee_id IS NOT NULL`);
});

test('the parallel lane links each clone to its plain user employee', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
  sql(
    `UPDATE app_user c SET employee_id = (SELECT p.employee_id FROM app_user p WHERE p.login_id = REGEXP_REPLACE(c.login_id, '[.]w[0-9]+@fci-demo[.]test$', '@fci-demo.test')) WHERE c.created_by = 'e2e-parallel' AND c.employee_id IS NULL`,
  );
});
