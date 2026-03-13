import com.bmuschko.gradle.docker.tasks.image.DockerPushImage
import org.gradle.kotlin.dsl.create

/*
 *  Copyright (c) 2024 Eclipse Dataspace Connector Project
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Eclipse Dataspace Connector Project - initial implementation
 */

plugins {
    id("com.bmuschko.docker-remote-api") version "9.3.4"
}

import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

val dockerImageName = project.findProperty("docker.image.name")?.toString() ?: "kafka-proxy-oidc-auth"
val dockerImageTag = project.findProperty("docker.image.tag")?.toString() ?: "latest"
val dockerRegistry = project.findProperty("docker.registry")?.toString() ?: ""
val loadToKind = project.hasProperty("loadToKind")

val pluginDir = file("kafka-proxy-auth")
val dockerContextDir = pluginDir
val dockerFile = file("$dockerContextDir/Dockerfile")
val imageName = "$dockerImageName:$dockerImageTag"

// Task to build Go binaries
val buildGoPlugins = tasks.register<Exec>("buildGoPlugins") {
    group = "build"
    description = "Build Go authentication plugins"
    
    workingDir = pluginDir
    
    // Use environment variable or system property to override the build target
    val makeTarget = System.getProperty("go.build.target") 
        ?: System.getenv("GO_BUILD_TARGET") 
        ?: "build"  // Default to regular build for CI/CD compatibility
    
    commandLine = listOf("make", makeTarget)
    
    doFirst {
        println("Building Go authentication plugins with target: $makeTarget")
    }
    
    doLast {
        println("Go plugins built successfully")
    }
}

// Task to build Go binaries specifically for Linux (Docker)
val buildGoPluginsLinux = tasks.register<Exec>("buildGoPluginsLinux") {
    group = "build"
    description = "Build Go authentication plugins for Linux (Docker)"
    
    workingDir = pluginDir
    commandLine = listOf("make", "build-linux")
    
    doFirst {
        println("Building Go authentication plugins for Linux/Docker...")
    }
    
    doLast {
        println("Go plugins built successfully for Linux/Docker")
    }
}

// Task to run Go tests
tasks.register<Exec>("testGoPlugins") {
    group = "verification"
    description = "Run Go plugin tests"
    
    workingDir = pluginDir
    commandLine = listOf("make", "test")
    
    doFirst {
        println("Running Go plugin tests...")
    }
}

// Task to clean Go build artifacts
tasks.register<Exec>("cleanGoPlugins") {
    group = "build"
    description = "Clean Go plugin build artifacts"
    
    workingDir = pluginDir
    commandLine = listOf("make", "clean")
    
    doFirst {
        println("Cleaning Go plugin build artifacts...")
    }
}

// Podman build task (following the same pattern as other modules)
val podmanTask = tasks.register("podmanize", Exec::class) {
    description = "Build container image with Podman for Kafka Proxy authentication plugins"
    group = "build"

    val platform = System.getProperty("platform")

    val commandLineArgs = mutableListOf(
        "podman", "build",
        "-t", imageName,
        "-f", dockerFile.absolutePath,
        dockerContextDir.absolutePath
    )

    if (platform != null) {
        commandLineArgs.add("--platform")
        commandLineArgs.add(platform)
    }

    commandLine(commandLineArgs)

    doLast {
        exec {
            commandLine("podman", "save", "-o", "${dockerContextDir.path}/image.tar", imageName)
        }
        println("Podman image built and saved: $imageName")
    }
}

podmanTask.configure {
    dependsOn(buildGoPluginsLinux)
}

// Load to Kind task (following the same pattern as other modules)
val loadToKindTask = tasks.register("loadToKind") {
    group = "docker"
    description = "Load Kafka Proxy plugin image to Kind cluster"
    
    doFirst {
        exec {
            commandLine("kind", "load", "image-archive", "${dockerContextDir.path}/image.tar", "-n", "dse-cluster")
            println("Image loaded to kind: $imageName")
        }
    }
}

loadToKindTask.configure {
    dependsOn(podmanTask)
}

// Docker build task (for Docker users)
val dockerTask = tasks.register("dockerize", DockerBuildImage::class) {
    group = "docker"
    description = "Build Docker image with OIDC authentication plugins"
    
    dependsOn(buildGoPlugins)
    
    images.add(imageName)
    
    if (dockerRegistry.isNotEmpty()) {
        images.add("$dockerRegistry/$dockerImageName:$dockerImageTag")
        images.add("$dockerRegistry/$dockerImageName:latest")
    }
    
    // specify platform with the -Dplatform flag:
    if (System.getProperty("platform") != null) {
        platform.set(System.getProperty("platform"))
    }
    
    inputDir.set(dockerContextDir)
    dockerFile.set(file("$dockerContextDir/Dockerfile"))
    
    doFirst {
        println("Building Docker image: $imageName")
    }
    
    doLast {
        println("Docker image built successfully")
    }
}


val dockerPushTask: DockerPushImage = tasks.create("dockerPush", DockerPushImage::class) {
    group = "distribution"
    description = "Push Docker image to registry"
    images.add("${dockerRegistry}/${dockerImageName}:${dockerImageTag}")
    images.add("${dockerRegistry}/${dockerImageName}:latest")
}
// Ensure push happens after build
dockerPushTask.dependsOn(dockerTask)

// Aggregate task for complete build
tasks.register("buildAll") {
    group = "build"
    description = "Build, test, and create container image for authentication plugins"
    
    dependsOn("testGoPlugins", "buildGoPlugins")
    
    if (loadToKind) {
        dependsOn("loadToKind")
    } else {
        dependsOn("podmanize")
    }
}

// Clean task removes image tar
tasks.register("cleanImage") {
    group = "build"
    description = "Clean generated image tar file"
    
    doLast {
        val imageTar = file("${dockerContextDir.path}/image.tar")
        if (imageTar.exists()) {
            imageTar.delete()
            println("Deleted image tar: ${imageTar.absolutePath}")
        }
    }
}

// Hook into standard Gradle lifecycle
tasks.named("clean") {
    dependsOn("cleanGoPlugins", "cleanImage")
}
