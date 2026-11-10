import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';

/**
 * Database fixtures of the E2E suite, for data the application has no producer for yet (plan 05 D-1:
 * "tests that need a locked period create it through a fixture, never through a weakened rule").
 * Runs SQL through H2's own shell (org.h2.tools.Shell) against the local stack's H2 server:
 *
 *   E2E_DB_URL            jdbc:h2:tcp://127.0.0.1:<port>/mem:fcilekha;MODE=Oracle
 *   E2E_DB_PASSWORD_FILE  <runtime>/config/secrets/backend/spring.datasource.password
 *   E2E_DB_JAR            <runtime>/h2/h2-<version>.jar
 *
 * Never used by the application; a run without them skips the tests that need a fixture, with the reason.
 */
export function dbFixtureAvailable(): boolean {
  const { E2E_DB_URL, E2E_DB_PASSWORD_FILE, E2E_DB_JAR } = process.env;
  return Boolean(
    E2E_DB_URL &&
    E2E_DB_PASSWORD_FILE &&
    E2E_DB_JAR &&
    existsSync(E2E_DB_PASSWORD_FILE) &&
    existsSync(E2E_DB_JAR),
  );
}

export const NO_DB_FIXTURE =
  'Needs the database fixture (E2E_DB_URL, E2E_DB_PASSWORD_FILE, E2E_DB_JAR): no module produces this data yet.';

/** Runs SQL statements (separated by ";") and returns the shell's output (result rows as text). */
export function sql(statements: string): string {
  const url = process.env.E2E_DB_URL ?? '';
  const passwordFile = process.env.E2E_DB_PASSWORD_FILE ?? '';
  const jar = process.env.E2E_DB_JAR ?? '';
  const out = execFileSync(
    'java',
    [
      '-cp',
      jar,
      'org.h2.tools.Shell',
      '-url',
      url,
      '-user',
      'fci_payroll',
      '-password',
      readFileSync(passwordFile, 'utf8').trim(),
      '-sql',
      statements,
    ],
    { encoding: 'utf8' },
  );
  // H2's shell prints a failed statement as "Error: ..." and still exits 0: a fixture that did not
  // apply must fail the test, never let it go on against data it did not set up.
  const failed = /^Error: .*$/m.exec(out);
  if (failed) throw new Error(`fixture SQL failed: ${failed[0]}\n${statements}`);
  return out;
}

/**
 * The rows of one query as text cells (H2's shell prints a header line, `a | b` rows and a
 * "(n rows, …)" footer). Read-only use: an oracle the browser's answer is compared with.
 */
export function sqlRows(query: string): string[][] {
  const lines = sql(query)
    .split(/\r?\n/)
    .filter((l) => l.includes('|') || (l.trim() !== '' && !l.startsWith('(')));
  return lines.slice(1).map((l) => l.split('|').map((c) => c.trim()));
}

/**
 * Locks the VERIFIED cycle of {@code yymm} at {@code unitCode} as an approved lock would (status,
 * locker, every current Actual result and its lines immutable), for the specs that need a locked
 * period while the gates cannot yet be met on the demo data (plan 05 D-1). Forward-only: the
 * database trigger still refuses anything but VERIFIED -> LOCKED.
 */
export function lockForFixture(yymm: string, unitCode: string) {
  const cycle = `(SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = '${yymm}' AND org_unit_id = (SELECT location_id FROM org_location WHERE location_code = '${unitCode}'))`;
  sql(
    [
      `UPDATE payroll_cycle_instance SET cycle_status = 'LOCKED', locked_by = 'e2e.fixture', locked_at = CURRENT_TIMESTAMP, row_version = row_version + 1 WHERE cycle_instance_id = ${cycle} AND cycle_status = 'VERIFIED'`,
      `UPDATE payroll_result SET is_locked = 'Y' WHERE cycle_instance_id = ${cycle} AND run_type = 'ACTUAL' AND result_status = 'CURRENT' AND is_locked = 'N'`,
      `UPDATE payroll_result_component SET is_locked = 'Y' WHERE is_locked = 'N' AND payroll_result_id IN (SELECT payroll_result_id FROM payroll_result WHERE cycle_instance_id = ${cycle} AND is_locked = 'Y')`,
    ].join(';\n'),
  );
}

/**
 * Q-05-2 (SRS-15-33, plan 05 D-25): a cycle is initiated only when the prior period of its unit is locked, or
 * the unit has no earlier period. The specs initiate the current period of a shared demo unit on a stack
 * that other specs (and earlier runs) have already used, so before a UI initiation the prior month is
 * recorded LOCKED when it has no instance yet; a prior month another spec left unlocked is kept (the
 * refusal then shows). No-op without the database fixture.
 */
export function lockPriorForFixture(period: string, unitCode: string) {
  if (!dbFixtureAvailable()) return;
  const [year, month] = period.split('-').map(Number) as [number, number];
  const index = year * 12 + (month - 1) - 1;
  const prior = `${Math.floor(index / 12)}${String((index % 12) + 1).padStart(2, '0')}`;
  const fyStart = (index % 12) + 1 >= 4 ? Math.floor(index / 12) : Math.floor(index / 12) - 1;
  const finYear = `${fyStart}-${String((fyStart + 1) % 100).padStart(2, '0')}`;
  const unit = `(SELECT location_id FROM org_location WHERE location_code = '${unitCode}')`;
  sql(
    `INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level, fin_year, calendar_id, cycle_status, trigger_type, initiated_by) SELECT '${prior}', ${unit}, 'DO', '${finYear}', (SELECT MIN(calendar_id) FROM payroll_calendar WHERE payroll_yymm = '${prior}'), 'LOCKED', 'ON_DEMAND', 'e2e.prior.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM payroll_cycle_instance WHERE payroll_yymm = '${prior}' AND org_unit_id = ${unit})`,
  );
}
