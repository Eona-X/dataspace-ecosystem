plugins {
    `java-library`
}

dependencies {
    implementation(project(":launchers:federated-catalog-filter:federated-catalog-filter-base"))
    implementation(project(":launchers:federated-catalog-filter:federated-catalog-filter-postgresql-hashicorpvault"))
}