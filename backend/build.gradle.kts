import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipFile
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("fci.spring-war")
    alias(libs.plugins.pitest)
}

description = "FCILekha Payroll backend: one WAR, two modes. Profile 'online' serves the browser " +
    "(session login, RBAC, employee master, maker-checker, audit); profile 'batch' runs the job " +
    "queue poller, Spring Batch jobs and scheduled jobs."

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.session.jdbc)
    implementation(libs.spring.boot.starter.batch.jdbc)
    // Circuit breaker and time limit around external calls of the batch jobs (HRMS interface pull).
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.timelimiter)
    // HRMS client (module 23): bounded concurrency and retry per interface, WebClient on a pooled Reactor Netty.
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.bulkhead)
    implementation(libs.spring.boot.starter.webclient)
    implementation(libs.reactor.netty.http)
    implementation(libs.spring.boot.starter.flyway)
    // Oracle 19c target (ADR-0019): Flyway's Oracle support is its own module.
    runtimeOnly(libs.flyway.database.oracle)
    // The Oracle JDBC driver and the wallet reader (TCPS to the Autonomous Database), profile oracle.
    runtimeOnly(libs.ojdbc17)
    runtimeOnly(libs.oraclepki)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.caffeine)
    // Module declarations (package-info @ApplicationModule / @NamedInterface); annotations only.
    implementation(libs.spring.modulith.api)
    implementation(libs.bucket4j.core)
    implementation(libs.mapstruct)
    implementation(libs.springdoc.webmvc.ui)
    implementation(libs.bouncycastle.prov)
    implementation(libs.poi.ooxml)
    implementation(libs.pdfbox)

    // Interim store (ADR-0019). Oracle replaces it at cut-over. compileOnly for the H2 ports of the
    // target DDL's Oracle triggers (platform.persistence.h2).
    compileOnly(libs.h2)
    runtimeOnly(libs.h2)

    // Chaos Monkey for Spring Boot (resilience runs on a local stack, profile chaos-monkey): only in
    // a WAR built with -Pfci.chaos=true, never in the release build (StartupGuards refuse the
    // profile in prod). Its jcl-over-slf4j clashes with spring-jcl.
    if (providers.gradleProperty("fci.chaos").orNull == "true") {
        implementation(libs.chaos.monkey.spring.boot) { exclude(group = "org.slf4j", module = "jcl-over-slf4j") }
        implementation(libs.spring.boot.restclient)
    }

    annotationProcessor(libs.mapstruct.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.starter.batch.jdbc.test)
    testImplementation(libs.archunit.junit5)
    // The trigger unit tests (platform.persistence.h2) compile against the H2 API.
    testImplementation(libs.h2)
}

// Versioned DB scripts live in the repository's db/ folder (one source for both profiles and for
// DBAs); they are packaged into the WAR's classpath as db/migration/** and db/seed/**.
val dbDir = rootProject.layout.projectDirectory.dir("db")

tasks.processResources {
    from(dbDir.dir("migration")) { into("db/migration") }
    from(dbDir.dir("seed")) { into("db/seed") }
    // The opt-in volume data set (profile volume; docs/perf/VOLUME_DATA.md).
    from(dbDir.dir("volume")) { into("db/volume") }
    // The opt-in edge-case data set (profile edge; docs/test-data/EDGE_DATA.md).
    from(dbDir.dir("edge")) { into("db/edge") }
    // The opt-in screen demo data set (profile screens, on top of demo; OD-141 D-screens, docs/db/INSTALL_KIT.md).
    from(dbDir.dir("demo-screens")) { into("db/demo-screens") }
    // The worker clones of the demo users (profile e2e-parallel, parallel Playwright lanes).
    from(dbDir.dir("e2e-parallel")) { into("db/e2e-parallel") }
}

// The RELEASE WAR (DEV, SIT, UAT, production; CI) holds NO H2 (owner 2026-11-01: the servers run on Oracle;
// H2 is only for local test runs and Playwright): no H2 jar, no H2 trigger ports or dialect, no H2 scripts.
// A local build adds H2 with -Pfci.h2=true (deploy-local.ps1 does). bootRun and the test suites keep H2.
val warWithH2 = providers.gradleProperty("fci.h2").map { it == "true" }.orElse(false)
val h2InWar = listOf(
    "WEB-INF/classes/in/gov/fci/payroll/platform/persistence/h2/**",
    "WEB-INF/classes/db/migration/h2/**",
    "WEB-INF/classes/db/edge/h2/**",
    "WEB-INF/classes/db/demo-screens/h2/**",
    "WEB-INF/classes/db/volume/h2/**",
    "WEB-INF/classes/db/e2e-parallel/h2/**",
)

// The release WAR's classes and resources: the main output without the H2 parts (BootWar applies no
// per-file filter to its classpath, so the classpath itself is filtered; Boot's jar index stays consistent).
val releaseClasses = tasks.register<Sync>("releaseClasses") {
    description = "The main classes and resources without the H2 trigger ports, dialect and scripts (release WAR)."
    from(sourceSets.main.get().output)
    exclude(h2InWar.map { it.removePrefix("WEB-INF/classes/") })
    into(layout.buildDirectory.dir("release-classes"))
}

tasks.bootWar {
    archiveFileName = "fci-payroll.war"
    if (!warWithH2.get()) {
        // The main output's directories as plain files (the configuration cache cannot store a SourceSetOutput).
        val output = sourceSets.main.get().output
        val mainDirs: Set<File> = output.classesDirs.files + listOfNotNull(output.resourcesDir)
        val dependencies = classpath!!.filter { f -> f !in mainDirs && !f.name.startsWith("h2-") }
        setClasspath(files(releaseClasses) + dependencies)
    }
    finalizedBy("verifyReleaseWar")
}

// Proof that a release WAR holds no H2 (runs after every bootWar; a local -Pfci.h2=true build only logs).
tasks.register("verifyReleaseWar") {
    description = "Fails when the release WAR contains H2 (the jar, the trigger ports or the H2 scripts)."
    val war = tasks.bootWar.flatMap { it.archiveFile }
    val local = warWithH2
    // Plain values only: the configuration cache cannot store references to the build script.
    val h2Prefixes: List<String> = h2InWar.map { it.removeSuffix("**") }
    inputs.file(war)
    doLast {
        val names = ZipFile(war.get().asFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        val h2 = names.filter { n ->
            (n.startsWith("WEB-INF/lib/h2-") && n.endsWith(".jar")) ||
                h2Prefixes.any { n.startsWith(it) }
        }
        if (!local.get() && h2.isNotEmpty()) {
            throw GradleException("the release WAR must not contain H2: ${h2.take(3)}")
        }
        logger.lifecycle(
            "fci-payroll.war: " + if (local.get()) "local build with H2 (-Pfci.h2=true)" else "release build, no H2",
        )
    }
}

// The shared H2 database runs as its own TCP server process so the online and the batch instance
// see the same data. Its classpath needs the H2 ports of the Oracle triggers: this jar.
val h2Triggers = tasks.register<Jar>("h2Triggers") {
    description = "Jar of the H2 trigger classes for the standalone H2 TCP server."
    archiveFileName = "fci-h2-triggers.jar"
    from(sourceSets.main.get().output) {
        include("in/gov/fci/payroll/platform/persistence/h2/**")
    }
}

tasks.assemble {
    dependsOn(h2Triggers)
}

// ------------------------------------------------------------------ local run and debug
// The same WAR code as two processes, as deployed (online + batch on one shared H2 TCP server), from
// Gradle or the IDE with the embedded Tomcat. In three terminals (or the IDE configurations in
// .vscode/ and .run/):
//   ./gradlew :backend:h2Server                    H2 TCP server, tcp://127.0.0.1:9092 (loopback)
//   ./gradlew :backend:bootRunOnline -Pdebug       http://127.0.0.1:8180/api   JDWP 5005
//   ./gradlew :backend:bootRunBatch  -Pdebug       http://127.0.0.1:8190/api   JDWP 5006
// bootRunBatch waits until the online instance is ready (it migrates the schema; batch never does).
// -Pdebug opens the JDWP port without suspending; --debug-jvm suspends until a debugger attaches.
val localDir = layout.buildDirectory.dir("local")
val localSecretsDir = localDir.map { it.dir("secrets/backend") }
val h2Port = providers.gradleProperty("h2Port").orElse("9092")
// -Pfci.db=oracle: the two instances on Oracle (profile oracle, docs/db/ORACLE_LOCAL.md) instead of the
// shared H2 server; the wallet folder from -Pfci.oracle.wallet-dir or FCI_ORACLE_WALLET_DIR.
val dbProfile = providers.gradleProperty("fci.db").map { if (it == "oracle") "oracle,oracle-adb" else "shared-h2" }.orElse("shared-h2")
val oracleWalletDir = providers.gradleProperty("fci.oracle.wallet-dir").orElse(providers.environmentVariable("FCI_ORACLE_WALLET_DIR"))
// -Pfci.data=edge | volume | screens: the data set the database holds (an Oracle schema keeps it: deploy it with its set);
// -Pfci.oracle.user: the schema owner (FCI_PAYROLL edge cases, FCI_PAYROLL_VOL volume; docs/db/ORACLE_LOCAL.md).
val dataProfile = providers.gradleProperty("fci.data").map { if (it == "edge" || it == "volume" || it == "screens") ",$it" else "" }.orElse("")
val oracleUser = providers.gradleProperty("fci.oracle.user").orElse("FCI_PAYROLL")

// Keys and passwords shared by both local processes (scripts/GenerateSecrets.java, once).
val localSecrets = tasks.register<Exec>("localSecrets") {
    description = "Generates the local secrets (configtree) shared by the online and batch processes."
    group = "fci local"
    val out = localDir.get().asFile
    onlyIf { !out.resolve("secrets/backend/fci.api.secrets.data-encryption-key").exists() }
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(libs.versions.java.get()) }
    executable = launcher.get().executablePath.asFile.path
    args(rootProject.file("scripts/GenerateSecrets.java").path, "--out", out.path)
}

tasks.register<JavaExec>("h2Server") {
    description = "Runs the shared H2 TCP server (in-memory, loopback only) for bootRunOnline/bootRunBatch."
    group = "fci local"
    dependsOn(localSecrets)
    // The H2 jar and the H2 ports of the Oracle triggers (platform.persistence.h2).
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.h2.tools.Server"
    args("-tcp", "-tcpPort", h2Port.get(), "-ifNotExists")
}

fun BootRun.fciInstance(mode: String, port: Int, debugPort: Int) {
    description = "Runs the backend as the $mode instance (profiles local,demo,shared-h2|oracle,$mode) on port $port."
    group = "fci local"
    dependsOn(localSecrets)
    mainClass = "in.gov.fci.payroll.FciPayrollApplication"
    classpath = tasks.bootRun.get().classpath
    args("--spring.profiles.active=local,demo${dataProfile.get()},${dbProfile.get()},$mode", "--server.port=$port")
    // oracle-adb: the cloud Autonomous Database through its wallet, local machines only (StartupGuards).
    if (dbProfile.get().startsWith("oracle")) {
        val wallet = oracleWalletDir.orNull
            ?: throw GradleException("-Pfci.db=oracle needs -Pfci.oracle.wallet-dir=<wallet folder> or FCI_ORACLE_WALLET_DIR")
        systemProperty("fci.oracle.wallet-dir", file(wallet).invariantSeparatorsPath)
        systemProperty("fci.oracle.username", oracleUser.get())
        val secrets = if (oracleUser.get() == "FCI_PAYROLL_VOL") "secrets/oracle-vol" else "secrets/oracle"
        systemProperty("fci.oracle.secrets-dir", rootProject.file(secrets).invariantSeparatorsPath)
    }
    systemProperty("fci.local.secrets-dir", localSecretsDir.get().asFile.invariantSeparatorsPath)
    systemProperty("fci.local.h2-port", h2Port.get())
    // <name>.json and <name>-security-audit.json of this process: api with profile online, batch with
    // profile batch, app otherwise (fci.logging.file-name; git-ignored build output).
    systemProperty("fci.logging.dir", localDir.get().dir("logs/$mode").asFile.invariantSeparatorsPath)
    jvmArgs("-Duser.timezone=UTC", "-Dfile.encoding=UTF-8")
    debugOptions {
        enabled = providers.gradleProperty("debug").isPresent
        this.port = debugPort
        server = true
        suspend = false
    }
}

tasks.register<BootRun>("bootRunOnline") {
    fciInstance("online", 8180, 5005)
}

tasks.register<BootRun>("bootRunBatch") {
    fciInstance("batch", 8190, 5006)
    // The online instance migrates the schema (Flyway); batch validates it, so it starts second.
    val readiness = "http://127.0.0.1:8180/api/actuator/health/readiness"
    doFirst {
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (true) {
            val up = runCatching {
                (URI(readiness).toURL().openConnection() as HttpURLConnection)
                    .apply { connectTimeout = 2000; readTimeout = 2000 }
                    .responseCode == 200
            }.getOrDefault(false)
            if (up) break
            if (System.currentTimeMillis() > deadline) throw GradleException("online instance not ready at $readiness")
            logger.lifecycle("waiting for the online instance ($readiness) ...")
            Thread.sleep(3000)
        }
    }
}

// Tests of the batch module (unit + @SpringBatchTest job tests + duplicate-guard integration test):
//   ./gradlew :backend:batchTest
// A suite of its own, so it compiles and runs independently of the legacy unit/integration suites.
@Suppress("UnstableApiUsage")
testing {
    suites {
        register<JvmTestSuite>("batchTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(platform(libs.spring.boot.bom))
                implementation(libs.spring.boot.starter.test)
                implementation(libs.spring.batch.test)
                // Test-only H2 triggers (e.g. a simulated outage) compile against the H2 API.
                implementation(libs.h2)
            }
            targets.configureEach {
                testTask.configure {
                    // Like integrationTest (build-logic): few forks, each reusing its Spring contexts.
                    maxParallelForks =
                        providers.gradleProperty("fci.test.forks.batch").map { it.toInt() }
                            .orElse((Runtime.getRuntime().availableProcessors() / 6).coerceAtLeast(1)).get()
                    // As the integration forks (build-logic): an explicit heap, at most 4 cached contexts.
                    maxHeapSize = providers.gradleProperty("fci.test.heap.batch").orElse("2g").get()
                    systemProperty("spring.test.context.cache.maxSize", "4")
                }
            }
        }
        // Module boundaries (Spring Modulith) and layering (ArchUnit) of the modular monolith:
        //   ./gradlew :backend:architectureTest     (part of `check`)
        register<JvmTestSuite>("architectureTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(platform(libs.spring.boot.bom))
                implementation(libs.spring.boot.starter.test)
                implementation(libs.spring.modulith.core)
                implementation(libs.spring.modulith.docs)
                implementation(libs.archunit.junit5)
            }
            targets.configureEach {
                testTask.configure {
                    // Module documentation (PlantUML component diagrams + module canvases).
                    systemProperty("fci.modules.docs", layout.buildDirectory.dir("modules").get().asFile.path)
                }
            }
        }
    }
}

tasks.named("check") { dependsOn("architectureTest") }

// The GitHub Actions gate (project owner, 2026-10-25): no test suite runs in CI - no unit,
// integration, batch or architecture tests, no PIT. They run on a developer machine when asked
// (./gradlew :backend:check, :backend:pitest). CI still proves the code is formatted, compiles
// with -Werror in every source set (the tests included, so none rots unnoticed) and passes
// SpotBugs / FindSecBugs.
tasks.register("staticCheck") {
    group = "verification"
    description = "Spotless, compile of every source set (-Werror) and SpotBugs, without running tests."
    dependsOn(
        "spotlessCheck",
        "compileTestJava",
        "compileIntegrationTestJava",
        "compileBatchTestJava",
        "compileArchitectureTestJava",
        "spotbugsMain",
    )
}

// Like the standard test suites: the batch tests see everything the backend itself uses.
configurations.named("batchTestImplementation") { extendsFrom(configurations.implementation.get()) }
configurations.named("batchTestRuntimeOnly") { extendsFrom(configurations.runtimeOnly.get()) }

// Coverage of every suite: the convention plugin collects test + integrationTest; the batch jobs are
// exercised only by batchTest, so its execution data joins the report and the verification.
listOf(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification).forEach { task ->
    task.configure {
        dependsOn(tasks.named("batchTest"))
        executionData.from(layout.buildDirectory.file("jacoco/batchTest.exec").map { f -> files(f).filter { it.exists() } })
    }
}

// Mutation testing (PIT) of the business rules: every *.business.* class (models, rules, validators)
// is mutated and the unit tests must kill the mutants. Orchestration, web and persistence are
// exercised by the integration suites, which are too slow to run once per mutant. Not part of
// "check" (minutes, not seconds); run it with ./gradlew :backend:pitest, report in
// build/reports/pitest/index.html. The threshold is the project owner's (gradle.properties).
pitest {
    pitestVersion = libs.versions.pitest.core.get()
    junit5PluginVersion = libs.versions.pitest.junit5.get()
    // While a module is built: -Pfci.pitest.targetClasses=in.gov.fci.payroll.cpf.business.* (comma
    // separated) mutates only its rules. The gate (CI, and before a merge) is the default, all of them.
    // PIT 1.30 has no free incremental history (moved to Arcmutate), so this is the quick rerun.
    targetClasses =
        providers.gradleProperty("fci.pitest.targetClasses").map { it.split(',').map(String::trim).toSet() }
            .orElse(setOf("in.gov.fci.payroll.*.business.*")).get()
    // -Pfci.pitest.targetTests=in.gov.fci.payroll.cpf.* (comma separated) runs only those unit tests
    // (scripts/quality/pit_shards.py, one module at a time); the default is every unit test.
    targetTests =
        providers.gradleProperty("fci.pitest.targetTests").map { it.split(',').map(String::trim).toSet() }
            .orElse(setOf("in.gov.fci.payroll.*")).get()
    excludedClasses = setOf("*.package-info", "*MapperImpl")
    // Per machine: -Pfci.pitest.threads=N (default: the cores, at most 4).
    threads =
        providers.gradleProperty("fci.pitest.threads").map { it.toInt() }
            .orElse(Runtime.getRuntime().availableProcessors().coerceIn(1, 4)).get()
    // A generous timeout (owner 2026-11-03): under load PIT's default (4 s + 1.25 x the normal run)
    // counted mutants as TIMED_OUT that a re-run killed or let survive; only a real loop may time out.
    timeoutConstInMillis =
        providers.gradleProperty("fci.pitest.timeout-ms").map { it.toInt() }.orElse(30000).get()
    timeoutFactor =
        providers.gradleProperty("fci.pitest.timeout-factor").getOrElse("4").toBigDecimal()
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    exportLineCoverage = true
    mutationThreshold = providers.gradleProperty("fci.pitest.min-mutation").map { it.toInt() }.orElse(0)
}

// Database install kit (docs/db/INSTALL_KIT.md): numbered drop / DDL / compile / data scripts per vendor,
// GENERATED from the Flyway end state (Flyway stays the schema of record; the kit is an extra deliverable).
//   ./gradlew :backend:installKit                                    db/install/h2 + db/install/oracle (fallback)
//   ./gradlew :backend:installKitOracle -Pfci.kit.mode=generate      db/install/oracle from a real Oracle build
//   ./gradlew :backend:installKitOracle -Pfci.kit.mode=check         Oracle equivalence check (scratch schema)
// The Oracle connection comes only from the FCI_KIT_ORACLE_* environment variables (OracleKitTool).
// The H2 proof (drop / DDL / data order, twice in a row, equivalence with Flyway) is an integration test:
//   FCI_INSTALL_KIT=1 ./gradlew :backend:integrationTest --tests "in.gov.fci.payroll.db.kit.*" --rerun
fun JavaExec.installKitTool(vararg toolArgs: String) {
    group = "database"
    classpath = sourceSets.named("integrationTest").get().runtimeClasspath
    mainClass = "in.gov.fci.payroll.db.kit.KitGenerator"
    workingDir = projectDir
    args("--db", rootProject.file("db").path, *toolArgs)
    // As the application's JVM: timestamps in UTC (docs/db/ORACLE_LOCAL.md section 4).
    jvmArgs("-Duser.timezone=UTC", "-Dfile.encoding=UTF-8")
}
tasks.register<JavaExec>("installKit") {
    description = "Generates the database install kit (db/install/h2 and the Oracle fallback) from the Flyway end state."
    installKitTool("--vendor", "all")
}
tasks.register<JavaExec>("installKitOracle") {
    description = "Oracle install kit from a Flyway-built scratch schema (generate) or the Oracle equivalence check (check)."
    installKitTool("--vendor", "oracle", "--mode", providers.gradleProperty("fci.kit.mode").orElse("generate").get())
}
