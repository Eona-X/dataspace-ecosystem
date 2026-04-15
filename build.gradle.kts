import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage
import com.github.jengelman.gradle.plugins.shadow.ShadowJavaPlugin

plugins {
    `java-library`
    id("com.bmuschko.docker-remote-api") version "9.3.4"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    alias(libs.plugins.edc.build)
}

val annotationProcessorVersion: String by project
val dseWebsiteUrl: String by project
val dseScmConnection: String by project
val dseScmUrl: String by project

val loadToKind = project.hasProperty("loadToKind")

allprojects {

    apply(plugin = "${group}.edc-build")
    apply(plugin = "org.eclipse.edc.autodoc")

    // configure which version of the annotation processor to use. defaults to the same version as the plugin
    configure<org.eclipse.edc.plugins.autodoc.AutodocExtension> {
        processorVersion.set(annotationProcessorVersion)
        outputDirectory.set(project.layout.buildDirectory.asFile.get())
    }

    configure<org.eclipse.edc.plugins.edcbuild.extensions.BuildExtension> {
        pom {
            // this is actually important, so we can publish under the correct GID
            groupId = project.group.toString()
            projectName.set(project.name)
            description.set("edc :: ${project.name}")
            projectUrl.set(dseWebsiteUrl)
            scmConnection.set(dseScmConnection)
            scmUrl.set(dseScmUrl)
        }
    }

    configure<CheckstyleExtension> {
        configFile = rootProject.file("resources/checkstyle-config.xml")
        configDirectory.set(rootProject.file("resources"))
    }

    // Force secure Netty version to fix vulnerable component: netty-codec-http2
    configurations.all {
        resolutionStrategy {
            force("io.netty:netty-codec-http2:4.2.4.Final")
        }
    }
}

subprojects {
    afterEvaluate {
        val vaultType: String = project.findProperty("vaultType") as? String ?: "hashicorp"
        
        // Skip building Docker images for non-selected vault variants
        val shouldSkipVaultVariant = when {
            vaultType == "both" -> false  // Build all variants when vaultType=both
            project.name.contains("-postgresql-azurevault") && vaultType != "azure" -> true
            project.name.contains("-postgresql-hashicorpvault") && vaultType != "hashicorp" -> true
            else -> false
        }

        if (project.plugins.hasPlugin("com.github.johnrengelman.shadow") && file("${project.projectDir}/Dockerfile").exists() && !shouldSkipVaultVariant) {
            val buildDir = project.layout.buildDirectory.get().asFile
            val otelFileName = "opentelemetry-javaagent.jar"
            val agentFileOnBuildDirectory = buildDir.resolve(otelFileName)

            val openTelemetryAgentUrl =
                "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.9.0/opentelemetry-javaagent.jar"

            val dockerContextDir = project.projectDir
            val dockerFile = file("$dockerContextDir/Dockerfile")
            val imageName = "${project.name}:latest"

            val copyOtel = tasks.register("copyOtel") {
                val agentFile = rootDir.resolve("externalLibs").resolve(otelFileName)
                // Only execute if the opentelemetry agent exists in the root dir but not in build directory
                onlyIf {
                    agentFile.exists() && !agentFileOnBuildDirectory.exists()
                }
                // Ensure the build directory exists before copying
                doFirst {
                    buildDir.mkdirs()
                }
                // Copy the jar file
                doLast {
                    copy {
                        from(agentFile)
                        into(buildDir)
                    }
                }
            }

            val downloadOtel = tasks.create("downloadOtel") {
                // Run after copyOtel to check if we still need to download
                dependsOn(copyOtel)
                // only execute task if the opentelemetry agent does not exist after copyOtel. invoke the "clean" task to force
                onlyIf {
                    !agentFileOnBuildDirectory.exists()
                }
                // this task could be the first in the graph, so "build/" may not yet exist. Let's be defensive
                doFirst {
                    project.layout.buildDirectory.asFile.get().mkdirs()
                }
                // download the jar file
                doLast {
                    val download = { url: String, destFile: File ->
                        ant.invokeMethod(
                            "get",
                            mapOf("src" to url, "dest" to destFile)
                        )
                    }
                    logger.lifecycle("Downloading OpenTelemetry Agent")
                    download(openTelemetryAgentUrl, agentFileOnBuildDirectory)
                }
            }

            val podmanTask = tasks.register("podmanize", Exec::class) {
                description = "Build container image with Podman"
                group = "build"

                val jarFile = "./build/libs/${project.name}.jar"
                val otelFile = "./build/$otelFileName"
                val platform = System.getProperty("platform")

                val commandLineArgs = mutableListOf(
                    "podman", "build",
                    "--build-arg", "JAR=$jarFile",
                    "--build-arg", "OTEL_JAR=$otelFile",
                    "-t", imageName,
                    "-f", dockerFile,
                    dockerContextDir
                )

                if(platform != null) {
                    commandLineArgs.add("--platform")
                    commandLineArgs.add(platform)
                }

                commandLine(commandLineArgs)

                doLast {
                    exec {
                        commandLine("podman", "save", "-o", "${dockerContextDir.path}/image.tar", imageName)
                    }
                }
            }
            podmanTask.configure {
                dependsOn(tasks.named(ShadowJavaPlugin.SHADOW_JAR_TASK_NAME))
                dependsOn(downloadOtel)
            }

            val loadToKindTask = tasks.register("loadToKind") {
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
            
            val dockerTask: DockerBuildImage = tasks.create("dockerize", DockerBuildImage::class) {
                project.plugins.apply("com.bmuschko.docker-remote-api")
                
                val imageTag = project.findProperty("imageTag")?.toString() ?: "latest"
                val registryHost = project.findProperty("registryHost")?.toString() ?: "localhost"
                val registryNs = project.findProperty("registryNs")?.toString() ?: "local"
                
                images.add("${registryHost}/${registryNs}/${project.name}:${imageTag}")
                images.add("${registryHost}/${registryNs}/${project.name}:latest")
                // specify platform with the -Dplatform flag:
                if (System.getProperty("platform") != null)
                    platform.set(System.getProperty("platform"))
                buildArgs.put("JAR", "build/libs/${project.name}.jar")
                buildArgs.put("OTEL_JAR", "build/${agentFileOnBuildDirectory.name}")
                inputDir.set(file(dockerContextDir))
            }
            // make sure  always runs after "dockerize" and after "copyOtel"
            dockerTask.dependsOn(tasks.named(ShadowJavaPlugin.SHADOW_JAR_TASK_NAME))
                .dependsOn(downloadOtel)

            // Only create dockerPushTask if not running locally (registryHost != localhost)
            if (registryHost != "localhost") {
                val dockerPushTask: DockerPushImage = tasks.create("dockerPush", DockerPushImage::class) {
                    group = "distribution"
                    description = "Push Docker image to registry"
                    project.plugins.apply("com.bmuschko.docker-remote-api")
                    images.add("${registryHost}/${registryNs}/${project.name}:${imageTag}")
                    images.add("${registryHost}/${registryNs}/${project.name}:latest")
                }
                // Ensure push happens after build
                dockerPushTask.dependsOn(dockerTask)
            }
                

        }
    }
}

buildscript {
    dependencies {
        val edcGradlePluginsVersion: String by project
        val version: String by project
        classpath("org.eclipse.edc.edc-build:org.eclipse.edc.edc-build.gradle.plugin:${edcGradlePluginsVersion}")
        classpath("org.eclipse.edc.autodoc:org.eclipse.edc.autodoc.gradle.plugin:$version")
    }
}