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

package org.eclipse.dse.core.kafkaproxy;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import org.eclipse.dse.core.kafkaproxy.config.KafkaProxyConfig;
import org.eclipse.dse.core.kafkaproxy.service.AutomaticDiscoveryQueueService;
import org.eclipse.dse.core.kafkaproxy.service.KubernetesCheckerService;
import org.eclipse.dse.core.kafkaproxy.service.KubernetesDeployerService;
import org.eclipse.dse.core.kafkaproxy.service.VaultService;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.eclipse.dse.core.kafkaproxy.config.KafkaProxyConfig.LARGEST_AVAILABLE_PORT;

/**
 * Extension that provides Kafka Proxy Kubernetes management capabilities
 */
@Extension(value = KafkaProxyKubernetesExtension.NAME)
public class KafkaProxyKubernetesExtension implements ServiceExtension {
    
    public static final String NAME = "Kafka Proxy Kubernetes Manager";
    
    private ScheduledExecutorService scheduler;
    private KafkaProxyOrchestrator orchestrator;
    
    @Override
    public String name() {
        return NAME;
    }
    
    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();
        
        // Get configuration
        String vaultAddr = context.getConfig().getString(KafkaProxyConfig.VAULT_ADDR, KafkaProxyConfig.DEFAULT_VAULT_ADDR);
        String vaultToken = context.getConfig().getString(KafkaProxyConfig.VAULT_TOKEN, KafkaProxyConfig.DEFAULT_VAULT_TOKEN);
        String vaultFolder = context.getConfig().getString(KafkaProxyConfig.VAULT_FOLDER, KafkaProxyConfig.DEFAULT_VAULT_FOLDER);
        int checkInterval = context.getConfig().getInteger(KafkaProxyConfig.CHECK_INTERVAL, Integer.parseInt(KafkaProxyConfig.DEFAULT_CHECK_INTERVAL));
        String sharedDir = context.getConfig().getString(KafkaProxyConfig.SHARED_DIR, KafkaProxyConfig.DEFAULT_SHARED_DIR);
        String proxyNamespace = context.getConfig().getString(KafkaProxyConfig.PROXY_NAMESPACE, KafkaProxyConfig.DEFAULT_PROXY_NAMESPACE);
        String proxyImage = context.getConfig().getString(KafkaProxyConfig.PROXY_IMAGE, KafkaProxyConfig.DEFAULT_PROXY_IMAGE);
        
        // Get participant ID (fallback to EDC_PARTICIPANT_ID if not set)
        String participantId = context.getConfig().getString(KafkaProxyConfig.PARTICIPANT_ID, 
                context.getConfig().getString("edc.participant.id", "default-participant"));
        
        boolean authEnabled = context.getConfig().getBoolean(KafkaProxyConfig.AUTH_ENABLED, false);
        String authMechanism = context.getConfig().getString(KafkaProxyConfig.AUTH_MECHANISM, KafkaProxyConfig.DEFAULT_AUTH_MECHANISM);
        String authTenantId = context.getConfig().getString(KafkaProxyConfig.AUTH_TENANT_ID, "");
        String authClientId = context.getConfig().getString(KafkaProxyConfig.AUTH_CLIENT_ID, "");
        String authStaticUsers = context.getConfig().getString(KafkaProxyConfig.AUTH_STATIC_USERS, "");
        String authImage = context.getConfig().getString(KafkaProxyConfig.AUTH_IMAGE, KafkaProxyConfig.DEFAULT_AUTH_IMAGE);
        
        // TLS listener configuration
        boolean tlsListenerEnabled = context.getConfig().getBoolean(KafkaProxyConfig.TLS_LISTENER_ENABLED, false);
        String tlsListenerCertSecret = context.getConfig().getString(KafkaProxyConfig.TLS_LISTENER_CERT_SECRET, "");
        String tlsListenerKeySecret = context.getConfig().getString(KafkaProxyConfig.TLS_LISTENER_KEY_SECRET, "");
        String tlsListenerCaSecret = context.getConfig().getString(KafkaProxyConfig.TLS_LISTENER_CA_SECRET, "");
        
        // Service configuration
        String serviceClusterIp = context.getConfig().getString(KafkaProxyConfig.SERVICE_CLUSTER_IP, null);
        
        // Base proxy port configuration
        int baseProxyPort = context.getConfig().getInteger(KafkaProxyConfig.BASE_PROXY_PORT, 
                Integer.parseInt(KafkaProxyConfig.DEFAULT_BASE_PROXY_PORT));
        
        // Maximum broker ports configuration
        int maxBrokerPorts = context.getConfig().getInteger(KafkaProxyConfig.MAX_BROKER_PORTS,
                KafkaProxyConfig.MAX_ALLOWED_BROKER_PORTS);
        if (maxBrokerPorts <= 0) {
            monitor.warning("Invalid configuration for max broker ports: " + maxBrokerPorts + ". Using default value: " + KafkaProxyConfig.MAX_ALLOWED_BROKER_PORTS);
            maxBrokerPorts = KafkaProxyConfig.MAX_ALLOWED_BROKER_PORTS;
        } else {
            // Ensure maxBrokerPorts does not exceed the default limit to prevent excessive port usage
            maxBrokerPorts = Math.min(maxBrokerPorts, KafkaProxyConfig.MAX_ALLOWED_BROKER_PORTS);
        }
        // ---- Additional validation to ensure port range stays inside 0–65535 ----
        int highestPort = baseProxyPort + maxBrokerPorts;
        if (highestPort > LARGEST_AVAILABLE_PORT) {
            throw new IllegalArgumentException(
                    "Configured port range exceeds the maximum allowed TCP port. " +
                            "Base port: " + baseProxyPort +
                            ", maxBrokerPorts: " + maxBrokerPorts +
                            ", highestPort: " + highestPort +
                            ". Maximum allowed port is 65535."
            );
        }

        // Pod labels configuration (comma-separated key=value pairs, e.g., "env=prod,team=platform")
        String podLabelsConfig = context.getConfig().getString(KafkaProxyConfig.POD_LABELS, "");
        java.util.Map<String, String> additionalPodLabels = parsePodLabels(podLabelsConfig);
        
        int discoveryInterval = context.getConfig().getInteger(KafkaProxyConfig.DISCOVERY_INTERVAL, Integer.parseInt(KafkaProxyConfig.DEFAULT_DISCOVERY_INTERVAL));
        
        monitor.info("Initializing Kafka Proxy Kubernetes Manager");
        monitor.info("Configuration:");
        monitor.info("  Participant ID: " + participantId);
        monitor.info("  Vault Address: " + vaultAddr);
        monitor.info("  Vault Folder: " + (vaultFolder.isEmpty() ? "(root)" : vaultFolder));
        monitor.info("  Check Interval: " + checkInterval + "s");
        monitor.info("  Discovery Interval: " + discoveryInterval + "s");
        monitor.info("  Shared Directory: " + sharedDir);
        monitor.info("  Proxy Namespace: " + proxyNamespace);
        monitor.info("  Proxy Image: " + proxyImage);
        monitor.info("  Authentication Enabled: " + authEnabled);
        if (authEnabled) {
            monitor.info("  Authentication Mechanism: " + authMechanism);
            monitor.info("  Auth Tenant ID: " + authTenantId);
            monitor.info("  Auth Client ID: " + authClientId);
            monitor.info("  Auth Static Users: " + authStaticUsers);
            monitor.info("  Auth Plugin Image: " + authImage);
        }
        monitor.info("  TLS Listener Enabled: " + tlsListenerEnabled);
        if (tlsListenerEnabled) {
            monitor.info("  TLS Listener Cert Secret: " + tlsListenerCertSecret);
            monitor.info("  TLS Listener Key Secret: " + tlsListenerKeySecret);
            monitor.info("  TLS Listener CA Secret: " + tlsListenerCaSecret);
        }
        if (serviceClusterIp != null && !serviceClusterIp.isEmpty()) {
            monitor.info("  Service ClusterIP: " + serviceClusterIp);
        }
        monitor.info("  Base Proxy Port: " + baseProxyPort);
        monitor.info("  Max Broker Ports: " + maxBrokerPorts);
        if (!additionalPodLabels.isEmpty()) {
            monitor.info("  Additional Pod Labels: " + additionalPodLabels);
        }
        
        // Initialize services
        var kubernetesClient = new DefaultKubernetesClient();
        var vaultService = new VaultService(vaultAddr, vaultToken, vaultFolder);
        var deployerService = new KubernetesDeployerService(kubernetesClient, proxyNamespace, proxyImage, 
                vaultService, participantId, serviceClusterIp, baseProxyPort, authEnabled, authMechanism, authTenantId, authClientId, authStaticUsers, authImage,
                tlsListenerEnabled, tlsListenerCertSecret, tlsListenerKeySecret, tlsListenerCaSecret, additionalPodLabels, maxBrokerPorts);
        var checkerService = new KubernetesCheckerService(kubernetesClient, proxyNamespace, participantId);
        
        var automaticQueueService = new AutomaticDiscoveryQueueService(sharedDir, vaultService, checkerService);
        
        // Initialize EDR tracking for automatic proxy deletion
        automaticQueueService.initializeEdrTracking();
        
        orchestrator = new KafkaProxyOrchestrator(
                vaultService,
                deployerService,
                checkerService,
                automaticQueueService,
                monitor,
                true // always enabled
        );
        
        // Start scheduled processing
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleWithFixedDelay(
                orchestrator::processQueues,
                0,
                checkInterval, 
                TimeUnit.SECONDS
        );
        
        monitor.info("Kafka Proxy Manager started with " + checkInterval + "s check interval");
    }
    
    @Override
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    @Provider
    public KafkaProxyOrchestrator getOrchestrator() {
        return orchestrator;
    }
    
    /**
     * Parses pod labels from a comma-separated string of key=value pairs
     * Example: "env=prod,team=platform,version=1.0"
     */
    private java.util.Map<String, String> parsePodLabels(String podLabelsConfig) {
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        
        if (podLabelsConfig == null || podLabelsConfig.trim().isEmpty()) {
            return labels;
        }
        
        String[] pairs = podLabelsConfig.split(",");
        for (String pair : pairs) {
            pair = pair.trim();
            if (pair.isEmpty()) {
                continue;
            }
            
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    labels.put(key, value);
                }
            }
        }
        
        return labels;
    }
}