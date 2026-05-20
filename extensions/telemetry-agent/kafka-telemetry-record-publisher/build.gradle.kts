plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.spi.core)
    implementation(libs.kafka.clients)
    implementation(project(":spi:common-spi"))
    implementation(project(":spi:telemetry-agent-spi"))
    implementation(libs.nimbus.jwt)
    implementation(project(":spi:telemetry-service-spi"))
    implementation(libs.nimbus.jwt)

    testImplementation(project(":extensions:telemetry-test-utils"))
    testImplementation(libs.edc.core.junit)

    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.awaitility)
    testImplementation(libs.assertj)
}
