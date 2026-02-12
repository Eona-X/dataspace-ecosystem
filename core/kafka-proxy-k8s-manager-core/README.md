# Kafka Proxy Kubernetes Manager - Comprehensive Guide

This is the complete documentation for the Kafka Proxy Kubernetes Manager, a service that provides automatic discovery, validation, deployment, and lifecycle management of Kafka proxy instances for EDRs (Endpoint Data References) stored in HashiCorp Vault.

## Table of Contents

- [Overview](#overview)
- [How It Works](#how-it-works)
- [Architecture Components](#architecture-components)
- [Configuration Reference](#configuration-reference)
- [Vault Integration](#vault-integration)
- [Authentication](#authentication)
- [TLS Configuration](#tls-configuration)
- [Security Considerations](#security-considerations)

## Overview

The Kafka Proxy Kubernetes Manager is an EDC (Eclipse Dataspace Connector) extension that automatically:

1. **Discovers Kafka EDRs** from HashiCorp Vault (with optional folder organization)
2. **Validates EDR properties** and determines deployment readiness
3. **Deploys proxy instances** dynamically to Kubernetes
4. **Manages lifecycle** including updates and cleanup
5. **Tags EDRs with metadata** in Vault KV v2 for efficient deduplication
6. **Supports authentication** for downstream clients (PLAIN and OAUTHBEARER)
7. **Enables TLS** for secure client connections

## How It Works

### Discovery and Deployment Flow

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. DISCOVERY CYCLE (every 10s by default)                       │
│    ┌──────────────────────────────────────────────────────┐    │
│    │ VaultDiscoveryService                                  │    │
│    │ • Lists all keys in secret/<folder>/                  │    │
│    │ • Filters for edr--* prefix                           │    │
│    │ • Checks custom_metadata to skip processed EDRs       │    │
│    └──────────────────────────────────────────────────────┘    │
│                          │                                       │
│                          ▼                                       │
│    ┌──────────────────────────────────────────────────────┐    │
│    │ EDR Validation                                         │    │
│    │ • Reads secret content                                │    │
│    │ • Checks type == "Kafka"                              │    │
│    │ • Validates bootstrap servers and topic               │    │
│    │ • Tags with custom_metadata (is_kafka, ready, tls)    │    │
│    └──────────────────────────────────────────────────────┘    │
│                          │                                       │
└──────────────────────────┼───────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ 2. QUEUE MANAGEMENT                                             │
│    ┌──────────────────────────────────────────────────────┐    │
│    │ AutomaticDiscoveryQueueService                         │    │
│    │ • Writes ALL ready EDRs to /shared/kafka_edrs_ready.txt │  │
│    │ • Detects orphaned proxies                            │    │
│    │ • Writes cleanup list to kafka_edrs_cleanup.txt       │    │
│    └──────────────────────────────────────────────────────┘    │
│                          │                                       │
└──────────────────────────┼───────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ 3. DEPLOYMENT CYCLE (every 15s by default)                      │
│    ┌──────────────────────────────────────────────────────┐    │
│    │ KafkaProxyOrchestrator - SINGLE PROXY PATTERN         │    │
│    │ • Reads ALL EDRs from ready queue                     │    │
│    │ • Selects NEWEST EDR by creation timestamp            │    │
│    │ • Marks older EDRs as processed (skipped)             │    │
│    │ • Deploys ONLY the newest EDR                         │    │
│    └──────────────────────────────────────────────────────┘    │
│                          │                                       │
│                          ▼                                       │
│    ┌──────────────────────────────────────────────────────┐    │
│    │ KubernetesDeployerService                              │    │
│    │ • Finds and DELETES all existing proxy deployments    │    │
│    │ • Generates EDR-specific deployment name              │    │
│    │   kp-{participant}-{edr-short-id}                     │    │
│    │ • Generates STANDARDIZED service name (FIXED)         │    │
│    │   kp-{participant}-service                            │    │
│    │ • Creates Deployment with kafka-proxy container       │    │
│    │ • Creates/Updates Service with FIXED name & port      │    │
│    │   Service: kp-consumer-service:30001 (always same)    │    │
│    │ • Configures authentication (if enabled)              │    │
│    │ • Configures TLS listener (if enabled)                │    │
│    │ • Configures upstream Kafka connection (TLS/SASL)     │    │
│    └──────────────────────────────────────────────────────┘    │
│                          │                                       │
└──────────────────────────┼───────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ 4. PROXY RUNNING STATE (SINGLE PROXY AT A TIME)                 │
│    ┌──────────────────────────────────────────────────────┐    │
│    │ Deployment: kp-consumer-<newest-edr-id>                │    │
│    │ Service: kp-consumer-service (STANDARDIZED, FIXED)     │    │
│    │ Port: 30001 (FIXED)                                    │    │
│    │                                                        │    │
│    │ • Only ONE proxy deployment running at a time         │    │
│    │ • Service name stays constant for DNS registration    │    │
│    │ • External clients always connect to same FQDN:       │    │
│    │   kp-consumer-service.default.svc.cluster.local:30001 │    │
│    │ • When new EDR arrives: old deployment deleted,       │    │
│    │   new deployment created, service updated to point    │    │
│    │   to new deployment (seamless switchover)             │    │
│    └──────────────────────────────────────────────────────┘    │
│                          │                                       │
└──────────────────────────┼───────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ 5. HEALTH MONITORING                                            │
│    ┌──────────────────────────────────────────────────────┐    │
│    │ KubernetesCheckerService                               │    │
│    │ • Checks proxy pod status (Ready/NotReady)            │    │
│    │ • Detects failed deployments                          │    │
│    │ • Identifies orphaned proxies                         │    │
│    │ • Periodic health summaries in logs                   │    │
│    └──────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

### Single-Proxy Pattern

**Key Principle**: Only ONE Kafka proxy runs at a time per participant, with a **standardized, fixed service name** for DNS registration.

**Why This Pattern?**
- **DNS Stability**: External load balancers need a fixed FQDN to route traffic
- **Resource Efficiency**: Avoids running multiple proxies when only one EDR is active
- **Automatic Switchover**: When a new EDR arrives, old proxy is deleted and new one takes its place
- **Service Continuity**: Service name and port remain constant, only deployment changes

**Naming Convention**:
- **Deployment Name**: `kp-{participant}-{edr-short-id}` (changes with each EDR)
  - Example: `kp-consumer-caa92ff5-2dbb-432d-b35c-2e2ac487544d`
- **Service Name**: `kp-{participant}-service` (FIXED, never changes)
  - Example: `kp-consumer-service`
- **Port**: `30001` (FIXED)

**Deployment Behavior**:
1. **Multiple EDRs Queued**: Orchestrator selects NEWEST EDR by vault creation timestamp
2. **Old Deployments**: All existing deployments for this participant are deleted
3. **New Deployment**: One new deployment created for newest EDR
4. **Service Update**: Service selector updated to point to new deployment
5. **Older EDRs**: Marked as "processed" to prevent requeuing

**Example Timeline**:
```
T0: EDR-A arrives → Deploy kp-consumer-<EDR-A>
T1: EDR-B arrives → Delete kp-consumer-<EDR-A>, Deploy kp-consumer-<EDR-B>
T2: EDR-C arrives → Delete kp-consumer-<EDR-B>, Deploy kp-consumer-<EDR-C>

At all times:
- Service name: kp-consumer-service (unchanged)
- Service port: 30001 (unchanged)
- External DNS: kp-consumer-service.default.svc.cluster.local:30001
```

### State Machine

Each EDR goes through the following states:

1. **DISCOVERED**: Found in Vault, metadata checked
2. **VALIDATED**: Confirmed as Kafka EDR with valid properties
3. **QUEUED**: Added to deployment queue
4. **SELECTED** (NEW): Chosen as newest EDR for deployment
5. **DEPLOYING**: Kubernetes resources being created
6. **DEPLOYED**: Proxy running and healthy
7. **SKIPPED** (NEW): Older EDR skipped in favor of newer one
8. **FAILED**: Deployment encountered errors
9. **CLEANUP**: Marked for deletion (EDR removed or invalid)
10. **DELETED**: Kubernetes resources removed

## Architecture Components

This section provides detailed technical documentation for each component in the Kafka Proxy Manager architecture.

### VaultDiscoveryService

**Package:** `org.eclipse.edc.kafkaproxy.vault`  
**File:** `VaultDiscoveryService.java`

Performs automatic discovery of Kafka EDRs in HashiCorp Vault with intelligent caching and deduplication.

#### Key Responsibilities:

- **Discovery Cycle Management**: Runs periodic discovery cycles every N seconds (configurable)
- **Secret Listing**: Lists all secrets from configured path using `vault.logical().list()`
  - Root level: `secret/` → lists `secret/edr--*`
  - Folder level: `secret/consumer/` → lists `secret/consumer/edr--*`
- **EDR Filtering**: Only processes secrets starting with configured prefix (default: `edr--`)
- **Type Validation**: Reads full secret content and validates `type` field equals `kafka`
- **Property Validation**: Checks for required Kafka properties:
  - `bootstrap.servers`: Kafka broker addresses
  - `topic`: Topic name for consumption
  - `security.protocol`: Security protocol (SASL_SSL, SSL, SASL_PLAINTEXT, PLAINTEXT)
  - SASL properties if applicable (mechanism, username, password/token)
  - TLS properties if applicable (ca.location, certificate paths)

#### Custom Metadata Tagging:

Uses Vault KV v2 custom_metadata API (HTTP POST to `/v1/secret/metadata/{path}`) to tag processed EDRs:

```json
{
  "custom_metadata": {
    "is_kafka": "true",                        // EDR type is kafka
    "kafka_ready_for_deployment": "true",      // Has valid properties
    "needs_tls": "true",                       // TLS certificates present
    "vault_checked_at": "2025-01-15T10:30:45Z", // ISO timestamp
    "processed_by": "edr-kafka-checker-java"   // Service identifier
  }
}
```

#### Efficient Deduplication:

1. **First Pass**: EDR without metadata → Full secret read, validation, metadata tagging
2. **Subsequent Passes**: 
   - Read metadata via HTTP GET to `/v1/secret/metadata/{path}`
   - Check `kafka_ready_for_deployment` flag
   - Skip full secret read if already processed (70% reduction in API calls)
   - Reconstruct `DiscoveryResult` from cached metadata

#### Key Methods:

- `performDiscoveryCycle()`: Main entry point for discovery cycle
- `isEdrAlreadyProcessed(String edrKey)`: Checks custom_metadata for processing status
- `checkAndTagEdrIfKafka(String edrKey)`: Validates and tags new EDRs
- `tagEdrForDeployment(String edrKey, ...)`: Writes custom_metadata via HTTP POST
- `getSecretListPath()`: Dynamic path generation with folder support
- `getSecretPath(String edrKey)`: Full secret path with folder support
- `getMetadataApiPath(String edrKey)`: HTTP API path for metadata operations

#### Configuration:

- `kafka.proxy.vault.addr`: Vault server address
- `kafka.proxy.vault.token`: Vault authentication token
- `kafka.proxy.vault.folder`: Vault folder for multi-tenant organization
- `kafka.proxy.discovery.interval`: Discovery cycle interval in seconds
- `kafka.proxy.edr.prefix`: EDR key prefix filter

#### Performance Optimizations:

- **Metadata-First Approach**: Checks lightweight metadata before reading full secrets
- **Property Caching**: Caches validated properties to avoid repeated parsing
- **Batch Listing**: Lists all EDRs in single API call, then processes individually
- **Conditional Reading**: Only reads full secret if metadata indicates changes or new EDR

### AutomaticDiscoveryQueueService

**Package:** `org.eclipse.edc.kafkaproxy.queue`  
**File:** `AutomaticDiscoveryQueueService.java`

Extends FileQueueService with automatic discovery integration and queue population.

#### Key Responsibilities:

- **Discovery Orchestration**: Invokes `VaultDiscoveryService.performDiscoveryCycle()`
- **Queue Population**: Converts `DiscoveryResult` to queue entries and writes to files
- **Orphan Detection**: Identifies deployed proxies that no longer have corresponding EDRs in Vault
- **Cleanup Queuing**: Adds orphaned proxies to cleanup queue for removal
- **Timestamp Management**: Maintains `last_update.txt` with ISO timestamp of last successful discovery

#### Discovery Flow:

```
1. Call VaultDiscoveryService.performDiscoveryCycle()
   ↓
2. Receive List<DiscoveryResult> (EDRs ready for deployment)
   ↓
3. For each result:
   - Extract edr_key and needs_tls flag
   - Write to kafka_edrs_ready.txt: "edr_key:needs_tls"
   ↓
4. Update last_update.txt with current timestamp
   ↓
5. Check for orphaned proxies (deployed but not in Vault)
   ↓
6. Add orphans to kafka_edrs_cleanup.txt
```

#### File Format:

**kafka_edrs_ready.txt** (deployment queue):
```
edr--12345678:true
edr--87654321:false
edr--abcdefgh:true
```

**kafka_edrs_cleanup.txt** (cleanup queue):
```
edr--orphaned-1
edr--orphaned-2
```

**last_update.txt** (timestamp tracking):
```
2025-01-15T10:30:45Z
```

#### Key Methods:

- `performAutomaticDiscovery()`: Main entry point, combines discovery + queue population
- `populateDeploymentQueue(List<DiscoveryResult>)`: Writes results to kafka_edrs_ready.txt
- `performOrphanedProxyCleanup()`: Identifies and queues orphaned proxies
- `updateLastUpdateTimestamp()`: Writes current timestamp to last_update.txt

#### Integration with Existing Queue System:

- Inherits from `FileQueueService` for queue file operations
- Uses same file-based queue format as manual deployment flow
- `KubernetesDeployerService` processes queues identically (automatic or manual)

#### Configuration:

- `kafka.proxy.shared.dir`: Base directory for queue files (default: `/shared`)
- `kafka.proxy.discovery.interval`: How often automatic discovery runs
- All VaultDiscoveryService configuration inherited

### VaultService

**Package:** `org.eclipse.edc.kafkaproxy.vault`  
**File:** `VaultService.java`

Unified vault client providing both discovery and property retrieval functionality.

#### Key Responsibilities:

- **EDR Discovery**: Integrates `VaultDiscoveryService` for automatic EDR discovery
- **Property Retrieval**: Retrieves and converts EDR secrets to `EdrProperties` objects
- **Cache Management**: Maintains separate caches for:
  - Discovery results (metadata + validation status)
  - EDR properties (full connection details)
- **OAuth2 Support**: Handles OAuth2 credential refresh and token management

#### Discovery Integration:

```java
// Discover all Kafka EDRs ready for deployment
List<DiscoveryResult> results = vaultService.discoverKafkaEdrs();

// Convert discovery result to full properties
EdrProperties props = vaultService.convertToEdrProperties(result);
```

#### Key Methods:

- `discoverKafkaEdrs()`: Delegates to VaultDiscoveryService.performDiscoveryCycle()
- `convertToEdrProperties(DiscoveryResult)`: Converts discovery result to full properties
- `getEdrPropertiesFromVault(String edrKey)`: Retrieves full EDR properties for deployment
- `getDeletedEdrKeys()`: Identifies EDRs removed from Vault (for cleanup)
- `refreshOauth2Credentials()`: Refreshes OAuth2 tokens if applicable

#### Cache Management:

- **Discovery Cache**: Lightweight, stores metadata and validation flags
- **Property Cache**: Full EDR connection details
- **Cache Coherence**: Discovery cache updated every discovery cycle, property cache on-demand
- **Cache Invalidation**: Automatically invalidates entries for deleted EDRs

#### Configuration:

- All VaultDiscoveryService configuration
- OAuth2 token endpoint configuration (if applicable)

### KubernetesDeployerService

**Package:** `org.eclipse.edc.kafkaproxy.k8s`  
**File:** `KubernetesDeployerService.java`

Deploys and manages Kubernetes resources for kafka-proxy instances with single-proxy enforcement.

#### Key Responsibilities:

- **Single-Proxy Enforcement**: Ensures only ONE proxy deployment exists at a time per participant
- **Deployment Lifecycle**: Creates, updates, and deletes Kubernetes Deployment resources
- **Service Management**: Creates/updates Kubernetes Service with STANDARDIZED, FIXED naming
- **Configuration Generation**: Generates proxy configuration with upstream Kafka details
- **Authentication Integration**: Injects authentication plugin containers if enabled
- **TLS Configuration**: Mounts TLS certificates from Kubernetes secrets
- **Resource Labeling**: Labels all resources for tracking, ownership, and management

#### Single-Proxy Pattern Implementation:

**Deployment Flow**:
1. **Find Existing Deployments**: Query all deployments with participant label
2. **Delete All Existing**: Remove all found deployments (ensures single proxy)
3. **Wait for Cleanup**: Brief wait (2s) for Kubernetes to complete deletion
4. **Create New Deployment**: Create deployment for new EDR with unique name
5. **Update Service**: Create/update service with FIXED name pointing to new deployment

**Naming Strategy**:
- **Deployment**: `kp-{participant}-{edr-short-id}` (EDR-specific, changes)
- **Service**: `kp-{participant}-service` (FIXED, never changes)
- **Port**: `30001` (FIXED for consistency)

**Benefits**:
- External load balancers register ONE fixed FQDN: `kp-consumer-service.default.svc.cluster.local:30001`
- No port conflicts (always same port)
- Clean switchover between EDRs (delete old, deploy new)
- Service selector automatically updated to new deployment

#### Deployment Resource Template:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kp-{participant}-{edr-short-id}  # EDR-specific
  labels:
    app: kp-{participant}-{edr-short-id}
    edr-id: edr--{full-uuid}
    component: kafka-proxy
    managed-by: edr-kubectl-deployer
    owner-participant: {safe-participant-id}
    owner-participant-full: {full-participant-id}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: kp-{participant}-{edr-short-id}
  template:
    spec:
      containers:
      - name: kafka-proxy
        image: grepplabs/kafka-proxy:0.4.2
        args:
          - server
          - --bootstrap-server-mapping=<upstream>:<port>,0.0.0.0:30001,kp-{participant}-service:30001
          - --dynamic-advertised-listener=kp-{participant}-service:30001
          [... TLS and auth configuration ...]
        ports:
        - containerPort: 30001
          name: proxy-port
```

#### Service Resource Template:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: kp-{participant}-service  # STANDARDIZED, FIXED
  labels:
    app: kp-{participant}-{edr-short-id}  # Points to current deployment
    edr-id: edr--{full-uuid}
    component: kafka-proxy
    managed-by: edr-kubectl-deployer
    owner-participant: {safe-participant-id}
spec:
  type: ClusterIP
  selector:
    app: kp-{participant}-{edr-short-id}  # Selector updated to match new deployment
  ports:
  - port: 30001  # FIXED
    targetPort: 30001
    name: proxy-port
```

**Service Update Mechanism**:
- Service name never changes: `kp-consumer-service`
- Service port never changes: `30001`
- Only the **selector** changes to point to new deployment
- Kubernetes automatically routes traffic to new pods when selector updates
- **Result**: Seamless switchover with no DNS changes required

#### Authentication Container Injection:

When `kafka.proxy.auth.enabled=true`, adds init container:

```yaml
initContainers:
- name: auth-plugin
  image: kafka-proxy-entra-auth:latest
  volumeMounts:
  - name: plugin-shared
    mountPath: /plugin
  command: ["cp", "/app/entra-token-verifier.so", "/plugin/"]
```

#### TLS Certificate Mounting:

When `kafka.proxy.tls.listener.enabled=true`, mounts secrets:

```yaml
volumeMounts:
- name: listener-cert
  mountPath: /tls/listener-cert.pem
  subPath: tls.crt
- name: listener-key
  mountPath: /tls/listener-key.pem
  subPath: tls.key
```

#### Key Methods:

- `deployProxy(EdrProperties props)`: Main deployment entry point with single-proxy enforcement
  - Finds and deletes all existing proxy deployments for participant
  - Creates new deployment with EDR-specific name
  - Updates service with fixed name pointing to new deployment
- `isProxyDeployed(String edrKey)`: Checks if specific EDR is currently deployed
- `deleteProxy(String edrKey)`: Deletes deployment for specific EDR
- `generateProxyName(String edrKey)`: Generates EDR-specific deployment name
- `generateServiceName(String edrKey)`: Returns FIXED service name (always same)
- `generateConsistentPort(String edrKey)`: Returns FIXED port (30001)
- `createDeployment(EdrProperties props)`: Generates and applies Deployment manifest
- `createService(String edrKey, String proxyName, String serviceName)`: Generates and applies Service manifest
- `generateProxyArgs(EdrProperties props)`: Builds kafka-proxy command arguments

#### Configuration:

- `kafka.proxy.namespace`: Target namespace for proxy resources
- `kafka.proxy.image`: Base kafka-proxy image
- `kafka.proxy.participant.id`: Participant identifier for ownership isolation
- `kafka.proxy.auth.enabled`, `kafka.proxy.auth.mechanism`, etc.: Authentication settings
- `kafka.proxy.tls.listener.enabled`, certificate secret names: TLS settings

### KubernetesCheckerService

**Package:** `org.eclipse.edc.kafkaproxy.k8s`  
**File:** `KubernetesCheckerService.java`

Monitors health and status of deployed kafka-proxy instances.

#### Key Responsibilities:

- **Deployment Status Checking**: Queries Kubernetes API for deployment status
- **Pod Health Monitoring**: Checks pod readiness and liveness
- **Failure Detection**: Identifies failed or crashlooping proxies
- **Status Reporting**: Generates health summaries and metrics
- **Cleanup Triggering**: Triggers cleanup for permanently failed proxies

#### Health Check Flow:

```
1. List all Deployments with label: app.kubernetes.io/managed-by=kafka-proxy-manager
   ↓
2. For each deployment:
   - Check replicas: available vs desired
   - Check pod status: Running, Pending, CrashLoopBackOff, Error
   - Check container status: ready, restart count
   ↓
3. Generate health summary:
   - Total managed proxies
   - Healthy proxies (all replicas ready)
   - Unhealthy proxies (not all replicas ready)
   - Failed proxies (error state)
   ↓
4. Log summary and update metrics
```

#### Key Methods:

- `checkProxyHealth()`: Main health check entry point
- `getDeploymentStatus(String edrKey)`: Gets status for specific proxy
- `isProxyHealthy(String edrKey)`: Boolean health check
- `generateHealthSummary()`: Aggregates health across all proxies
- `identifyFailedProxies()`: Returns list of proxies in failed state

#### Health States:

- **HEALTHY**: All replicas available, pods running, containers ready
- **DEPLOYING**: Replicas not yet available, pods pending or starting
- **UNHEALTHY**: Some replicas unavailable, pods restarting
- **FAILED**: Deployment in error state, CrashLoopBackOff, ImagePullBackOff

#### Configuration:

- `kafka.proxy.check.interval`: How often health checks run (same as deployment interval)
- `kafka.proxy.namespace`: Namespace to monitor

### KafkaProxyOrchestrator

**Package:** `org.eclipse.edc.kafkaproxy`  
**File:** `KafkaProxyOrchestrator.java`

Main orchestration service coordinating all manager components with scheduled execution and newest-EDR selection.

#### Key Responsibilities:

- **Discovery Scheduling**: Schedules automatic discovery at configured intervals
- **Deployment Scheduling**: Schedules deployment/cleanup processing at configured intervals
- **Newest-EDR Selection**: Implements single-proxy pattern by selecting only newest EDR
- **Component Coordination**: Wires together discovery, queue, deployer, and checker services
- **Lock Management**: Implements file-based locking for single-instance processing
- **Lifecycle Management**: Handles startup, shutdown, and graceful termination

#### Scheduled Tasks:

**Discovery Task** (every `kafka.proxy.discovery.interval` seconds):
```java
1. Acquire lock (if enabled)
2. Call AutomaticDiscoveryQueueService.performAutomaticDiscovery()
3. Log results
4. Release lock
```

**Deployment Task** (every `kafka.proxy.check.interval` seconds):
```java
1. Acquire lock (if enabled)
2. Read kafka_edrs_ready.txt (may contain multiple EDRs)
3. IF multiple EDRs queued:
   a. Call selectNewestEdr() to choose by creation timestamp
   b. Mark older EDRs as processed (prevent requeue)
   c. Deploy ONLY newest EDR
4. ELSE (single EDR):
   a. Deploy that EDR
5. For each deployment:
   - Call KubernetesDeployerService.deployProxy()
   - Update deployment status file
6. Read kafka_edrs_cleanup.txt
7. For each EDR in cleanup queue:
   - Call KubernetesDeployerService.deleteProxy()
   - Update cleanup status file
8. Call KubernetesCheckerService.checkProxyHealth()
9. Release lock
```

#### Lock Management:

Prevents multiple manager instances from processing simultaneously:

```java
class LockManager {
  private static final long LOCK_TIMEOUT = 5 * 60 * 1000; // 5 minutes
  
  boolean acquireLock() {
    File lockFile = new File(lockFilePath);
    if (lockFile.exists() && !isLockStale(lockFile)) {
      return false; // Another instance holds lock
    }
    lockFile.createNewFile();
    return true;
  }
  
  void releaseLock() {
    new File(lockFilePath).delete();
  }
}
```

#### Key Methods:

- `start()`: Initializes scheduled tasks and starts orchestration
- `stop()`: Gracefully shuts down scheduled tasks
- `processQueues()`: Main entry point for deployment/cleanup cycle
- `processDeploymentQueue()`: Processes deployment queue with newest-EDR selection
- `selectNewestEdr(List<EdrQueueEntry>)`: Selects newest EDR by timestamp
- `getEdrCreationTimestamp(String edrKey)`: Retrieves creation timestamp from Vault
- `processDeploymentEntry(EdrQueueEntry)`: Deploys single EDR
- `processCleanupQueue()`: Processes cleanup queue

#### Configuration:

- All component configuration (vault, kubernetes, auth, TLS)
- `kafka.proxy.enable.lock`: Enable/disable locking
- `kafka.proxy.lock.file.path`: Lock file location
- `kafka.proxy.discovery.interval`: How often to run discovery
- `kafka.proxy.check.interval`: How often to run deployment/cleanup

## Cross-Consumer Isolation and Participant Ownership

To prevent cross-consumer proxy deletion vulnerabilities in multi-tenant deployments, the manager implements participant-based ownership isolation.

### Ownership Model

Each deployed proxy is tagged with participant ownership labels:

```yaml
metadata:
  labels:
    owner-participant: consumer-example-com    # Safe participant ID
    owner-participant-full: did:web:consumer.example.com  # Full participant ID
    app.kubernetes.io/managed-by: kafka-proxy-manager
```

### Participant ID Configuration

The participant ID is determined in this order:
1. `kafka.proxy.participant.id` configuration property
2. `EDC_PARTICIPANT_ID` environment variable
3. `edc.participant.id` configuration property

### Safe Participant ID Generation

DID format participant IDs are converted to Kubernetes-safe identifiers:
- `did:web:consumer.example.com` → `consumer-example-com`
- `urn:connector:provider` → `provider`
- Simple IDs like `consumer1` remain unchanged

### Proxy Naming Convention

Proxies are named using participant-based prefixes:
- **Format**: `kafka-proxy-{participantId}-{edrKey}`
- **Example**: `kafka-proxy-consumer-example-com-edr--12345678`

### Isolation Benefits

1. **Cross-Consumer Protection**: Consumer A cannot delete Consumer B's proxies
2. **Ownership Tracking**: Each proxy clearly identifies its owner participant
3. **Multi-Tenant Support**: Multiple participants can safely share the same cluster
4. **Audit Trail**: Kubernetes labels provide ownership audit trail

### Configuration Example

```properties
# Consumer participant configuration
kafka.proxy.participant.id=did:web:consumer.example.com

# Alternative via environment variable
EDC_PARTICIPANT_ID=did:web:consumer.example.com
```

### Kubernetes Filtering

The checker service filters operations by participant ownership:
- Only queries proxies with matching `owner-participant` label
- Prevents interference with other participants' proxies
- Ensures health checks and metrics are participant-scoped

## Configuration Reference

The Kafka Proxy Kubernetes Manager is configured through the `configuration.properties` file, which is mounted via ConfigMap. The EDC runtime loads this file using the `EDC_FS_CONFIG` environment variable.

### Complete Configuration Variables

#### Vault Configuration

| Variable | Type | Default | Description |
|----------|------|---------|-------------|
| `kafka.proxy.vault.addr` | String | `http://vault:8200` | HashiCorp Vault server address. Used to connect to Vault API for EDR discovery and retrieval. Should include protocol (http/https) and port. |
| `kafka.proxy.vault.token` | String | `root` | Vault authentication token. Must have read permissions on `secret/data/*` and `secret/metadata/*`, plus update permission on `secret/metadata/*` for custom_metadata tagging. **Sensitive - protect in production!** |
| `kafka.proxy.vault.folder` | String | `""` (empty) | Vault folder for organizing EDR secrets. If empty, reads from `secret/edr--*`. If set to "consumer", reads from `secret/consumer/edr--*`. Enables multi-tenant deployments where different participants have separate vault folders. |
| `kafka.proxy.discovery.interval` | Integer | `10` | Discovery cycle interval in seconds. How often the service scans Vault for new/changed EDRs. Lower values = faster discovery but more Vault API calls. Recommended: 10-60 seconds. |
| `kafka.proxy.edr.prefix` | String | `edr--` | Prefix for EDR keys in Vault. Only secrets starting with this prefix are considered for discovery. Standard EDC format uses `edr--<uuid>`. |

**Example Vault Configuration:**
```properties
# Consumer instance reads from secret/consumer/
kafka.proxy.vault.addr=http://consumer-vault:8200
kafka.proxy.vault.token=hvs.CAES...
kafka.proxy.vault.folder=consumer
kafka.proxy.discovery.interval=10
kafka.proxy.edr.prefix=edr--
```

#### Kubernetes Configuration

| Variable | Type | Default | Description |
|----------|------|---------|-------------|
| `kafka.proxy.namespace` | String | `default` | Kubernetes namespace where proxy deployments and services are created. Manager must have RBAC permissions in this namespace. |
| `kafka.proxy.image` | String | `grepplabs/kafka-proxy:0.4.2` | Base kafka-proxy Docker image. Use official image for basic functionality or custom image with authentication plugins. |
| `kafka.proxy.shared.dir` | String | `/shared` | Shared directory for queue files. Must be mounted as PersistentVolume when running multiple manager instances. Contains deployment/cleanup queues and lock files. |
| `kafka.proxy.check.interval` | Integer | `15` | Deployment/cleanup processing interval in seconds. How often the service processes queued EDRs and performs deployments/cleanups. Recommended: 15-30 seconds. |
| `kafka.proxy.enable.lock` | Boolean | `true` | Enable file-based locking for single-instance processing. Prevents multiple manager instances from processing the same EDRs simultaneously. Disable only for testing single-instance deployments. |
| `kafka.proxy.lock.file.path` | String | `/tmp/kubectl-deployer.lock` | Path to lock file. Must be in a directory writable by the service. Lock is automatically released after 5 minutes if process crashes. |
| `kafka.proxy.kubeconfig.path` | String | N/A (optional) | Path to kubeconfig file for external cluster access. If not set, uses in-cluster ServiceAccount authentication (recommended for pod deployments). |
| `kafka.proxy.participant.id` | String | N/A (optional) | Participant identifier for proxy ownership isolation. Used to prevent cross-consumer proxy deletion by ensuring each participant only manages their own proxies. If not set, falls back to `edc.participant.id` environment variable. Critical for multi-tenant deployments. |
| `kafka.proxy.base.port` | Integer | `30001` | Base port for Kafka proxy service. This is the bootstrap port that clients connect to. |
| `kafka.proxy.max.broker.ports` | Integer | `20` | Maximum number of broker ports to expose for multi-broker Kafka clusters. The proxy exposes sequential ports starting from `baseProxyPort+1` up to `baseProxyPort+maxBrokerPorts` to handle individual broker connections. Set this to at least the number of brokers in your largest Kafka cluster. Default of 20 supports clusters with up to 20 brokers. |

**Example Kubernetes Configuration:**
```properties
kafka.proxy.namespace=default
kafka.proxy.image=grepplabs/kafka-proxy:0.4.2
kafka.proxy.shared.dir=/shared
kafka.proxy.check.interval=15
kafka.proxy.enable.lock=true
kafka.proxy.lock.file.path=/tmp/kubectl-deployer.lock
kafka.proxy.participant.id=did:web:consumer.example.com
kafka.proxy.base.port=30001
kafka.proxy.max.broker.ports=20
```

#### Downstream Authentication Configuration

These settings configure how **clients authenticate to the proxy's listener port** (not upstream Kafka authentication).

| Variable | Type | Default | Description |
|----------|------|---------|-------------|
| `kafka.proxy.auth.enabled` | Boolean | `false` | Enable authentication on proxy listener. When true, clients must authenticate before accessing the proxy. Requires custom authentication plugin image. |
| `kafka.proxy.auth.mechanism` | String | `PLAIN` | Authentication mechanism. `PLAIN` = Hybrid JWT-over-PLAIN (accepts JWT as password), `OAUTHBEARER` = Proper OAuth2 flow (implements TokenInfo interface). |
| `kafka.proxy.auth.tenant.id` | String | N/A | Microsoft Entra ID (Azure AD) tenant ID. Required for JWT validation. Format: UUID (e.g., `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`). |
| `kafka.proxy.auth.client.id` | String | N/A | Application client ID registered in Entra ID. Used to validate JWT audience claim. Format: UUID . |
| `kafka.proxy.auth.static.users` | String | `""` | Static users for fallback authentication. Format: `username1:password1,username2:password2`. **Only works with PLAIN mechanism.** Example: `admin:admin-secret`. **Sensitive - for testing only!** |
| `kafka.proxy.auth.image` | String | `kafka-proxy-entra-auth:latest` | Custom Docker image with authentication plugins. Must contain `entra-token-verifier` (for PLAIN) or `entra-token-info` (for OAUTHBEARER) plugins. See [Building Authentication Plugin](#building-authentication-plugin). |

**Example Authentication Configuration:**
```properties
# Enable JWT-over-PLAIN authentication
kafka.proxy.auth.enabled=true
kafka.proxy.auth.mechanism=PLAIN
kafka.proxy.auth.tenant.id=<your-tenant-id>
kafka.proxy.auth.client.id=<your-client-id>
kafka.proxy.auth.static.users=admin:admin-secret
kafka.proxy.auth.image=localhost/kafka-proxy-entra-auth:latest
```

#### TLS Listener Configuration

These settings configure TLS encryption for the **proxy's listener port** (client-to-proxy connection).

| Variable | Type | Default | Description |
|----------|------|---------|-------------|
| `kafka.proxy.tls.listener.enabled` | Boolean | `false` | Enable TLS on proxy listener. When true, clients must connect using `security.protocol=SASL_SSL` or `SSL`. Requires TLS certificates in Kubernetes secrets. |
| `kafka.proxy.tls.listener.cert.secret` | String | N/A | Kubernetes secret name containing listener TLS certificate (`tls.crt` key). Certificate is presented to clients during TLS handshake. Must be in same namespace as proxy deployment. |
| `kafka.proxy.tls.listener.key.secret` | String | N/A | Kubernetes secret name containing listener TLS private key (`tls.key` key). Must match the certificate. Typically same secret as cert. |
| `kafka.proxy.tls.listener.ca.secret` | String | `""` | Kubernetes secret name containing CA certificate for client verification. If empty, **mutual TLS is disabled** (server-only authentication). If set, clients must present valid certificates signed by this CA. |

**Example TLS Configuration:**
```properties
# Enable TLS listener (server-only authentication)
kafka.proxy.tls.listener.enabled=true
kafka.proxy.tls.listener.cert.secret=consumer-kafka-proxy-listener-tls
kafka.proxy.tls.listener.key.secret=consumer-kafka-proxy-listener-tls
kafka.proxy.tls.listener.ca.secret=
```

**Note on TLS Certificates:**
- Certificates must include appropriate SANs (Subject Alternative Names) for dynamically created service names
- Use wildcard DNS: `*.default.svc.cluster.local` or full FQDNs
- See [TLS_CLIENT_SETUP.md](TLS_CLIENT_SETUP.md) for client configuration details

#### EDC Runtime Configuration

| Variable | Type | Default | Description |
|----------|------|---------|-------------|
| `edc.runtime.id` | String | `kafka-proxy-k8s-manager` | EDC runtime identifier. Used in logs and for runtime identification. |
| `web.http.port` | Integer | `8080` | HTTP port for API endpoints. Used for health checks and management APIs. |
| `web.http.path` | String | `/api` | Base path for API endpoints. |
| `web.http.management.port` | Integer | `8081` | HTTP port for management endpoints. Separate from API port for security isolation. |
| `web.http.management.path` | String | `/management` | Base path for management endpoints. |

**Example EDC Configuration:**
```properties
edc.runtime.id=consumer-kafka-proxy-manager
web.http.port=8080
web.http.path=/api
web.http.management.port=8081
web.http.management.path=/management
```

### Configuration Template

Complete `configuration.properties` template:

```properties
# ============================================
# Vault Configuration
# ============================================
kafka.proxy.vault.addr=http://consumer-vault:8200
kafka.proxy.vault.token=<vault-token>
kafka.proxy.vault.folder=consumer
kafka.proxy.discovery.interval=10
kafka.proxy.edr.prefix=edr--

# ============================================
# Kubernetes Configuration
# ============================================
kafka.proxy.namespace=default
kafka.proxy.image=grepplabs/kafka-proxy:0.4.2
kafka.proxy.shared.dir=/shared
kafka.proxy.check.interval=15
kafka.proxy.enable.lock=true
kafka.proxy.lock.file.path=/tmp/kubectl-deployer.lock
kafka.proxy.participant.id=did:web:consumer.example.com
kafka.proxy.base.port=30001
kafka.proxy.max.broker.ports=20

# ============================================
# Downstream Authentication
# ============================================
kafka.proxy.auth.enabled=true
kafka.proxy.auth.mechanism=PLAIN
kafka.proxy.auth.tenant.id=<tenant-id>
kafka.proxy.auth.client.id=<client-id>
kafka.proxy.auth.static.users=admin:admin-secret
kafka.proxy.auth.image=localhost/kafka-proxy-entra-auth:latest

# ============================================
# TLS Listener Configuration
# ============================================
kafka.proxy.tls.listener.enabled=true
kafka.proxy.tls.listener.cert.secret=consumer-kafka-proxy-listener-tls
kafka.proxy.tls.listener.key.secret=consumer-kafka-proxy-listener-tls
kafka.proxy.tls.listener.ca.secret=

# ============================================
# EDC Runtime Configuration
# ============================================
edc.runtime.id=consumer-kafka-proxy-manager
web.http.port=8080
web.http.path=/api
web.http.management.port=8081
web.http.management.path=/management
```

## Vault Folder Organization

The service supports organizing EDR secrets in Vault folders for multi-tenant deployments:

### Root Level (Default)
```
secret/
├── edr--<id-1>         # Consumer EDR
├── edr--<id-2>         # Consumer EDR
└── edr--<id-3>         # Consumer EDR
```

Configuration: `kafka.proxy.vault.folder=` (empty)

### Folder-Based Organization
```
secret/
├── consumer/
│   ├── edr--<id-1>
│   ├── edr--<id-2>
│   └── edr--<id-3>
└── provider/
    ├── edr--<id-4>
    ├── edr--<id-5>
    └── edr--<id-6>
```

Configuration: `kafka.proxy.vault.folder=consumer`

This allows deploying separate proxy managers for different participants, each reading from their own vault folder.

## Custom Metadata for Deduplication

The service uses Vault KV v2 custom_metadata to efficiently track EDR processing status without repeatedly reading full secret contents.

### Metadata Structure

Each EDR in Vault has custom_metadata attached:

```json
{
  "custom_metadata": {
    "is_kafka": "true",
    "kafka_ready_for_deployment": "true",
    "needs_tls": "true",
    "vault_checked_at": "2025-10-06T09:08:10.855667302Z",
    "processed_by": "edr-kafka-checker-java"
  }
}
```

### Deduplication Benefits

1. **First Discovery**: EDR without metadata → Full secret read, validation, metadata tagging
2. **Subsequent Discoveries**: 
   - Metadata exists → Check `kafka_ready_for_deployment` flag
   - Skip full secret read if already processed
   - Reconstruct result from metadata + cached properties
3. **Efficiency**: Reduces Vault API calls by ~70% in steady-state operation

### Viewing Metadata

```bash
# View custom_metadata for an EDR
vault kv metadata get secret/edr--<id>

# View custom_metadata for EDR in folder
vault kv metadata get secret/consumer/edr--<id>
```

## File Structure

The automatic discovery uses the following file structure for queue management:

```
/shared/
├── kafka_edrs_ready.txt       # EDRs ready for deployment (format: edr_key:needs_tls)
├── kafka_edrs_cleanup.txt     # EDRs to cleanup
├── last_update.txt            # Last discovery update timestamp
├── kafka_edr_*_deployment_status.txt  # Individual deployment status files
└── kafka_edr_*_cleanup_status.txt     # Individual cleanup status files
```

## Authentication Mechanisms

The service supports two authentication mechanisms for downstream clients:

### PLAIN Mechanism (Hybrid JWT-over-PLAIN)

Uses the `entra-token-verifier` plugin:
- Client sends username (any value) and JWT token as password
- Plugin validates JWT token against Azure Entra ID
- Supports static username/password fallback for testing

**Use case**: Clients that can't implement OAuth2 OAUTHBEARER but can send JWT in password field

### OAUTHBEARER Mechanism (Proper OAuth2)

Uses the `entra-token-info` plugin:
- Client implements proper OAuth2 OAUTHBEARER SASL mechanism
- Plugin validates bearer tokens via TokenInfo interface
- No static user fallback (OAuth2 only)

**Use case**: Clients with full OAuth2 support (recommended for production)

### Authentication Plugin Image

The authentication plugins must be available as a custom kafka-proxy image:

```dockerfile
# Example structure
kafka-proxy-entra-auth:latest
├── kafka-proxy (binary)
├── plugin/
│   ├── entra-token-verifier.so    # For PLAIN mechanism
│   └── entra-token-info.so        # For OAUTHBEARER mechanism
```

Build and load this image to your Kubernetes cluster, then configure:
```properties
kafka.proxy.auth.image=localhost/kafka-proxy-entra-auth:latest
```

## Deployment Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Kafka Proxy K8s Manager (Automatic Discovery)              │
│  ┌────────────────┐      ┌──────────────────┐              │
│  │ Discovery Loop │─────▶│ VaultDiscovery   │              │
│  │  (10s cycle)   │      │     Service      │              │
│  └────────────────┘      └──────────────────┘              │
│                                    │                         │
│                                    ▼                         │
│  ┌────────────────────────────────────────────────┐         │
│  │  HashiCorp Vault (KV v2)                       │         │
│  │  secret/consumer/                              │         │
│  │    ├── edr--<id-1> (+ custom_metadata)        │         │
│  │    ├── edr--<id-2> (+ custom_metadata)        │         │
│  │    └── edr--<id-3> (+ custom_metadata)        │         │
│  └────────────────────────────────────────────────┘         │
│                                    │                         │
│                                    ▼                         │
│  ┌────────────────┐      ┌──────────────────┐              │
│  │ Queue Manager  │─────▶│ K8s Deployer     │              │
│  │ (/shared)      │      │   Service        │              │
│  └────────────────┘      └──────────────────┘              │
│                                    │                         │
└────────────────────────────────────┼─────────────────────────┘
                                     ▼
                    ┌─────────────────────────────────┐
                    │  Kubernetes Cluster             │
                    │  ┌───────────────────────────┐  │
                    │  │ kafka-proxy-edr--<id-1>   │  │
                    │  │  + entra-auth (optional)  │  │
                    │  └───────────────────────────┘  │
                    │  ┌───────────────────────────┐  │
                    │  │ kafka-proxy-edr--<id-2>   │  │
                    │  │  + TLS (optional)         │  │
                    │  └───────────────────────────┘  │
                    └─────────────────────────────────┘
```

## Security Considerations

### Vault Access
- **Token Permissions**: The Vault token must have:
  - `read` permission on `secret/data/<folder>/*` (or `secret/data/*` for root level)
  - `read` permission on `secret/metadata/<folder>/*` for custom_metadata reading
  - `update` permission on `secret/metadata/<folder>/*` for custom_metadata tagging
- **Network Access**: The service needs network access to Vault API
- **Token Rotation**: Implement vault token rotation for production deployments

### Kubernetes Access
- **ServiceAccount**: The service needs a ServiceAccount with RBAC permissions:
  - `deployments`: create, read, update, delete
  - `services`: create, read, delete
  - `configmaps`: create, read (for proxy configuration)
  - `secrets`: read (for TLS certificates)
- **Namespace Isolation**: Deploy proxies in dedicated namespaces for isolation

### Authentication Secrets
- **Azure Entra ID**: Protect tenant ID and client ID configuration
- **Static Users**: Avoid using static users in production; use OAuth2 mechanisms only
- **JWT Validation**: The authentication plugins validate JWT signatures and claims

### File System
- **Shared Directory**: Must be writable by the service user
- **Lock Files**: Used for single-instance coordination
- **Queue Files**: Contain EDR keys but not sensitive data

### Network Security
- **TLS**: Enable TLS on proxy listeners for production traffic
- **Network Policies**: Restrict traffic between components
- **Mutual TLS**: Consider mutual TLS for upstream Kafka connections

## Implementation Details

### Vault Folder Integration

The service fully integrates vault folder support throughout all vault operations:

**Configuration Loading:**
- Property: `kafka.proxy.vault.folder` (empty = root level, "consumer" = secret/consumer/)
- Default: Empty string (root level)
- Loaded via EDC_FS_CONFIG environment variable

**Path Generation:**
The service uses dynamic path generation methods that respect the folder configuration:

```java
// List path: secret/ or secret/consumer/
private String getSecretListPath()

// Secret path: secret/edr--<id> or secret/consumer/edr--<id>  
private String getSecretPath(String edrKey)

// Metadata API path: /v1/secret/metadata/edr--<id> or /v1/secret/metadata/consumer/edr--<id>
private String getMetadataApiPath(String edrKey)
```

**Operations Using Folder-Aware Paths:**
1. **Listing EDRs**: `vault.logical().list(getSecretListPath())`
2. **Reading Secrets**: `vault.logical().read(getSecretPath(edrKey))`
3. **Reading Metadata**: HTTP GET to `vaultAddress + getMetadataApiPath(edrKey)`
4. **Writing Metadata**: HTTP POST to `vaultAddress + getMetadataApiPath(edrKey)`

**Log Verification:**
Check logs to confirm folder configuration:
```bash
kubectl logs <pod> | grep "Vault Folder"
# Output: "Vault Folder: (root)" or "Vault Folder: consumer"
```

### Custom Metadata Implementation

**Write Operation (HTTP POST):**
```java
POST {vaultAddress}/v1/secret/metadata/{folder}/{edrKey}
Headers: X-Vault-Token, Content-Type: application/json
Body: {
  "custom_metadata": {
    "is_kafka": "true",
    "kafka_ready_for_deployment": "true",
    "needs_tls": "true",
    "vault_checked_at": "2025-10-06T09:08:10Z",
    "processed_by": "edr-kafka-checker-java"
  }
}
Response: HTTP 204 (success)
```

**Read Operation (HTTP GET):**
```java
GET {vaultAddress}/v1/secret/metadata/{folder}/{edrKey}
Headers: X-Vault-Token
Response: {
  "data": {
    "custom_metadata": {...},
    "created_time": "...",
    "updated_time": "..."
  }
}
```

**Testing Kafka Transfer**
1. **Check kp pod status** : 'kcat -L -b <kp-consumer pod name with service> -L -X security.protocol=SASL_PLAINTEXT -X sasl.mechanism=PLAIN -X sasl.username=admin -X sasl.password=admin-secret' 
2. **Provider message publishing** : 'kcat -b  <broker-pod> \ -t tst-topic \ -P \ -X security.protocol=PLAINTEXT'
3. **Consumer message consumption** : 'kcat -b <kp-consumer pod>  \ -t tst-topic \ -C \ -o beginning \ -X security.protocol=SASL_PLAINTEXT \ -X sasl.mechanism=PLAIN \ -X sasl.username=admin \ -X sasl.password=admin-secret'