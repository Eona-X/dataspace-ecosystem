plugins {
    id("application")
    alias(libs.plugins.shadow)
}

var distTar = tasks.getByName("distTar")
var distZip = tasks.getByName("distZip")

dependencies {
    runtimeOnly(project(":launchers:telemetry-service:telemetry-service-base"))
    runtimeOnly(project(":extensions:telemetry-service:telemetry-service-credential-jwt"))

    runtimeOnly(libs.edc.issuerservice.holder.store.sql)
    runtimeOnly(libs.bundles.coresql)
    runtimeOnly(libs.edc.ext.vault.hashicorp)
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
    archiveFileName.set("${project.name}.jar")
    dependsOn(distTar, distZip)
    mustRunAfter(distTar, distZip)
}

edcBuild {
    publish.set(false)
}