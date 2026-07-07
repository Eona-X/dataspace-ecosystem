plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":extensions:telemetry-storage:telemetry-storage-api"))
    runtimeOnly(project(":extensions:telemetry-storage:telemetry-storage-kafka-consumer"))

    runtimeOnly(libs.bundles.connector)
}

edcBuild {
    publish.set(false)
}