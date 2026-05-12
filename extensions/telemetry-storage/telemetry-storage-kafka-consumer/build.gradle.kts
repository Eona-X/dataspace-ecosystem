plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.spi.core)
    implementation(libs.kafka.clients)
    implementation(project(":spi:telemetry-agent-spi"))
    implementation(project(":spi:telemetry-storage-spi"))
    implementation(libs.edc.lib.json)

    testImplementation(project(":extensions:telemetry-test-utils"))
    testImplementation(libs.edc.core.junit)

    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.awaitility)
    testImplementation(libs.assertj)
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
}
