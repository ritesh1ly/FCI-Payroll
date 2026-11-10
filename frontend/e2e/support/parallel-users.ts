import { closeSync, fstatSync, openSync, readSync } from 'node:fs';

/**
 * Who a test is in a parallel lane (e2e/README.md "Parallel lanes"). Every demo user holds a single
 * session (fci.api.single-session), so no two workers may be the same user.
 *
 * - The lane `parallel`: worker n > 0 signs in as its clone, maker.delhi.wn@fci-demo.test, seeded only
 *   by the profile e2e-parallel (db/e2e-parallel/h2); worker 0 is the plain user. Specs keep writing
 *   the plain login.
 *
 * TEST_PARALLEL_INDEX, not the worker index: a worker replaced after a failure gets a new index, and
 * only the parallel index stays within 0 .. workers - 1, the clones that exist.
 * Every other run (`edge`, `edge-serial`) maps nothing.
 */
function clone(): number {
  return process.env.E2E_LANES === 'parallel' ? Number(process.env.TEST_PARALLEL_INDEX ?? 0) : 0;
}

/** The login a test signs in with: the plain one, or this worker's clone. */
export function laneLogin(loginId: string): string {
  const worker = clone();
  return worker > 0 ? loginId.replace(/@fci-demo\.test$/, `.w${worker}@fci-demo.test`) : loginId;
}

/**
 * A clone's registered mobile ends in 8<worker, two digits>7 (V990_1): the log masks all but the last
 * four digits, so that is what tells a worker its own OTP without any new log field. No plain demo
 * mobile ends that way.
 */
const CLONE_RECIPIENT = /to=\*{6}8(\d\d)7\b/;

/**
 * The OTP log lines of this test's own user. While no SMS gateway is connected a code is logged twice,
 * on the sms-log line and on an e-mail line (OtpService); the e-mail mask shows nothing of the worker,
 * so worker 0 leaves out every code that a clone's SMS line carries.
 */
export function ownOtpLines(lines: string[]): string[] {
  if (process.env.E2E_LANES !== 'parallel') return lines;
  const worker = clone();
  if (worker > 0) return lines.filter((line) => Number(CLONE_RECIPIENT.exec(line)?.[1]) === worker);
  const code = (line: string) => /devCode=(\d{6})/.exec(line)?.[1];
  const ofClones = new Set(lines.filter((line) => CLONE_RECIPIENT.test(line)).map(code));
  return lines.filter((line) => !ofClones.has(code(line)));
}

/** Enough of the log for a code written seconds ago, also with every worker logging. */
const LOG_TAIL_BYTES = 16 * 1024 * 1024;

/** The end of the log: the whole file would be read on every poll of every worker. */
export function readLogTail(path: string): string {
  const file = openSync(path, 'r');
  try {
    const size = fstatSync(file).size;
    const length = Math.min(size, LOG_TAIL_BYTES);
    const buffer = Buffer.alloc(length);
    readSync(file, buffer, 0, length, size - length);
    return buffer.toString('utf8');
  } finally {
    closeSync(file);
  }
}
