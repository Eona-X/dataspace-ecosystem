# Dataspace Ecosystem

![Latest Release]()
![Contributors]()

## 1. Overview

The Dataspace Ecosystem enables interoperable, policy-governed, and auditable data exchange across organizational boundaries. It aligns with emerging dataspace standards (e.g. [GAIA-X](https://gaia-x.eu/) / IDS concepts) and leverages
the [Eclipse EDC components](https://github.com/eclipse-edc) to build this ecosystem securely, using concepts like contracts, policies and verifiable credentials. This project is currently being developed by [Eona-X](https://eona-x.eu). 


## 2. Architecture

### 2.1. Components:

* **Control Plane:** Manages contract negotiations and policy evaluations
* **Data Plane:** Enables data transfers
* **Identity Hub:** Provides credential validation to authenticate participants in the ecosystem
* **Federated Catalog:** Provides info about all the available datasets
* **Issuer Service:** Provides VC credentials for the participants to authenticate in the ecosystem
* **Telemetry Agent:** Collects contract/data transfers information from the participants connectors relevant for the billing system
* **Telemetry Service:** Provides SAS tokens for telemetry agents to authenticate
* **Telemetry Storage:** Stores telemetry events used for the billing system

## 3. Repository Structure

The repository is structured as follows:

```
.
├─ core/                # Default implementations
├─ docs/                # Additional documentation assets
├─ extensions/          # Optional runtime features (providers, auth, connectors)
├─ externalLibs/        # Libraries used for building images
├─ gradle/              # Gradle Wrapper
├─ images/              # Pictures used in documentation
├─ launchers/           # Contains Dockerfiles to build images for the components
├─ resources/           # OpenAPI Specs of the project
├─ spi/                 # Service Provider Interfaces (service contracts interfaces)
├─ system-tests/        # Cross-service integration tests
```

### 3.1 In-depth folder information

### Core

The core folder contains all the core logic with the default implementation.

For example, in the **telemetry-agent-core** folder, you will find:

- *TelemetryAgentCoreExtension*: Contains the core logic for this specific service.
- *TelemetryAgentDefaultServiceExtension*: Provides the default implementation logic.
- *Defaults folder*: Contains the default implementation.
  The idea is to have a general implementation that can be extended by the "Extension" part, which will contain the
  actual implementation of specific functionalities.

For instance, in the **telemetry-agent-core**, we use classes from the *core/common* folder. Specifically, we use the
*InMemoryTelemetryRecordStore* to simulate a database. This class is used also by the *telemetry-service-core* (this is
why is inside a 'common' folder).

In the *TelemetryAgentDefaultServiceExtension*, we use the *inMemoryTelemetryRecordStore* method to provide an
implementation for the *TelemetryRecordStore* interface and designate it as the default using
```@Provider(isDefault = true)```.

### Launchers

Through the Launcher, we can enhance this service with different implementations. The base implementation in the core
folder serves as the default. If we want to change an implementation, we can define it inside the launchers folder.

For example, in ```launcher/telemetry-agent/telemetry-agent-postgresql-eventhub```, the *build.gradle.kts* file includes
```runtimeOnly(project(":extensions:common:store:sql:telemetry-store-sql"))```. This means that this implementation will
be loaded at runtime and will inject a different implementation. If we examine this code, we will see that
*telemetry-store-sql* provides an implementation for *TelemetryRecordStore*, replacing the default one.

### Extensions

The extensions folder is used to add the actual implementation of functionalities that will be injected at runtime.

### SPI (Service Provider Interface)

The SPI module contains interfaces and abstract classes that define the contracts for service providers. This allows for
the implementation of different service providers that can be plugged into the system.

### System Test

The System Test module includes integration tests that verify the interactions between different components of the
system. These tests ensure that the system works as a whole.

## 4. Technology Stack

- Language: Java
- Build: Gradle (Kotlin DSL)
- Testing: JUnit

## 5. Prerequisites

* JDK 17
* Docker/Podman build tool
* Kind
* Terraform
* Kubectl

## 6. Getting Started

To get started with the project, follow these instructions:

1. Navigate to the `/system-tests` directory.
2. Open the `readme.md` file.
3. Inside the file, you will find detailed instructions on how to obtain a Minimum Viable Product (MVP) for the project.

These instructions will guide you through the necessary steps to set up and use the system tests for the project.

## 7. OpenAPI Specifications

Our extensions are integrated with an OpenAPI plugin that allows to generate the available OpenAPI specs with a specific gradle task. To inspect the available OpenAPI specs to generate we can execute the command:

```
./gradlew tasks --all | grep openapi
```

We can select one of the tasks available in the output of this command and simply run it. Example:

```
./gradlew extensions:agreements:retirement-evaluation-api:openapi 
```

In this example, the generated OpenApi spec will be placed in the folder ```extensions/agreements/retirement-evaluation-api/build/docs/openapi```

## 8. Contributing

We welcome community feedback and engagement! Please read our [Contributing Guide](CONTRIBUTING.md) to understand our current contribution process and policies.

For guidelines on submitting and reviewing pull requests (when contributions are enabled), please refer to our [PR Etiquette Guide](PR_ETIQUETTE.md).

Also ensure your contributions align with our [Code of Conduct](CODE_OF_CONDUCT.md) and follow our [Code Style Guide](STYLE_GUIDE.md).

**Important**: Before using this code, please review our [Disclaimer](DISCLAIMER.md) regarding security validation and production deployment.

## 9. License

Distributed under the Apache 2.0 License.
See [LICENSE](LICENSE) for more information.

