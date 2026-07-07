plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":extensions:data-plane:data-plane-public-api-v2"))
    runtimeOnly(project(":extensions:data-plane:data-plane-data-consumption-metrics"))
    runtimeOnly(project(":extensions:data-plane:data-plane-http-header-secrets"))
    runtimeOnly(project(":core:common:telemetry-record-store"))

    runtimeOnly(libs.edc.ext.dpf.kafka)
    runtimeOnly(libs.edc.core.edr.store)
    runtimeOnly(libs.bundles.connector)
    runtimeOnly(libs.edc.dataplane.bom)
}

edcBuild {
    publish.set(false)
}