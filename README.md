# FCILekha Payroll

One repository: `frontend/` (Next.js, server-side rendered), `backend/` (Spring Boot 4 / JDK 25
WAR), `db/` (versioned Flyway scripts with rollbacks), `config/` (Tomcat, NGINX, systemd, app
config), `scripts/` (deploy, secrets, smoke test), `docs/`.

**Building a module?** Start with [CLAUDE.md](CLAUDE.md) and the [developer playbook](docs/engineering/DEVELOPER_PLAYBOOK.md):
the patterns to reuse, the test rules and the traps other module teams already hit. Add what you learn there.

## Build

```sh
./gradlew :backend:bootWar :backend:h2Triggers     # backend/build/libs/fci-payroll.war
(cd frontend && npm ci && npm run build)           # frontend/.next/standalone
```

## One WAR, two instances

The same WAR runs twice, as two processes on one database: profile `online` (REST API, login,
browser; migrates the schema) and profile `batch` (job-queue poller, Spring Batch jobs, schedules;
no REST API, no Flyway). Start order: database, online, batch. The backend is a modular monolith
(`in.gov.fci.payroll.<module>.<layer>`): see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

### Deployed: two Tomcat 11 instances (Windows, as on a server)

```powershell
powershell -File scripts\local\deploy-local.ps1             # build, deploy, smoke test
powershell -File scripts\local\deploy-local.ps1 -SkipBuild -Jpda   # debugger ports 8000 / 8001
powershell -File scripts\local\deploy-local.ps1 -Action status     # or -Action stop
```

H2 server on 9092, Tomcat `online` 8080 (management 8081), Tomcat `batch` 8090 (8091), Next.js
3000, NGINX https://localhost:8443/. With `-Jpda` attach the IDE to 127.0.0.1:8000 (online) or
127.0.0.1:8001 (batch): "Attach: Tomcat online/batch" in `.vscode/launch.json` and `.run/`.
Details: [config/README.md](config/README.md). Ports are indicative.

### From source: run and debug in the IDE or with Gradle

The same code with the embedded Tomcat, again as two processes on one shared H2 server (profile
`shared-h2`: shared database and shared keys, generated once into the git-ignored
`backend/build/local` by `:backend:localSecrets`):

```sh
./gradlew :backend:h2Server                  # tcp://127.0.0.1:9092, in-memory, loopback only
./gradlew :backend:bootRunOnline -Pdebug     # http://127.0.0.1:8180/api, JDWP 5005
./gradlew :backend:bootRunBatch  -Pdebug     # http://127.0.0.1:8190/api, JDWP 5006 (waits for online)
(cd frontend && npm run dev)                 # http://localhost:3000, /api -> 8180
```

`-Pdebug` opens the debugger port without suspending (`--debug-jvm` suspends until you attach);
`-Ph2Port=9093` runs beside a deployed stack that already uses 9092. Demo users: docs/rbac/SEED_USERS.md
(password `Fci@Demo2026`); OTP codes are in the online log.

* VS Code: Run and Debug → "Backend online", "Backend batch", "Frontend" or the compound "Full
  stack" (tasks in `.vscode/tasks.json` start H2 and the Gradle processes, then attach).
* IntelliJ IDEA: run configurations folder "FCI local" (`.run/`): Debug "1. H2 server",
  "2. Backend online", "3. Backend batch" (IntelliJ attaches to the Gradle-forked JVM), "4. Frontend",
  or the compound "FCI full stack".

### Modes

On Tomcat the mode comes from `FCI_MODE` (`config/env/tomcat-batch.env.example`, systemd unit
`fci-tomcat@batch`). Exactly one of `online` and `batch` must be active, or the application refuses
to start. Both instances must use  the same database. Start the database first, then online, then
batch.

The batch instance:

* loads `application-batch.yml`: every tunable is there (chunk and page size, partitions and
  threads, retry and skip limits, lock heartbeat, poll interval, restart window, schedules);
* polls `batch_job_request` every `fci.batch.poller.interval` and claims PENDING jobs with
  `SELECT … FOR UPDATE SKIP LOCKED`, so several batch nodes can run side by side;
* applies Stop / Pause / Resume / Restart commands recorded by the online API;
* fails executions abandoned by a crashed node at start-up and on every poll
  (`fci.batch.recovery.auto-restart` decides whether they restart on their own);
* pauses its running jobs on shutdown, so they can be resumed from the last commit.

Users submit and monitor jobs on screen **C-13 Batch Jobs**. Design, state diagram, recovery and
the full configuration reference: [docs/BATCH_DESIGN.md](docs/BATCH_DESIGN.md).

## Tests

```sh
./gradlew :backend:architectureTest   # module boundaries (Spring Modulith) and layers (ArchUnit)
./gradlew :backend:batchTest          # batch module: unit, @SpringBatchTest jobs, duplicate-run guard IT
(cd frontend && npm test)             # frontend unit tests with coverage
```
