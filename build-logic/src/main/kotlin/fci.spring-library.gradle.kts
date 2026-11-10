// A shared library consumed by the FCI Spring Boot applications (for example fci-common).

plugins {
    `java-library`
    id("fci.java-conventions")
}

java {
    withSourcesJar()
}
