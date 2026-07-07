plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":extensions:common:vc-revocation-patch"))
    runtimeOnly(project(":extensions:common:policies"))
    runtimeOnly(project(":extensions:common:odrl-policy-did-validation"))
    runtimeOnly(project(":extensions:agreements"))
    runtimeOnly(project(":extensions:common:metrics:custom-micrometer"))
    runtimeOnly(project(":extensions:control-plane:asset-custom-property-subscriber"))
    runtimeOnly(project(":extensions:control-plane:transfer-data-plane-signal-kafka"))
    runtimeOnly(project(":extensions:control-plane:control-plane-federated-catalog-filter"))
    runtimeOnly(libs.edc.controlplane.api.secrets)
    runtimeOnly(libs.bundles.connector)
    runtimeOnly(libs.edc.controlplane.bom)
}

edcBuild {
    publish.set(false)
}