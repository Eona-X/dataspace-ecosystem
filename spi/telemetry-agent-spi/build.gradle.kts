plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(libs.edc.spi.core)
    implementation(libs.jackson.datatypeJsr310)
    testImplementation(libs.edc.lib.json)
    implementation(libs.edc.lib.statemachine)
    implementation(libs.edc.spi.http)

    testFixturesImplementation(libs.edc.core.junit)
    testFixturesImplementation(libs.junit.jupiter.api)
    testFixturesImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    
}


