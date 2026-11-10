#!/usr/bin/env python3
"""Deep profile of one module on the local stack B (owner 2026-10-27): load + JFR (CPU, memory, I/O) together.

For the module it:
  1. finds THIS checkout's online Tomcat (catalina.base under scripts/local/runtime-b/tomcat-online; the other
     checkout's JVMs are never touched),
  2. starts a JFR recording with scripts/perf/io-profile.jfc (the JDK "profile" settings with every socket and
     file read / write recorded, threshold 0 ms, so the database time over the H2 TCP connection is visible),
  3. runs scripts/perf/load-module.mjs for the module,
  4. stops the recording and writes docs/perf/module-<NN>-jfr.txt: the JDK views hot-methods, allocation-by-class,
     gc-pauses, gc, cpu-load, socket-reads-by-host, socket-writes-by-host, file-reads-by-path, file-writes-by-path,
     exception-count, thread-cpu-load; and a summary of the time spent in database reads (the H2 TCP port) per
     request, printed and kept in docs/perf/module-<NN>-io.json.

Usage: python scripts/perf/deep-profile.py --module 09 [--concurrency 25] [--iterations 20] [--think 20]
"""
import argparse
import json
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
JDK = pathlib.Path(r"C:\Program Files\Java\jdk-25.0.4.1\bin")
RUNTIME = ROOT / "scripts" / "local" / "runtime-b"
DB_PORT = 9392  # stack B (port offset 300) H2 TCP server
VIEWS = [
    "hot-methods", "cpu-time-hot-methods", "allocation-by-class", "allocation-by-site", "gc-pauses", "gc",
    "cpu-load", "thread-cpu-load", "socket-reads-by-host", "socket-writes-by-host", "file-reads-by-path",
    "file-writes-by-path", "exception-count", "exception-by-type", "contention-by-site",
]


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", **kw)


def online_pid():
    """The pid of this checkout's online Tomcat (by its catalina.base), never another JVM."""
    listing = run([str(JDK / "jcmd"), "-l"]).stdout.splitlines()
    want = str(RUNTIME / "tomcat-online").replace("\\", "/").lower()
    for line in listing:
        pid = line.split(" ", 1)[0]
        if not pid.isdigit() or "Bootstrap" not in line:
            continue
        props = run([str(JDK / "jcmd"), pid, "VM.system_properties"]).stdout.replace("\\\\", "/").replace("\\:", ":")
        for p in props.splitlines():
            if p.startswith("catalina.base=") and p.split("=", 1)[1].replace("\\", "/").lower().rstrip("/") == want:
                return pid
    sys.exit("online Tomcat of stack B not found (deploy it first)")


def h2_pid():
    """The pid of this stack's H2 TCP server (by its port), or None."""
    for line in run([str(JDK / "jcmd"), "-l"]).stdout.splitlines():
        pid = line.split(" ", 1)[0]
        if pid.isdigit() and "org.h2.tools.Server" in line and f"-tcpPort {DB_PORT}" in line:
            return pid
    return None


def db_io(recording):
    """Database I/O: socket reads and writes on the H2 TCP port, from the recorded events."""
    out = run([str(JDK / "jfr"), "print", "--json", "--events", "jdk.SocketRead,jdk.SocketWrite", str(recording)])
    events = json.loads(out.stdout or "{}").get("recording", {}).get("events", [])
    totals = {"jdk.SocketRead": [0, 0, 0.0], "jdk.SocketWrite": [0, 0, 0.0]}
    other = {"jdk.SocketRead": [0, 0, 0.0], "jdk.SocketWrite": [0, 0, 0.0]}
    for e in events:
        v = e.get("values", {})
        bucket = totals if v.get("port") == DB_PORT else other
        t = bucket[e["type"]]
        t[0] += 1
        t[1] += int(v.get("bytesRead", v.get("bytesWritten", 0)) or 0)
        dur = v.get("duration", "PT0S")
        t[2] += parse_duration(dur)
    shape = lambda b: {k.split(".")[1]: {"events": n, "bytes": by, "seconds": round(s, 3)} for k, (n, by, s) in b.items()}
    return {"database": shape(totals), "otherSockets": shape(other)}


def parse_duration(iso):
    """ISO-8601 duration as JFR prints it (PT0.000123S, PT1M2.3S) in seconds."""
    if not iso.startswith("PT"):
        return 0.0
    s, num = 0.0, ""
    for ch in iso[2:]:
        if ch.isdigit() or ch == ".":
            num += ch
        else:
            s += float(num or 0) * {"H": 3600, "M": 60, "S": 1}.get(ch, 0)
            num = ""
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--module", required=True)
    ap.add_argument("--concurrency", default="25")
    ap.add_argument("--iterations", default="20")
    ap.add_argument("--think", default="20")
    ap.add_argument("--base", default="https://localhost:8743")
    ap.add_argument("--desks", default="makers", help="makers or checkers (the region desks of the volume set)")
    a = ap.parse_args()
    out_dir = ROOT / "docs" / "perf"
    rec = RUNTIME / "logs" / f"deep-{a.module}.jfr"
    pid = online_pid()
    name = f"deep{a.module}"
    run([str(JDK / "jcmd"), pid, "JFR.stop", f"name={name}"])  # a leftover of an interrupted run
    started = run([str(JDK / "jcmd"), pid, "JFR.start", f"name={name}",
                   f"settings={ROOT / 'scripts' / 'perf' / 'io-profile.jfc'}", f"filename={rec}"])
    if started.returncode != 0:
        sys.exit(started.stdout + started.stderr)
    # The database's own CPU (the H2 server is a separate JVM): its hot methods show which SQL work costs.
    db_pid = h2_pid()
    db_rec = RUNTIME / "logs" / f"deep-{a.module}-h2.jfr"
    if db_pid:
        run([str(JDK / "jcmd"), db_pid, "JFR.stop", f"name={name}"])
        run([str(JDK / "jcmd"), db_pid, "JFR.start", f"name={name}", "settings=profile", f"filename={db_rec}"])
    t0 = time.time()
    load = run(["node", str(ROOT / "scripts" / "perf" / "load-module.mjs"), "--module", a.module, "--base", a.base,
                "--concurrency", a.concurrency, "--iterations", a.iterations, "--think", a.think, "--desks", a.desks,
                "--log", str(RUNTIME / "logs" / "online" / "api.json")])
    seconds = time.time() - t0
    run([str(JDK / "jcmd"), pid, "JFR.stop", f"name={name}", f"filename={rec}"])
    if db_pid:
        run([str(JDK / "jcmd"), db_pid, "JFR.stop", f"name={name}", f"filename={db_rec}"])
    print(load.stdout)
    if load.returncode != 0:
        print(load.stderr, file=sys.stderr)
    report = [f"# Module {a.module} deep profile: JFR views of the online instance during the load ({seconds:.0f} s)", ""]
    for view in VIEWS:
        r = run([str(JDK / "jfr"), "view", "--width", "180", view, str(rec)])
        report += [f"## {view}", "", r.stdout.strip() or r.stderr.strip(), ""]
    if db_pid and db_rec.exists():
        report += ["# The H2 database JVM during the same load", ""]
        for view in ("hot-methods", "cpu-load", "gc-pauses", "allocation-by-class"):
            r = run([str(JDK / "jfr"), "view", "--width", "180", view, str(db_rec)])
            report += [f"## h2 {view}", "", r.stdout.strip() or r.stderr.strip(), ""]
    (out_dir / f"module-{a.module}-jfr.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
    io = db_io(rec)
    io["loadSeconds"] = round(seconds, 1)
    try:
        lj = json.loads((out_dir / f"module-{a.module}-load.json").read_text(encoding="utf-8"))
        requests = sum(r["requests"] for r in lj["rows"])
        io["requests"] = requests
        db = io["database"]["SocketRead"]
        io["dbReadMsPerRequest"] = round(1000 * db["seconds"] / requests, 2) if requests else None
        io["dbRoundTripsPerRequest"] = round(db["events"] / requests, 1) if requests else None
    except (OSError, KeyError, ValueError):
        pass
    (out_dir / f"module-{a.module}-io.json").write_text(json.dumps(io, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(io, indent=2))
    sys.exit(load.returncode)


if __name__ == "__main__":
    main()
