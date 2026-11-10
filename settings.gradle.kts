pluginManagement {
    // Convention plugins (fci.java-conventions, fci.spring-library, fci.spring-war).
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    // Provisions the Java 25 toolchain automatically on machines that do not have it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Repositories are declared once, here; a project declaring its own fails the build.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "fci-payroll"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// One deployable: the backend WAR (online and batch profiles). The frontend (Next.js) builds
// with npm in frontend/; db/ and config/ are plain folders.
include("backend")
