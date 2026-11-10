# config/

Everything needed to run FCILekha Payroll on a server, outside a developer's IDE. Procedures are in
the [operations runbook](../docs/operations/runbook.md); decisions in ADR-0010, 0013, 0016 and 0019.

## Runtime topology (one host; DEV, UAT and production on Oracle)

```
browser ──TLS──> NGINX :443 ──"/"──────> Next.js server 127.0.0.1:3000  (SSR, node server.js)
                           └─"/api/"──> Tomcat "online" 127.0.0.1:8080  (fci-payroll.war, profile online)
                                                 │                 ▲
                                                 │ JDBC            │ server-side calls (BACKEND_URL)
                                                 ▼                 │
                     Oracle 19c+ on premises (fci.oracle.*)             <── JDBC ── Tomcat "batch" 127.0.0.1:8090
                                                                              (same WAR, profile batch)
```

* **One WAR, two modes.** `fci-payroll.war` is deployed to two Tomcat instances (one `CATALINA_HOME`,
  two `CATALINA_BASE`s). The mode is the Spring profile `online` or `batch` (`FCI_MODE`); the app
  refuses to start with neither or both.
* **Job hand-off through the DB.** A job queued from the UI is a `QUEUED` row in `batch_job_request`
  written by `online`; the `batch` instance's poller claims and runs it (Spring Batch).
* **No scheduler on online.** `@EnableScheduling` exists only in the batch profile; Spring Session's
  cleanup task is disabled (`cleanup-cron: "-"`) and done by the batch housekeeping job instead.
  Verify with `/api/actuator/scheduledtasks` on each management port (metrics credentials).
* **Security in Spring Security filters**, no API gateway: session cookie + CSRF checks, session
  policy and API access logging run in the online instance's filter chain. NGINX adds TLS, flood
  limits and headers; the batch instance has no route from NGINX.
* Start order: `fci-tomcat@online` (Flyway migrates the Oracle schema) → `fci-tomcat@batch` → `fci-frontend`.

## Files

| Path | Installed as | Purpose |
|---|---|---|
| `tomcat/conf/server.xml` | `$CATALINA_BASE/conf/server.xml` (both instances) | Hardened Tomcat 11.0.27 (JDK 25, virtual-thread requests): loopback connectors from `fci.http.port`/`fci.mgmt.port`, no shutdown port, no AJP, empty `appBase` |
| `tomcat/conf/Catalina/localhost/api.xml` | `$CATALINA_BASE/conf/Catalina/localhost/` (both) | Context `/api`: WAR location, profiles `${fci.profile},${fci.mode}`, shared config, client-IP valve |
| `tomcat/bin/setenv.sh`, `setenv.bat` | `$CATALINA_BASE/bin/` (both) | JVM options and the non-secret `fci.*` system properties, incl. `fci.mode` |
| `app/backend/application.yaml` | `/etc/fci/backend/` | Per-environment settings shared by both instances (the on-premises Oracle `fci.oracle.*`, SMTP, log dir); imports the secrets. Servers never run H2 (owner 2026-11-01) |
| `env/tomcat-online.env.example`, `env/tomcat-batch.env.example` | `/etc/fci/tomcat-{online,batch}.env` | Mode, profile, directories, ports, heap (systemd `EnvironmentFile`) |
| `systemd/fci-tomcat@.service` (+ `fci-tomcat@batch.service.d/`) | `/etc/systemd/system/` | Templated Tomcat unit, `fci-tomcat@online` and `fci-tomcat@batch`; batch ordered after online |
| `systemd/fci-frontend.service` | `/etc/systemd/system/` | Next.js standalone server (SSR) on 127.0.0.1:3000 |
| `nginx/nginx.conf` | `/etc/nginx/nginx.conf` | Limits, JSON access log, correlation id, rate-limit zones |
| `nginx/conf.d/`, `nginx/snippets/` | `/etc/nginx/fci/{conf.d,snippets}/` | Site (`/` → Next.js, `/api/` → online), TLS, security headers |
| `logrotate/fci-nginx` | `/etc/logrotate.d/` | NGINX logs kept 180 days (CERT-In) |

Related, outside this folder: `../scripts/GenerateSecrets.java` (every key, password and hash, in
the configtree layout `secrets/backend/`), `../scripts/install-release.sh` (installs a CI bundle,
switches atomically, smoke-tests, rolls back), `../scripts/smoke-test.sh` (read-only checks incl. the
scheduler split) and
`../scripts/local/deploy-local.ps1` (the same topology on a Windows PC). Database scripts and their
rollbacks are in `../db/`.
