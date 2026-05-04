/*
 *  Copyright (c) 2024 Amadeus IT Group
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Amadeus IT Group - Vault type selection logic
 */

/**
 * Shared configuration for launcher modules to manage vault-specific dependencies.
 * This script dynamically includes HashiCorp Vault.
 *
 * Usage: apply(from = "$rootDir/gradle/launcher-vault-dependencies.gradle.kts")
 *
 * Supported vaultType values:
 * - "hashicorp" (default): Include only HashiCorp Vault variant
 */

val launcherPrefix = ":launchers:${project.name}:${project.name}"

dependencies {
    add("implementation", project("$launcherPrefix-base"))
    add("implementation", project("$launcherPrefix-postgresql-hashicorpvault"))
}
