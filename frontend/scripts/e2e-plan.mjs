// What runs when, from e2e/suites.json: shared by the manifest's validation and the runner (e2e/README.md "Suites").

/** Two suites may not run at once when one writes what the other uses. */
export function conflict(a, b) {
  const used = (s) => new Set([...s.reads, ...s.writes]);
  const usedA = used(a);
  const usedB = used(b);
  return a.writes.some((key) => usedB.has(key)) || b.writes.some((key) => usedA.has(key));
}

/** Parallel suites into waves: a wave holds no two that conflict; the longest suites are placed first. */
export function waves(suites) {
  const batches = [];
  for (const suite of [...suites].sort((a, b) => b.estimatedSeconds - a.estimatedSeconds)) {
    const home = batches.find((batch) => !batch.some((other) => conflict(other, suite)));
    if (home) home.push(suite);
    else batches.push([suite]);
  }
  return batches;
}

/** Suites in dependency order: each after what it depends on, otherwise as listed. */
export function ordered(suites) {
  const names = new Map(suites.map((s) => [s.name, s]));
  const done = new Set();
  const out = [];
  const visit = (suite) => {
    if (done.has(suite.name)) return;
    done.add(suite.name);
    for (const dep of suite.dependsOn) if (names.has(dep)) visit(names.get(dep));
    out.push(suite);
  };
  suites.forEach(visit);
  return out;
}

/** The selection with what its results rest on (dependsOn) and what rests on them (dependents). */
export function withRelated(manifest, names) {
  const byName = new Map(manifest.suites.map((s) => [s.name, s]));
  const chosen = new Set();
  const up = (name) => {
    if (chosen.has(name) || !byName.has(name)) return;
    chosen.add(name);
    byName.get(name).dependsOn.forEach(up);
  };
  const down = (name) => {
    up(name);
    for (const next of byName.get(name)?.dependents ?? []) down(next);
  };
  names.forEach(down);
  return manifest.suites.filter((s) => chosen.has(s.name));
}

/** Longest prefix of the impact map; a path it does not know maps to every suite. */
export function impactOf(manifest, file) {
  const path = file.replace(/\\/g, '/').replace(/^\.\//, '');
  const hit = manifest.impact.find(
    (e) => path === e.path || (e.path.endsWith('/') && path.startsWith(e.path)),
  );
  return hit ?? { path, suites: manifest.suites.map((s) => s.name), why: 'unknown path: safe default' };
}

/**
 * The steps of a run: clone suites in waves, then the serial suites one at a time in dependency order.
 * Each step is one Playwright process.
 */
export function plan(manifest, suites) {
  const inOrder = ordered(suites);
  const steps = [];
  const clones = inOrder.filter((s) => s.lane === 'parallel' && s.identity === 'clones');
  for (const batch of waves(clones)) steps.push({ kind: 'parallel', suites: batch });
  for (const suite of inOrder.filter((s) => s.lane === 'serial')) {
    steps.push({ kind: 'serial', suites: [suite] });
  }
  return steps;
}
