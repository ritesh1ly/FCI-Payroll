// Shared conventions for every FCI Java project: toolchain, Spring Boot BOM, compiler flags, test
// suites, coverage gates, formatting, SBOM and banned dependencies.

plugins {
    java
    jacoco
    id("com.diffplug.spotless")
    id("org.cyclonedx.bom")
    id("com.github.spotbugs")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()
fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

java {
    toolchain {
        // Baseline: JDK 25 LTS (any vendor; Gradle finds a local JDK 25 or JAVA_HOME pointing to one).
        languageVersion = JavaLanguageVersion.of(version("java"))
    }
}

// The Spring Boot BOM is a platform on every classpath, including annotation processors.
val springBootBom = dependencies.platform(lib("spring-boot-bom"))
dependencies {
    "implementation"(springBootBom)
    "compileOnly"(springBootBom)
    "annotationProcessor"(springBootBom)
    "testImplementation"(springBootBom)
    "testAnnotationProcessor"(springBootBom)

    // Banned artefacts (superseded, vulnerable or wrong namespace): resolution fails if anything requests them.
    constraints {
        listOf("log4j:log4j", "javax.servlet:javax.servlet-api", "junit:junit", "org.mongodb:mongodb-driver-sync")
            .forEach { banned ->
                "implementation"(banned) {
                    version { rejectAll() }
                    because("banned by FCI build policy (ADR-0002, ADR-0019)")
                }
            }
        // Security patches above the Spring Boot BOM (scripts/oss_advisory_check.py, 2026-10-22): each
        // version is at least two weeks old; drop an entry once the BOM manages the same or a newer one.
        mapOf(
            "tools.jackson.core:jackson-core" to "3.1.7", // CVE-2026-89425, CVE-2026-89407
            "tools.jackson.core:jackson-databind" to "3.1.7", // CVE-2026-68497, -91776, -91777, -83557, -19032
            "com.fasterxml.jackson.core:jackson-core" to "2.22.3", // same CVEs on the Jackson 2 line (springdoc)
            "com.fasterxml.jackson.core:jackson-databind" to "2.22.3",
            "org.apache.tomcat.embed:tomcat-embed-core" to "11.0.27", // CVE-2026-65182, -65905, -68525
            "org.apache.tomcat.embed:tomcat-embed-el" to "11.0.27",
            "org.apache.tomcat.embed:tomcat-embed-websocket" to "11.0.27",
            // Not a CVE: H2 2.4.240 binds a CHECK constraint to the session that created it, so once
            // Hikari retires Flyway's connection (max-lifetime 30 min) every CHECK fails with 23514
            // "Check constraint invalid ... database has been closed" and every audited write (sign-in
            // included) answers COM-409. Reproduced on a clean server; fixed in 2.5.252 (playbook).
            "com.h2database:h2" to "2.5.252",
        ).forEach { (module, patched) ->
            "implementation"(module) {
                version { require(patched) }
                because("published vulnerability or defect fixed in $patched (see the comment above)")
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = version("java").toInt()
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror", "-Xmaxerrs", "5000"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    // Reproducible archives: identical inputs give byte-identical WARs and JARs.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

@Suppress("UnstableApiUsage")
testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter()
        }
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
            }
            targets.configureEach {
                testTask.configure {
                    shouldRunAfter(tasks.named("test"))
                    maxParallelForks = testForks("integration", 6)
                    springContextForks(this, "integration")
                }
            }
        }
    }
}

configurations.named("integrationTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
configurations.named("integrationTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
configurations.named("integrationTestAnnotationProcessor") { extendsFrom(configurations.testAnnotationProcessor.get()) }

// Test JVMs per suite. The suites run at the same time (parallel tasks + configuration cache), and an
// integration fork spends most of its time starting a Spring context and migrating its own in-memory
// database: a few forks that each reuse their context beat many forks competing for the CPU.
// Override per machine: -Pfci.test.forks.unit=N / .integration=N / .batch=N (CI runners: 1 or 2).
val cores = Runtime.getRuntime().availableProcessors()
fun testForks(suite: String, share: Int): Int =
    providers.gradleProperty("fci.test.forks.$suite").map { it.toInt() }.orElse((cores / share).coerceAtLeast(1)).get()

// A fork that starts Spring contexts (integration and batch suites). The heap is explicit, so a CI
// runner and a developer machine behave alike (the JVM default is a quarter of the machine's RAM:
// the first GitHub run, 2026-10-25, ran one fork with all 411 ITs and died of "Java heap space").
// Spring keeps at most 4 contexts (default 32): every class with its own profiles or properties is
// a full Boot application with its own migrated, seeded in-memory H2 database; the shared
// ApiTestSupport context stays hot because fewer than four others ever run between its classes.
// A closed context's in-memory database is shut down with it (ItDatabaseShutdown; the URLs keep
// DB_CLOSE_DELAY=-1, which start-up needs).
// Override per machine: -Pfci.test.heap.integration=3g / .batch=3g.
fun springContextForks(task: Test, suite: String) {
    task.maxHeapSize = providers.gradleProperty("fci.test.heap.$suite").orElse("2g").get()
    task.systemProperty("spring.test.context.cache.maxSize", "4")
}

tasks.withType<Test>().configureEach {
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Duser.timezone=UTC", "-Dfile.encoding=UTF-8")
    systemProperty("user.language", "en")
    maxParallelForks = testForks("unit", 4)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named("check") { dependsOn(tasks.named("integrationTest")) }

jacoco {
    toolVersion = version("jacoco")
}

val coverageData = files(
    layout.buildDirectory.file("jacoco/test.exec"),
    layout.buildDirectory.file("jacoco/integrationTest.exec"),
)

tasks.jacocoTestReport {
    dependsOn(tasks.named("test"), tasks.named("integrationTest"))
    executionData.setFrom(coverageData.filter { it.exists() })
    reports {
        xml.required = true // consumed by SonarQube
        html.required = true
    }
}

// Coverage hard gates (gradle.properties, set 2026-10-23 by the project owner; docs/quality/
// QUALITY_GATES.md): the whole project at fci.coverage.min-line / min-branch (90 %, recommendation
// above 95 %), and every critical class (fci.coverage.critical-classes) at 100 % line and branch.
val minLine = providers.gradleProperty("fci.coverage.min-line").orElse("0.90")
val minBranch = providers.gradleProperty("fci.coverage.min-branch").orElse("0.90")
val criticalClasses =
    providers.gradleProperty("fci.coverage.critical-classes")
        .map { list -> list.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        .orElse(emptyList())

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    executionData.setFrom(coverageData.filter { it.exists() })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = minLine.get().toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = minBranch.get().toBigDecimal()
            }
        }
        // An empty include list would mean every class: the rule exists only with a list.
        if (criticalClasses.get().isNotEmpty()) {
            rule {
                element = "CLASS"
                includes = criticalClasses.get()
                limit {
                    counter = "LINE"
                    minimum = "1.0000".toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    minimum = "1.0000".toBigDecimal()
                }
            }
        }
    }
}

tasks.named("check") { dependsOn(tasks.jacocoTestCoverageVerification) }

spotless {
    java {
        target("src/*/java/**/*.java")
        // Version left to Spotless: it pins the google-java-format release it is tested with.
        googleJavaFormat().aosp().reflowLongStrings()
        removeUnusedImports()
        importOrder()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs = listOf("runtimeClasspath")
}

// SAST: SpotBugs with the FindSecBugs security detectors on production code (./gradlew spotbugsMain;
// reports in build/reports/spotbugs: HTML for people, SARIF for code scanning / Sonar). Findings that
// were reviewed and accepted are listed with their reason in config/spotbugs/exclude.xml; anything
// else fails the build (part of check).
spotbugs {
    toolVersion = version("spotbugs")
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.MEDIUM
    excludeFilter = rootProject.file("config/spotbugs/exclude.xml")
}

dependencies {
    "spotbugsPlugins"(lib("findsecbugs-plugin"))
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required = true }
    reports.create("sarif") { required = true }
}

// Tests are not production code: analyse main only.
tasks.matching { it.name.startsWith("spotbugs") && it.name != "spotbugsMain" }.configureEach { enabled = false }
