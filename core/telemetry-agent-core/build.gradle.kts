plugins {
    `java-library`
}

dependencies {
    implementation(project(":spi:telemetry-agent-spi"))
    implementation(project(":spi:telemetry-service-spi"))
    implementation(project(":core:common:telemetry-record-store"))
    implementation(project(":extensions:common:policies"))

    implementation(libs.edc.lib.token)
    implementation(libs.edc.spi.identity.did)
    implementation(libs.edc.lib.store)
    implementation(libs.edc.lib.query)
    testImplementation(libs.edc.lib.query)
    implementation(libs.edc.lib.statemachine)
    implementation(libs.edc.lib.http)
    
    // Policy Engine and IATP dependencies
    implementation(libs.edc.spi.policy.engine)
    implementation(libs.edc.spi.policy.context)
    
    testImplementation(libs.awaitility)
    implementation(libs.edc.lib.util)
    testImplementation(libs.edc.lib.json)
    testImplementation(testFixtures(project(":spi:telemetry-agent-spi")))
    testImplementation(libs.mockserver.netty)
    testImplementation(libs.mockserver.client)
    testImplementation(testFixtures(libs.edc.lib.http))
}
