plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.spi.core)
    implementation(libs.edc.spi.policy.context)
    api(libs.edc.spi.participant)

}
