plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":core:telemetry-agent-core"))
    runtimeOnly(project(":extensions:common:vc-revocation-patch"))
    runtimeOnly(project(":extensions:telemetry-agent:event-hub-telemetry-record-publisher"))
    runtimeOnly(project(":extensions:common:policies"))

    runtimeOnly(libs.bundles.connector)
    runtimeOnly(libs.bundles.identity)
    runtimeOnly(libs.edc.identitytrust.core)
    runtimeOnly(libs.edc.oauth2.oauth2client)
}

edcBuild {
    publish.set(false)
}