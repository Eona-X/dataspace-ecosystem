plugins {
    `java-library`
    `java-test-fixtures`
}

// Configure test task to pass system properties
tasks.test {
    useJUnitPlatform()
    // Pass all system properties that start with "cluster." or "kubectl." to the test JVM
    System.getProperties().forEach { key, value ->
        if (key.toString().startsWith("cluster.") || key.toString().startsWith("kubectl.")) {
            systemProperty(key.toString(), value.toString())
        }
    }
}

dependencies {
    testImplementation(libs.edc.spi.identity.did)
    testImplementation(libs.awaitility)
    testImplementation(libs.edc.spi.dpf)
    testImplementation(libs.jakarta.rsApi)
    testImplementation(libs.edc.lib.keys)
    testImplementation(project(":spi:telemetry-service-spi"))
    testImplementation(project(":extensions:common:policies"))
    testImplementation(project(":spi:common-spi"))
    testImplementation(libs.jjwt.api)
    testImplementation(libs.jjwt.impl)
    testImplementation(libs.jjwt.jackson)

    testImplementation(libs.postgres)

    testFixturesApi(libs.restAssured)
    testFixturesApi(testFixtures(libs.edc.ext.api.management.test.fixtures))

    testFixturesImplementation(project(":spi:common-spi"))
    testFixturesImplementation(project(":extensions:common:policies"))
    testFixturesImplementation(project(":extensions:issuer-service:domain-attestation-api"))
    testFixturesImplementation(project(":extensions:issuer-service:membership-attestation-api"))
    testFixturesImplementation(libs.edc.issuerservice.api.credentialdefinition)
    testFixturesImplementation(libs.edc.issuerservice.api.attestation)
    testFixturesImplementation(libs.edc.issuerservice.api.holder)
    testFixturesImplementation(libs.edc.identityhub.api.verifiablecredentials)
    testFixturesImplementation(libs.edc.spi.vc)
    testFixturesImplementation(libs.edc.lib.crypto.common)
    testFixturesImplementation(libs.edc.core.token)
    testFixturesImplementation(libs.edc.lib.token)
    testFixturesImplementation(libs.edc.spi.jsonld)
    testFixturesImplementation(libs.edc.federatedcatalog.spi.core)
    testFixturesImplementation(libs.edc.identityhub.spi.core)
    testFixturesImplementation(libs.edc.spi.identity.did)
    testFixturesImplementation(libs.awaitility)
    testImplementation(libs.edc.core.junit)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.assertj)
    testImplementation(libs.kafka.clients)
    testFixturesImplementation(libs.kafka.clients)

}
