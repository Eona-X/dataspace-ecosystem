plugins {
    `java-library`
}

dependencies {
    api(libs.edc.spi.asset)
    api(libs.edc.spi.core)
    api(libs.edc.spi.controlplane)
    api(libs.edc.spi.transaction)
}
