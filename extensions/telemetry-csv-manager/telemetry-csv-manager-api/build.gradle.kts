plugins {
    `java-library`
    id("io.swagger.core.v3.swagger-gradle-plugin")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.edc.core.jersey)
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("org.hibernate.orm:hibernate-core:7.1.2.Final")
    implementation("software.amazon.awssdk:s3:2.20.162")
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    testImplementation("org.hibernate.orm:hibernate-core:7.1.2.Final")
    testRuntimeOnly("org.hsqldb:hsqldb:2.7.4")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.h2database:h2:2.2.224")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.mockito:mockito-core:5.10.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.10.0")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:minio:1.21.4")
    testImplementation(libs.assertj)
}