// Times a few GET calls one at a time for one desk (no concurrency): separates a request's own cost from
// contention under load. Usage: node scripts/perf/time-calls.mjs <login> <office> <role> <path> [<path> ...]
import { signIn } from './load-module.mjs';

const [login, office, role, ...paths] = process.argv.slice(2);
const session = await signIn(login, office, role);
for (const path of paths) {
  const ms = [];
  let status = 0;
  for (let i = 0; i < 12; i++) {
    const t = performance.now();
    const r = await session.call('GET', path);
    await r.text();
    status = r.status;
    ms.push(performance.now() - t);
  }
  ms.sort((a, b) => a - b);
  console.log(`${status} median ${Math.round(ms[6])} ms min ${Math.round(ms[0])} ms  ${path}`);
}
