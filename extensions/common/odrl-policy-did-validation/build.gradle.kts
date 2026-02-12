plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.spi.core)
    implementation(libs.edc.spi.transform)
    implementation(libs.edc.spi.jsonld)
    implementation(libs.edc.lib.transform)
    implementation(libs.edc.api.management.config)
    implementation(libs.edc.lib.validator)
    
    implementation(libs.jakarta.rsApi)

    testImplementation(libs.edc.junit)
    testImplementation(libs.assertj)
    testImplementation(libs.jakartaJson)
}


