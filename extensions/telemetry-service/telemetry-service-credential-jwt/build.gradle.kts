plugins {
    `java-library`
}

dependencies {
    implementation(project(":spi:telemetry-service-spi"))
    implementation(libs.edc.spi.core)
    implementation(libs.edc.spi.jwt)
    implementation(libs.edc.spi.token)
    implementation(libs.edc.spi.jwt.signer)
    implementation(libs.edc.lib.keys)
    implementation(libs.edc.core.token)
    implementation(libs.edc.lib.crypto.common)
    implementation(libs.nimbus.jwt)
}
