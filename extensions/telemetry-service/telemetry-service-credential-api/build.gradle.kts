plugins {
    `java-library`
    id(libs.plugins.swagger.get().pluginId)
}

dependencies {
    api(project(":spi:telemetry-service-spi"))
    implementation(libs.edc.spi.web)
    implementation(libs.edc.lib.token)
    implementation(libs.jakarta.rsApi)
    implementation(libs.edc.core.api)
    implementation(libs.edc.core.jersey)
    implementation(libs.edc.spi.jwt)
    implementation(libs.edc.lib.keys)
    implementation(libs.nimbus.jwt)

    testImplementation(libs.edc.tests.junit.base)
    testImplementation(libs.restAssured)
    testImplementation(libs.awaitility)
    testImplementation(libs.edc.lib.jsonld)
    testImplementation(libs.edc.lib.transform)
    testImplementation(testFixtures(libs.edc.core.jersey))
}

edcBuild {
    swagger {
        apiGroup.set("credential-api")
    }
}
