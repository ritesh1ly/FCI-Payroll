// A Spring Boot application packaged as a WAR for an external Tomcat 11 (Servlet 6.1, ADR-0016).

plugins {
    war
    id("fci.java-conventions")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    // Tomcat is provided by the external container; the WAR must not bundle it. Only the Tomcat
    // jars themselves: everything reachable from a providedRuntime dependency goes to
    // WEB-INF/lib-provided, which an external container never loads. Declaring
    // spring-boot-starter-tomcat here moved spring-boot, spring-web and logback there too, and the
    // WAR could not start outside `bootRun`. bootRun still gets these jars (runtimeClasspath
    // extends providedRuntime).
    "providedRuntime"(platform(libs.findLibrary("spring-boot-bom").get()))
    "providedRuntime"(libs.findLibrary("tomcat-embed-core").get())
    "providedRuntime"(libs.findLibrary("tomcat-embed-el").get())
    "providedRuntime"(libs.findLibrary("tomcat-embed-websocket").get())
}

springBoot {
    // build/resources/main/META-INF/build-info.properties, exposed by /actuator/info.
    buildInfo {
        excludes = setOf("time") // keep the build reproducible
    }
}

// Only the Boot WAR is deployed; the plain WAR would lack the launcher layout and is disabled.
tasks.named<Jar>("war") { enabled = false }
