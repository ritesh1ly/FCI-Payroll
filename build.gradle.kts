// Root project: aggregate-only tasks. Module configuration lives in the build-logic convention plugins.

plugins {
    alias(libs.plugins.dependency.check)
    alias(libs.plugins.sonarqube)
}

// OWASP Dependency-Check across every project: ./gradlew dependencyCheckAggregate (needs NVD_API_KEY).
dependencyCheck {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON", "SARIF")
    suppressionFile = "ci/dependency-check/suppressions.xml"
    nvd {
        apiKey = providers.environmentVariable("NVD_API_KEY").orNull
    }
}

// SonarQube (backend): ./gradlew :backend:check sonar -Dsonar.host.url=... with SONAR_TOKEN in the
// environment (never in a file). Coverage is the merged Jacoco report of all four test suites; the
// SpotBugs / FindSecBugs findings are imported as SARIF. The frontend is a project of its own
// (frontend/sonar-project.properties). Quality gate: docs/quality/QUALITY_GATES.md.
sonar {
    properties {
        property("sonar.projectKey", "fci-payroll-backend")
        property("sonar.projectName", "FCILekha Payroll - backend")
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.java.source", libs.versions.java.get())
    }
}

project(":backend") {
    sonar {
        properties {
            property(
                "sonar.coverage.jacoco.xmlReportPaths",
                layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.path,
            )
            property("sonar.sarifReportPaths", layout.buildDirectory.file("reports/spotbugs/main.sarif").get().asFile.path)
            property("sonar.junit.reportPaths", listOf("test", "integrationTest", "batchTest", "architectureTest")
                .joinToString(",") { layout.buildDirectory.dir("test-results/$it").get().asFile.path })
            // Flyway Java migrations and the H2 ports of the Oracle triggers are not application logic.
            property("sonar.coverage.exclusions", "**/persistence/V0*__*.java,**/platform/persistence/h2/**")
            property("sonar.cpd.exclusions", "**/persistence/V0*__*.java")
        }
    }
}
