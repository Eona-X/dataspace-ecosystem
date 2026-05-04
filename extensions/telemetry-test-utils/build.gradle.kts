plugins {
    `java-library`
}

dependencies {
    api(libs.kafka.clients)
    api(project(":spi:telemetry-agent-spi"))

    testImplementation(libs.edc.core.junit)
    testImplementation(libs.assertj)
}
