plugins {
    `java-library`
}

dependencies {
    implementation(project(":spi:telemetry-service-spi"))

    implementation(libs.edc.issuerservice.spi.holder)
    implementation(libs.edc.lib.token)
    implementation(libs.edc.lib.http)
    implementation(libs.edc.spi.identity.did)
    implementation(libs.edc.spi.transaction)
    implementation(libs.edc.spi.participant)
    implementation(libs.edc.spi.policy.context)
    implementation(libs.edc.spi.identitytrust)
    implementation(libs.edc.spi.controlplane)
    implementation(libs.edc.controlplane.core)
    implementation(libs.edc.spi.dsp.http)

    testImplementation(testFixtures(project(":spi:telemetry-agent-spi")))
    testImplementation(libs.edc.core.junit)
}
