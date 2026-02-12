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

package org.eclipse.dse.core.kafkaproxy.config;

/**
 * Configuration settings for the Kafka Proxy Kubernetes Manager
 */
public class KafkaProxyConfig {

    public static final String VAULT_ADDR = "kafka.proxy.vault.addr";
    public static final String VAULT_TOKEN = "kafka.proxy.vault.token";
    public static final String VAULT_FOLDER = "kafka.proxy.vault.folder";
    public static final String CHECK_INTERVAL = "kafka.proxy.check.interval";
    public static final String SHARED_DIR = "kafka.proxy.shared.dir";
    public static final String PROXY_NAMESPACE = "kafka.proxy.namespace";
    public static final String PROXY_IMAGE = "kafka.proxy.image";
    public static final String KUBECONFIG_PATH = "kafka.proxy.kubeconfig.path";
    public static final String PARTICIPANT_ID = "kafka.proxy.participant.id";
    public static final String SERVICE_CLUSTER_IP = "kafka.proxy.service.clusterIP";
    public static final String BASE_PROXY_PORT = "kafka.proxy.base.port";
    public static final String MAX_BROKER_PORTS = "kafka.proxy.max.broker.ports";
    
    // Discovery configuration (always enabled)
    public static final String DISCOVERY_INTERVAL = "kafka.proxy.discovery.interval";
    public static final String EDR_PREFIX = "kafka.proxy.edr.prefix";
    
    // Authentication configuration
    // Supports PLAIN (JWT-over-PLAIN) and OAUTHBEARER (proper OAuth2) mechanisms
    public static final String AUTH_ENABLED = "kafka.proxy.auth.enabled";
    public static final String AUTH_MECHANISM = "kafka.proxy.auth.mechanism";  // PLAIN or OAUTHBEARER
    public static final String AUTH_TENANT_ID = "kafka.proxy.auth.tenant.id";
    public static final String AUTH_CLIENT_ID = "kafka.proxy.auth.client.id";
    public static final String AUTH_STATIC_USERS = "kafka.proxy.auth.static.users";  // Only for PLAIN mechanism
    public static final String AUTH_IMAGE = "kafka.proxy.auth.image";
    
    // TLS listener configuration
    public static final String TLS_LISTENER_ENABLED = "kafka.proxy.tls.listener.enabled";
    public static final String TLS_LISTENER_CERT_SECRET = "kafka.proxy.tls.listener.cert.secret";
    public static final String TLS_LISTENER_KEY_SECRET = "kafka.proxy.tls.listener.key.secret";
    public static final String TLS_LISTENER_CA_SECRET = "kafka.proxy.tls.listener.ca.secret";
    
    // Pod labels configuration (comma-separated key=value pairs)
    public static final String POD_LABELS = "kafka.proxy.pod.labels";
    
    // Default values
    public static final String DEFAULT_VAULT_ADDR = "http://vault:8200";
    public static final String DEFAULT_VAULT_TOKEN = "root";
    public static final String DEFAULT_VAULT_FOLDER = "";  // Empty = root level
    public static final String DEFAULT_CHECK_INTERVAL = "15";
    public static final String DEFAULT_SHARED_DIR = "/shared";
    public static final String DEFAULT_PROXY_NAMESPACE = "default";
    public static final String DEFAULT_PROXY_IMAGE = "grepplabs/kafka-proxy:0.4.2";
    public static final String DEFAULT_AUTH_IMAGE = "kafka-proxy-entra-auth:latest";
    public static final String DEFAULT_AUTH_MECHANISM = "PLAIN";  // PLAIN (JWT-over-PLAIN) or OAUTHBEARER (proper OAuth2)
    public static final String DEFAULT_BASE_PROXY_PORT = "30001";
    public static final int MAX_ALLOWED_BROKER_PORTS = 20;
    public static final int LARGEST_AVAILABLE_PORT = 65535;
    
    // Discovery defaults (always enabled)
    public static final String DEFAULT_DISCOVERY_INTERVAL = "10";  // in seconds
    public static final String DEFAULT_EDR_PREFIX = "edr--";
    
    private KafkaProxyConfig() {
        // Utility class
    }
}