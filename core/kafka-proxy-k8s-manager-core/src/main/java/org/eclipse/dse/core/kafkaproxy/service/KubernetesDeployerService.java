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

package org.eclipse.dse.core.kafkaproxy.service;

import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.eclipse.dse.core.kafkaproxy.model.DeploymentStatus;
import org.eclipse.dse.core.kafkaproxy.model.EdrProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static java.lang.String.format;
import static org.eclipse.dse.core.kafkaproxy.config.KafkaProxyConfig.LARGEST_AVAILABLE_PORT;

/**
 * Service for managing Kubernetes deployments of Kafka proxies
 */
public class KubernetesDeployerService {

    private static final Logger LOGGER = Logger.getLogger(KubernetesDeployerService.class.getName());

    private final KubernetesClient kubernetesClient;
    private final String proxyNamespace;
    private final String proxyImage;
    private final int baseProxyPort;
    private final VaultService vaultService;
    private final String participantId;
    private final String clusterIp;  // Optional fixed ClusterIP address
    
    // Authentication configuration (supports PLAIN and OAUTHBEARER mechanisms)
    private final boolean authEnabled;
    private final String authMechanism;  // PLAIN or OAUTHBEARER
    private final String authTenantId;
    private final String authClientId;
    private final String authStaticUsers;  // Only used for PLAIN mechanism
    private final String authImage;
    
    // TLS listener configuration
    private final boolean tlsListenerEnabled;
    private final String tlsListenerCertSecret;
    private final String tlsListenerKeySecret;
    private final String tlsListenerCaSecret;
    
    // Additional pod labels from configuration
    private final Map<String, String> additionalPodLabels;
    
    // Maximum number of broker ports to expose (for multi-broker clusters)
    private final int maxBrokerPorts;

    public KubernetesDeployerService(KubernetesClient kubernetesClient, String proxyNamespace, String proxyImage, 
                                   VaultService vaultService, String participantId, String clusterIp, int baseProxyPort, boolean authEnabled, String authMechanism,
                                   String authTenantId, String authClientId, String authStaticUsers, String authImage,
                                   boolean tlsListenerEnabled, String tlsListenerCertSecret, 
                                   String tlsListenerKeySecret, String tlsListenerCaSecret,
                                   Map<String, String> additionalPodLabels, int maxBrokerPorts) {
        this.kubernetesClient = kubernetesClient;
        this.proxyNamespace = proxyNamespace;
        this.proxyImage = proxyImage;
        this.vaultService = vaultService;
        this.participantId = participantId;
        this.clusterIp = clusterIp;
        this.baseProxyPort = baseProxyPort;
        this.authEnabled = authEnabled;
        this.authMechanism = authMechanism != null ? authMechanism : "PLAIN";
        this.authTenantId = authTenantId;
        this.authClientId = authClientId;
        this.authStaticUsers = authStaticUsers;
        this.authImage = authImage;
        this.tlsListenerEnabled = tlsListenerEnabled;
        this.tlsListenerCertSecret = tlsListenerCertSecret;
        this.tlsListenerKeySecret = tlsListenerKeySecret;
        this.tlsListenerCaSecret = tlsListenerCaSecret;
        this.additionalPodLabels = additionalPodLabels != null ? new HashMap<>(additionalPodLabels) : new HashMap<>();
        this.maxBrokerPorts = maxBrokerPorts;
    }
    
    /**
     * Deploys a Kafka proxy for the given EDR key and properties.
     * Deletes any existing proxy deployment and creates a new one.
     * Updates the standardized service to point to the new deployment.
     */
    public DeploymentStatus deployProxy(String edrKey, EdrProperties properties) {
        try {
            String proxyName = generateProxyName(edrKey);
            String serviceName = generateServiceName(edrKey);
            LOGGER.info(format("Deploying Kafka proxy: %s (service: %s) for EDR: %s", proxyName, serviceName, edrKey));
            
            // Validate properties
            if (properties.getBootstrapServers() == null || properties.getBootstrapServers().isEmpty()) {
                String message = format("Missing bootstrap servers for EDR: %s", edrKey);
                LOGGER.severe(message);
                return new DeploymentStatus(edrKey, DeploymentStatus.Status.FAILED, message);
            }
            
            // Find and delete any existing proxy deployments for this participant
            String safeParticipantId = generateSafeParticipantId(participantId);
            var existingDeployments = kubernetesClient.apps().deployments()
                    .inNamespace(proxyNamespace)
                    .withLabel("owner-participant", safeParticipantId)
                    .withLabel("component", "kafka-proxy")
                    .list()
                    .getItems();
            
            for (var existingDeployment : existingDeployments) {
                String oldDeploymentName = existingDeployment.getMetadata().getName();
                String oldEdrKey = existingDeployment.getMetadata().getLabels().get("edr-id");
                
                LOGGER.info(format("Deleting existing deployment: %s (EDR: %s) before deploying new one", 
                        oldDeploymentName, oldEdrKey));
                
                kubernetesClient.apps().deployments()
                        .inNamespace(proxyNamespace)
                        .withName(oldDeploymentName)
                        .delete();
                
                // Do NOT untag old EDR to avoid requeuing it
                LOGGER.info(format("Deleted deployment for EDR: %s (vault tag kept to prevent requeuing)", oldEdrKey));
            }
            
            // Wait for deletion to complete if there were existing deployments
            if (!existingDeployments.isEmpty()) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.warning("Thread interrupted while waiting for proxy deletion");
                }
                LOGGER.info("Old deployments deleted successfully");
            }
            
            // Create secret for sensitive credentials before creating deployment
            createProxySecret(edrKey, properties);
            
            // Create new deployment
            Deployment deployment = createDeployment(edrKey, proxyName, properties);
            kubernetesClient.resource(deployment).inNamespace(proxyNamespace).create();
            
            // Create or update standardized service (service name stays the same)
            Service service = createService(edrKey, proxyName, serviceName);
            try {
                var existingService = kubernetesClient.services()
                        .inNamespace(proxyNamespace)
                        .withName(serviceName)
                        .get();
                if (existingService != null) {
                    kubernetesClient.resource(service).inNamespace(proxyNamespace).update();
                } else {
                    kubernetesClient.resource(service).inNamespace(proxyNamespace).create();
                }
            } catch (Exception e) {
                kubernetesClient.resource(service).inNamespace(proxyNamespace).create();
            }
            
            LOGGER.info(format("Successfully deployed Kafka proxy: %s with service: %s for EDR: %s", 
                    proxyName, serviceName, edrKey));
            
            // Tag the secret as deployed in vault
            boolean tagged = vaultService.tagSecretAsDeployed(edrKey, proxyName, true);
            if (!tagged) {
                LOGGER.warning(format("Failed to tag secret as deployed for EDR: %s", edrKey));
            }
            
            return new DeploymentStatus(edrKey, DeploymentStatus.Status.DEPLOYED, 
                    format("Proxy deployed successfully as %s", proxyName));
            
        } catch (Exception e) {
            String message = format("Failed to deploy proxy for EDR %s: %s", edrKey, e.getMessage());
            LOGGER.severe(message);
            return new DeploymentStatus(edrKey, DeploymentStatus.Status.FAILED, message);
        }
    }
    
    /**
     * Checks if a proxy is deployed for the given EDR key.
     * Looks for any deployment with matching participant and EDR labels.
     */
    public boolean isProxyDeployed(String edrKey) {
        try {
            String safeParticipantId = generateSafeParticipantId(participantId);
            
            // Look for deployments with matching participant and EDR
            var deployments = kubernetesClient.apps().deployments()
                    .inNamespace(proxyNamespace)
                    .withLabel("owner-participant", safeParticipantId)
                    .withLabel("component", "kafka-proxy")
                    .withLabel("edr-id", edrKey)
                    .list()
                    .getItems();
            
            if (deployments.isEmpty()) {
                return false;
            }
            
            // Check if deployment is ready
            for (var deployment : deployments) {
                Integer readyReplicas = deployment.getStatus().getReadyReplicas();
                Integer replicas = deployment.getSpec().getReplicas();
                if (readyReplicas != null && readyReplicas.equals(replicas)) {
                    LOGGER.info(format("Proxy deployment found and ready for EDR: %s", edrKey));
                    return true;
                }
            }
            
            return false;
            
        } catch (KubernetesClientException e) {
            LOGGER.warning(format("Error checking deployment status for EDR %s: %s", edrKey, e.getMessage()));
            return false;
        }
    }
    
    /**
     * Deletes the proxy deployment for the given EDR key.
     * Finds deployment by labels and deletes it. Service remains for future deployments.
     */
    public DeploymentStatus deleteProxy(String edrKey) {
        try {
            String safeParticipantId = generateSafeParticipantId(participantId);
            LOGGER.info(format("Deleting Kafka proxy for EDR: %s", edrKey));
            
            // Find deployments with matching participant and EDR
            var deployments = kubernetesClient.apps().deployments()
                    .inNamespace(proxyNamespace)
                    .withLabel("owner-participant", safeParticipantId)
                    .withLabel("component", "kafka-proxy")
                    .withLabel("edr-id", edrKey)
                    .list()
                    .getItems();
            
            boolean anyDeleted = false;
            for (var deployment : deployments) {
                String proxyName = deployment.getMetadata().getName();
                LOGGER.info(format("Deleting deployment: %s for EDR: %s", proxyName, edrKey));
                
                var deleteResult = kubernetesClient.apps().deployments()
                        .inNamespace(proxyNamespace)
                        .withName(proxyName)
                        .delete();
                
                if (deleteResult != null && !deleteResult.isEmpty()) {
                    anyDeleted = true;
                    LOGGER.info(format("Successfully deleted deployment: %s", proxyName));
                }
            }
            
            // Delete associated secret
            String secretName = generateSecretName(edrKey);
            try {
                var secretDeleted = kubernetesClient.secrets()
                        .inNamespace(proxyNamespace)
                        .withName(secretName)
                        .delete();
                if (secretDeleted != null && !secretDeleted.isEmpty()) {
                    LOGGER.info(format("Successfully deleted secret: %s", secretName));
                }
            } catch (Exception e) {
                LOGGER.warning(format("Failed to delete secret %s: %s", secretName, e.getMessage()));
            }
            
            if (anyDeleted) {
                return new DeploymentStatus(edrKey, DeploymentStatus.Status.DELETED, 
                        format("Proxy deleted successfully for EDR: %s", edrKey));
            } else {
                LOGGER.warning(format("No proxy deployment found for EDR: %s", edrKey));
                return new DeploymentStatus(edrKey, DeploymentStatus.Status.DELETED, 
                        format("No proxy found for EDR: %s", edrKey));
            }
            
        } catch (Exception e) {
            String message = format("Failed to delete proxy for EDR %s: %s", edrKey, e.getMessage());
            LOGGER.severe(message);
            return new DeploymentStatus(edrKey, DeploymentStatus.Status.FAILED, message);
        }
    }
    
    private Deployment createDeployment(String edrKey, String proxyName, EdrProperties properties) {
        String cleanBootstrapServers = properties.getBootstrapServers()
                .replace("https://", "")
                .replace("http://", "")
                .replace("PLAINTEXT://", "")
                .replace("SSL://", "")
                .replace("SASL_PLAINTEXT://", "")
                .replace("SASL_SSL://", "");
        
        boolean needsTls = properties.isTlsEnabled();
        
        LOGGER.info(format("Creating deployment for %s - TLS: %s, Protocol: %s", 
                proxyName, needsTls, properties.getSecurityProtocol()));
        LOGGER.info(format("Bootstrap servers for %s: %s", proxyName, cleanBootstrapServers));
        
        // Create labels with required Kubernetes standard labels
        Map<String, String> labels = new HashMap<>();
        
        // Required standard Kubernetes labels for admission webhook
        labels.put("app.kubernetes.io/name", "kafka-proxy");
        labels.put("app.kubernetes.io/instance", proxyName);
        labels.put("app.kubernetes.io/component", "proxy");
        labels.put("app.kubernetes.io/managed-by", "kafka-proxy-k8s-manager");
        
        // Custom labels for identification and management
        labels.put("app", proxyName);
        labels.put("edr-id", edrKey);
        labels.put("component", "kafka-proxy");
        labels.put("managed-by", "edr-kubectl-deployer");
        labels.put("owner-participant", generateSafeParticipantId(participantId));
        // For full participant ID, we need to make it Kubernetes-safe while preserving info
        labels.put("owner-participant-full", generateSafeLabelValue(participantId));
        labels.put("tls-enabled", String.valueOf(needsTls));
        labels.put("security-protocol", properties.getSecurityProtocol());
        labels.put("auth-enabled", String.valueOf(authEnabled));
        labels.put("auth-mechanism", authMechanism);
        
        // Add additional pod labels from Helm chart configuration
        if (additionalPodLabels != null && !additionalPodLabels.isEmpty()) {
            labels.putAll(additionalPodLabels);
            LOGGER.info(format("Added %d additional pod labels from configuration", additionalPodLabels.size()));
        }
        
        // Build container arguments
        var args = buildContainerArgs(edrKey, cleanBootstrapServers, properties);
        
        // Use fixed port
        int port = generateConsistentPort(edrKey);
        
        return new DeploymentBuilder()
                .withNewMetadata()
                    .withName(proxyName)
                    .withNamespace(proxyNamespace)
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(1)
                    .withNewSelector()
                        .withMatchLabels(Map.of("app", proxyName))
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(labels)
                        .endMetadata()
                        .withNewSpec()
                            .addNewContainer()
                                .withName("kafka-proxy")
                                .withImage(authEnabled && authImage != null ? authImage : proxyImage)
                                .withImagePullPolicy("IfNotPresent")
                                .withPorts(createContainerPorts(port))
                                .withArgs(args)
                                .withEnv(createEnvironmentVariables(edrKey, properties))
                                .withVolumeMounts(createVolumeMounts(properties))
                            .endContainer()
                            .withVolumes(createVolumes(properties))
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }
    
    /**
     * Creates container port definitions for the proxy.
     * Includes the base port plus sequential ports for each broker in the cluster.
     */
    private java.util.List<io.fabric8.kubernetes.api.model.ContainerPort> createContainerPorts(int basePort) {
        var ports = new java.util.ArrayList<io.fabric8.kubernetes.api.model.ContainerPort>();
        
        // Add base bootstrap port
        ports.add(new ContainerPortBuilder()
                .withContainerPort(basePort)
                .withName("proxy-port")
                .withProtocol("TCP")
                .build());
        
        // Add sequential ports for each broker (starting from basePort+1)
        for (int i = 1; i <= maxBrokerPorts && basePort + maxBrokerPorts <= LARGEST_AVAILABLE_PORT; i++) {
            ports.add(new ContainerPortBuilder()
                    .withContainerPort(basePort + i)
                    .withName(format("broker-port-%d", i))
                    .withProtocol("TCP")
                    .build());
        }
        
        LOGGER.info(format("Exposing %d container ports: base port %d + %d broker ports (%d-%d)",
                ports.size(), basePort, maxBrokerPorts, basePort + 1, basePort + maxBrokerPorts));
        
        return ports;
    }
    
    /**
     * Creates service port definitions for the proxy.
     * Includes the base port plus sequential ports for each broker in the cluster.
     */
    private java.util.List<io.fabric8.kubernetes.api.model.ServicePort> createServicePorts(int basePort) {
        var ports = new java.util.ArrayList<io.fabric8.kubernetes.api.model.ServicePort>();
        
        // Add base bootstrap port
        ports.add(new ServicePortBuilder()
                .withName("proxy-port")
                .withPort(basePort)
                .withTargetPort(new IntOrString(basePort))
                .withProtocol("TCP")
                .build());
        
        // Add sequential ports for each broker (starting from basePort+1)
        for (int i = 1; i <= maxBrokerPorts && basePort + maxBrokerPorts <= LARGEST_AVAILABLE_PORT; i++) {
            ports.add(new ServicePortBuilder()
                    .withName(format("broker-port-%d", i))
                    .withPort(basePort + i)
                    .withTargetPort(new IntOrString(basePort + i))
                    .withProtocol("TCP")
                    .build());
        }
        
        LOGGER.info(format("Exposing %d service ports: base port %d + %d broker ports (%d-%d)",
                ports.size(), basePort, maxBrokerPorts, basePort + 1, basePort + maxBrokerPorts));
        
        return ports;
    }
    
    private java.util.List<io.fabric8.kubernetes.api.model.EnvVar> createEnvironmentVariables(String edrKey, EdrProperties properties) {
        var envVars = new java.util.ArrayList<io.fabric8.kubernetes.api.model.EnvVar>();
        String secretName = generateSecretName(edrKey);
        
        // Add environment variables from secret for sensitive data
        if ("PLAIN".equals(properties.getSaslMechanism())) {
            // SASL PLAIN credentials
            envVars.add(new EnvVarBuilder()
                    .withName("SASL_USERNAME")
                    .withNewValueFrom()
                        .withNewSecretKeyRef()
                            .withName(secretName)
                            .withKey("sasl-username")
                        .endSecretKeyRef()
                    .endValueFrom()
                    .build());
            envVars.add(new EnvVarBuilder()
                    .withName("SASL_PASSWORD")
                    .withNewValueFrom()
                        .withNewSecretKeyRef()
                            .withName(secretName)
                            .withKey("sasl-password")
                        .endSecretKeyRef()
                    .endValueFrom()
                    .build());
        } else if ("OAUTHBEARER".equals(properties.getSaslMechanism()) && properties.hasOauth2Credentials()) {
            // OAuth2 client secret
            envVars.add(new EnvVarBuilder()
                    .withName("OAUTH2_CLIENT_SECRET")
                    .withNewValueFrom()
                        .withNewSecretKeyRef()
                            .withName(secretName)
                            .withKey("oauth2-client-secret")
                        .endSecretKeyRef()
                    .endValueFrom()
                    .build());
        }
        
        // Add auth static users if configured
        if (authEnabled && authStaticUsers != null && !authStaticUsers.isEmpty()) {
            envVars.add(new EnvVarBuilder()
                    .withName("AUTH_STATIC_USERS")
                    .withNewValueFrom()
                        .withNewSecretKeyRef()
                            .withName(secretName)
                            .withKey("auth-static-users")
                        .endSecretKeyRef()
                    .endValueFrom()
                    .build());
        }
        
        return envVars;
    }
    
    private java.util.List<io.fabric8.kubernetes.api.model.VolumeMount> createVolumeMounts(EdrProperties properties) {
        var volumeMounts = new java.util.ArrayList<io.fabric8.kubernetes.api.model.VolumeMount>();
        
        if (properties.isTlsEnabled()) {
            // Always mount CA certificate for TLS verification
            volumeMounts.add(new io.fabric8.kubernetes.api.model.VolumeMountBuilder()
                    .withName("tls-ca")
                    .withMountPath("/etc/tls")
                    .withReadOnly(true)
                    .build());
            
            // Mount client certificates only if mutual TLS is configured
            if (properties.hasMutualTls()) {
                volumeMounts.add(new io.fabric8.kubernetes.api.model.VolumeMountBuilder()
                        .withName("tls-client")
                        .withMountPath("/etc/tls/client")
                        .withReadOnly(true)
                        .build());
            }
        }
        
        // Mount TLS listener certificates if enabled
        if (tlsListenerEnabled && tlsListenerCertSecret != null && tlsListenerKeySecret != null) {
            volumeMounts.add(new io.fabric8.kubernetes.api.model.VolumeMountBuilder()
                    .withName("tls-listener")
                    .withMountPath("/etc/tls/listener")
                    .withReadOnly(true)
                    .build());
        }
        
        return volumeMounts;
    }
    
    private java.util.List<io.fabric8.kubernetes.api.model.Volume> createVolumes(EdrProperties properties) {
        var volumes = new java.util.ArrayList<io.fabric8.kubernetes.api.model.Volume>();
        
        if (properties.isTlsEnabled()) {
            // Always add CA certificate volume
            volumes.add(new io.fabric8.kubernetes.api.model.VolumeBuilder()
                    .withName("tls-ca")
                    .withNewConfigMap()
                        .withName(properties.getTlsCaSecret())
                    .endConfigMap()
                    .build());
            
            // Add client certificate volume only if mutual TLS is configured
            if (properties.hasMutualTls()) {
                volumes.add(new io.fabric8.kubernetes.api.model.VolumeBuilder()
                        .withName("tls-client")
                        .withNewSecret()
                            .withSecretName("kafka-tls-client-certificates")
                        .endSecret()
                        .build());
            }
        }
        
        // Add TLS listener certificates if enabled
        if (tlsListenerEnabled && tlsListenerCertSecret != null && tlsListenerKeySecret != null) {
            var secretItems = new java.util.ArrayList<io.fabric8.kubernetes.api.model.KeyToPath>();
            secretItems.add(new io.fabric8.kubernetes.api.model.KeyToPathBuilder()
                    .withKey("tls.crt")
                    .withPath("tls.crt")
                    .build());
            secretItems.add(new io.fabric8.kubernetes.api.model.KeyToPathBuilder()
                    .withKey("tls.key")
                    .withPath("tls.key")
                    .build());
            
            // Add CA certificate if mutual TLS is enabled
            if (tlsListenerCaSecret != null && !tlsListenerCaSecret.isEmpty()) {
                secretItems.add(new io.fabric8.kubernetes.api.model.KeyToPathBuilder()
                        .withKey("ca.crt")
                        .withPath("ca.crt")
                        .build());
            }
            
            volumes.add(new io.fabric8.kubernetes.api.model.VolumeBuilder()
                    .withName("tls-listener")
                    .withNewSecret()
                        .withSecretName(tlsListenerCertSecret)
                        .withItems(secretItems)
                    .endSecret()
                    .build());
        }
        
        return volumes;
    }
    
    private Service createService(String edrKey, String proxyName, String serviceName) {
        Map<String, String> labels = new HashMap<>();
        
        // Required standard Kubernetes labels
        labels.put("app.kubernetes.io/name", "kafka-proxy");
        labels.put("app.kubernetes.io/instance", serviceName);
        labels.put("app.kubernetes.io/component", "service");
        labels.put("app.kubernetes.io/managed-by", "kafka-proxy-k8s-manager");
        
        // Custom labels for identification
        labels.put("app", proxyName);
        labels.put("edr-id", edrKey);
        labels.put("component", "kafka-proxy");
        labels.put("managed-by", "edr-kubectl-deployer");
        labels.put("owner-participant", generateSafeParticipantId(participantId));
        
        // Use fixed port
        int port = generateConsistentPort(edrKey);
        
        ServiceBuilder builder = new ServiceBuilder()
                .withNewMetadata()
                    .withName(serviceName)  // Use standardized service name
                    .withNamespace(proxyNamespace)
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withSelector(Map.of("app", proxyName))  // Selector points to deployment
                    .withPorts(createServicePorts(port))
                    .withType("ClusterIP")
                .endSpec();
        
        // Set fixed ClusterIP if configured
        if (clusterIp != null && !clusterIp.isEmpty()) {
            LOGGER.info(format("Configuring service with fixed ClusterIP: %s", clusterIp));
            builder.editSpec()
                    .withClusterIP(clusterIp)
                    .endSpec();
        }
        
        return builder.build();
    }
    
    private java.util.List<String> buildContainerArgs(String edrKey, String cleanBootstrapServers, EdrProperties properties) {
        var args = new java.util.ArrayList<String>();
        args.add("server");
        
        // Use standardized service name that external clients will connect to
        String serviceName = generateServiceName(edrKey);
        
        // Use fixed port
        int port = generateConsistentPort(edrKey);
        
        // Use the three-parameter format: source,listen,advertised
        // The advertised address should be the standardized service name
        args.add(format("--bootstrap-server-mapping=%s,0.0.0.0:%d,%s:%d", 
                cleanBootstrapServers, port, serviceName, port));
        args.add(format("--dynamic-advertised-listener=%s", serviceName));
        args.add(format("--dynamic-sequential-min-port=%d", port + 1));
        
        // Add SASL configuration based on mechanism
        if ("PLAIN".equals(properties.getSaslMechanism())) {
            // SASL PLAIN authentication
            args.add("--sasl-enable");
            
            // Use environment variables for sensitive credentials
            args.add("--sasl-username=$(SASL_USERNAME)");
            args.add("--sasl-password=$(SASL_PASSWORD)");
            
            LOGGER.info("Adding SASL PLAIN authentication with credentials from secret");
        } else if ("OAUTHBEARER".equals(properties.getSaslMechanism()) && properties.hasOauth2Credentials()) {
            // OAuth2/OIDC authentication using SASL plugin with entra-token-provider
            // This uses standard Kafka SASL OAUTHBEARER protocol (not gateway auth)
            args.add("--sasl-enable");
            args.add("--sasl-plugin-enable");
            args.add("--sasl-plugin-mechanism=OAUTHBEARER");
            args.add("--sasl-plugin-command=/usr/local/bin/entra-token-provider");
            args.add("--sasl-plugin-timeout=30s");
            args.add(format("--sasl-plugin-param=--tenant-id=%s", properties.getOauth2TenantId()));
            args.add(format("--sasl-plugin-param=--client-id=%s", properties.getOauth2ClientId()));
            // Pass environment variable NAME (not value) to avoid credential exposure in logs
            // Plugin detects OAUTH2_CLIENT_SECRET pattern and reads from environment at runtime
            args.add("--sasl-plugin-param=--client-secret=OAUTH2_CLIENT_SECRET");
            
            // Add scope if specified
            if (properties.getOauth2Scope() != null && !properties.getOauth2Scope().isEmpty()) {
                args.add(format("--sasl-plugin-param=--scope=%s", properties.getOauth2Scope()));
            }
            
            
            LOGGER.info(format("Adding OAuth2 SASL plugin authentication with mechanism=OAUTHBEARER, tenant-id: %s, client-id: %s", 
                    properties.getOauth2TenantId(), properties.getOauth2ClientId()));
        }
        
        // Add TLS configuration if needed
        if (properties.isTlsEnabled()) {
            args.add("--tls-enable");
            args.add("--tls-ca-chain-cert-file=/etc/tls/ca.crt");
            
            // Check if client certificate and key are provided for mutual TLS
            if (properties.hasMutualTls()) {
                // Mutual TLS - client certificate authentication
                args.add("--tls-client-cert-file=/etc/tls/client/" + properties.getTlsClientCert());
                args.add("--tls-client-key-file=/etc/tls/client/" + properties.getTlsClientKey());
                LOGGER.info("Adding mutual TLS configuration with client certificate: " + properties.getTlsClientCert());
            } else {
                // One-way TLS - server authentication only
                LOGGER.info("Adding one-way TLS configuration with CA certificate only");
            }
        }
        
        // Add authentication if enabled (supports PLAIN and OAUTHBEARER mechanisms)
        if (authEnabled && authTenantId != null && authClientId != null) {
            args.add("--auth-local-enable");
            
            // Select mechanism and plugin based on configuration
            if ("OAUTHBEARER".equalsIgnoreCase(authMechanism)) {
                // OAUTHBEARER - proper OAuth2 flow using entra-token-info
                args.add("--auth-local-mechanism=OAUTHBEARER");
                args.add("--auth-local-command=/usr/local/bin/entra-token-info");
                args.add(format("--auth-local-param=--tenant-id=%s", authTenantId));
                args.add(format("--auth-local-param=--client-id=%s", authClientId));
                LOGGER.info(format("Adding OAUTHBEARER authentication with tenant-id: %s, client-id: %s", 
                        authTenantId, authClientId));
            } else {
                // PLAIN - JWT-over-PLAIN hybrid using entra-token-verifier (default)
                args.add("--auth-local-mechanism=PLAIN");
                args.add("--auth-local-command=/usr/local/bin/entra-token-verifier");
                args.add(format("--auth-local-param=--tenant-id=%s", authTenantId));
                args.add(format("--auth-local-param=--client-id=%s", authClientId));
                
                if (authStaticUsers != null && !authStaticUsers.isEmpty()) {
                    // Pass environment variable NAME (not value) to avoid credential exposure in logs
                    // Plugin detects ENV_VAR_NAME pattern and reads credentials from environment at runtime
                    args.add("--auth-local-param=--static-user=AUTH_STATIC_USERS");
                    LOGGER.info(format("Adding PLAIN authentication (JWT-over-PLAIN) with tenant-id: %s, client-id: %s, static-users: [from secret]", 
                            authTenantId, authClientId));
                } else {
                    LOGGER.info(format("Adding PLAIN authentication (JWT-over-PLAIN) with tenant-id: %s, client-id: %s", 
                            authTenantId, authClientId));
                }
            }
        }
        
        // Add TLS listener configuration if enabled
        if (tlsListenerEnabled && tlsListenerCertSecret != null && tlsListenerKeySecret != null) {
            args.add("--proxy-listener-tls-enable");
            args.add("--proxy-listener-cert-file=/etc/tls/listener/tls.crt");
            args.add("--proxy-listener-key-file=/etc/tls/listener/tls.key");
            
            // Add CA certificate if provided for mutual TLS
            if (tlsListenerCaSecret != null && !tlsListenerCaSecret.isEmpty()) {
                args.add("--proxy-listener-ca-chain-cert-file=/etc/tls/listener/ca.crt");
                LOGGER.info("Adding TLS listener configuration with mutual TLS (client certificate verification)");
            } else {
                LOGGER.info("Adding TLS listener configuration (one-way TLS)");
            }
        }
        
        return args;
    }
    
    /**
     * Generates a deployment name that includes the EDR key for uniqueness
     */
    private String generateProxyName(String edrKey) {
        String safeParticipantId = generateSafeParticipantId(participantId);
        
        // Extract UUID part from EDR key to keep names short
        String shortEdrKey = edrKey;
        if (edrKey.startsWith("edr--")) {
            shortEdrKey = edrKey.substring(5); // Remove "edr--" prefix
        }
        
        // Create deployment name: kp-<participant>-<edr-short>
        String proxyName = format("kp-%s-%s", safeParticipantId, shortEdrKey);
        
        // Ensure total length is under 63 characters (Kubernetes limit)
        if (proxyName.length() > 63) {
            int maxEdrLength = 63 - safeParticipantId.length() - 4; // 4 for "kp-" and "-"
            if (maxEdrLength > 8) {
                shortEdrKey = shortEdrKey.substring(0, Math.min(shortEdrKey.length(), maxEdrLength));
                proxyName = format("kp-%s-%s", safeParticipantId, shortEdrKey);
            } else {
                // Use hash if participant ID is too long
                String hash = Integer.toHexString(participantId.hashCode()).replaceAll("-", "");
                proxyName = format("kp-%s-%s", hash.substring(0, 8), shortEdrKey.substring(0, 8));
            }
        }
        
        return proxyName.toLowerCase().replace("_", "-");
    }
    
    /**
     * Generates a standardized service name (always the same for this participant)
     */
    private String generateServiceName(String edrKey) {
        String safeParticipantId = generateSafeParticipantId(participantId);
        return format("kp-%s-service", safeParticipantId).toLowerCase().replace("_", "-");
    }

    /**
     * Generates a safe Kubernetes-compatible identifier from participant ID
     */
    private String generateSafeParticipantId(String participantId) {
        if (participantId == null || participantId.isEmpty()) {
            return "default";
        }
        
        // Extract meaningful part from DID or URL
        String safeName = participantId;
        
        // Handle DID format (did:web:consumer-identityhub%3A8383:api:did)
        if (participantId.startsWith("did:web:")) {
            String[] parts = participantId.split(":");
            if (parts.length >= 3) {
                safeName = parts[2]; // Get the host part
                // Remove URL encoding and extract meaningful name
                safeName = safeName.replace("%3A", "").replace("%2F", "");
                // Extract main part before port or path
                if (safeName.contains("-")) {
                    String[] hostParts = safeName.split("-");
                    safeName = hostParts[0]; // Take first part (e.g., "consumer" from "consumer-identityhub")
                }
            }
        } else if (participantId.startsWith("urn:connector:")) { // Handle URN format (urn:connector:provider)
            safeName = participantId.substring("urn:connector:".length());
        } else if (participantId.contains("://")) { // Handle plain URLs
            try {
                java.net.URI uri = new java.net.URI(participantId);
                safeName = uri.getHost();
                if (safeName != null && safeName.contains(".")) {
                    safeName = safeName.split("\\.")[0]; // Take first part of domain
                }
            } catch (Exception e) {
                // Fallback to cleaning the original string
            }
        }
        
        // Clean for Kubernetes naming requirements
        safeName = safeName.toLowerCase()
                .replaceAll("[^a-z0-9-]", "")  // Remove invalid chars completely
                .replaceAll("-+", "-")         // Collapse multiple dashes
                .replaceAll("^-|-$", "");      // Remove leading/trailing dashes
        
        // Limit length for Kubernetes (keep it short for proxy names)
        if (safeName.length() > 12) {
            safeName = safeName.substring(0, 12);
        }
        
        // Ensure it doesn't end with dash
        safeName = safeName.replaceAll("-$", "");
        
        return safeName.isEmpty() ? "default" : safeName;
    }

    /**
     * Generates a safe Kubernetes label value from any string
     * Kubernetes labels must be alphanumeric with dashes, dots, underscores
     */
    private String generateSafeLabelValue(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        
        // Convert special characters to safe alternatives
        String safeValue = value
                .replace(":", "-")
                .replace("/", "-")
                .replace("%", "")
                .replaceAll("[^a-zA-Z0-9._-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        
        // Limit length (Kubernetes label values can be up to 63 chars)
        if (safeValue.length() > 63) {
            safeValue = safeValue.substring(0, 63);
        }
        
        // Ensure it doesn't end with dash
        safeValue = safeValue.replaceAll("-$", "");
        
        return safeValue.isEmpty() ? "unknown" : safeValue;
    }
    
    private int generateConsistentPort(String edrKey) {
        // Use fixed port for standardized service
        // Since we have one proxy at a time, we can use the same port
        return baseProxyPort;
    }
    
    /**
     * Generates a secret name for storing sensitive proxy credentials
     */
    private String generateSecretName(String edrKey) {
        String safeParticipantId = generateSafeParticipantId(participantId);
        String shortEdrKey = edrKey;
        if (edrKey.startsWith("edr--")) {
            shortEdrKey = edrKey.substring(5);
        }
        String secretName = format("kp-%s-%s-secret", safeParticipantId, shortEdrKey);
        if (secretName.length() > 63) {
            int maxEdrLength = 63 - safeParticipantId.length() - 11; // 11 for "kp-" + "-secret"
            if (maxEdrLength > 8) {
                shortEdrKey = shortEdrKey.substring(0, Math.min(shortEdrKey.length(), maxEdrLength));
                secretName = format("kp-%s-%s-secret", safeParticipantId, shortEdrKey);
            }
        }
        return secretName.toLowerCase().replace("_", "-");
    }
    
    /**
     * Creates a Kubernetes Secret containing sensitive credentials for the proxy
     */
    private void createProxySecret(String edrKey, EdrProperties properties) {
        String secretName = generateSecretName(edrKey);
        Map<String, String> secretData = new HashMap<>();
        
        // Add SASL credentials if using PLAIN mechanism
        if ("PLAIN".equals(properties.getSaslMechanism())) {
            String username = properties.getUsername() != null ? properties.getUsername() : "admin";
            String password = properties.getPassword() != null ? properties.getPassword() : "admin-secret";
            secretData.put("sasl-username", username);
            secretData.put("sasl-password", password);
            LOGGER.info(format("Adding SASL PLAIN credentials to secret %s", secretName));
        }
        
        // Add OAuth2 client secret if using OAUTHBEARER mechanism
        if ("OAUTHBEARER".equals(properties.getSaslMechanism()) && properties.hasOauth2Credentials()) {
            secretData.put("oauth2-client-secret", properties.getOauth2ClientSecret());
            LOGGER.info(format("Adding OAuth2 client secret to secret %s", secretName));
        }
        
        // Add auth static users if configured
        if (authEnabled && authStaticUsers != null && !authStaticUsers.isEmpty()) {
            secretData.put("auth-static-users", authStaticUsers);
            LOGGER.info(format("Adding auth static users to secret %s", secretName));
        }
        
        // Only create secret if there's data to store
        if (!secretData.isEmpty()) {
            Map<String, String> labels = new HashMap<>();
            labels.put("app.kubernetes.io/name", "kafka-proxy");
            labels.put("app.kubernetes.io/component", "credentials");
            labels.put("app.kubernetes.io/managed-by", "kafka-proxy-k8s-manager");
            labels.put("edr-id", edrKey);
            labels.put("owner-participant", generateSafeParticipantId(participantId));
            
            var secret = new SecretBuilder()
                    .withNewMetadata()
                        .withName(secretName)
                        .withNamespace(proxyNamespace)
                        .withLabels(labels)
                    .endMetadata()
                    .withType("Opaque")
                    .withStringData(secretData)
                    .build();
            
            try {
                // Create or update the secret
                var existing = kubernetesClient.secrets()
                        .inNamespace(proxyNamespace)
                        .withName(secretName)
                        .get();
                
                if (existing != null) {
                    kubernetesClient.resource(secret).inNamespace(proxyNamespace).update();
                    LOGGER.info(format("Updated secret %s in namespace %s", secretName, proxyNamespace));
                } else {
                    kubernetesClient.resource(secret).inNamespace(proxyNamespace).create();
                    LOGGER.info(format("Created secret %s in namespace %s", secretName, proxyNamespace));
                }
            } catch (Exception e) {
                LOGGER.severe(format("Failed to create/update secret %s: %s", secretName, e.getMessage()));
                throw e;
            }
        }
    }
}