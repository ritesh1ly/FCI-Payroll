plugins {
    `kotlin-dsl`
}

dependencies {
    // Plugins applied by the precompiled convention scripts in src/main/kotlin.
    implementation(libs.gradle.plugin.spring.boot)
    implementation(libs.gradle.plugin.spotless)
    implementation(libs.gradle.plugin.cyclonedx)
    implementation(libs.gradle.plugin.spotbugs)
}
