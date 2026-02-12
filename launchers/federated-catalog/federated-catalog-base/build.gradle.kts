plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":extensions:common:vc-revocation-patch"))
    runtimeOnly(project(":extensions:common:policies"))
    runtimeOnly(project(":extensions:common:metrics:custom-micrometer"))
    runtimeOnly(project(":extensions:federated-catalog:participant-registry-node-directory"))
    runtimeOnly(libs.bundles.connector)
    runtimeOnly(libs.edc.federatedcatalog.bom)
}

edcBuild {
    publish.set(false)
}