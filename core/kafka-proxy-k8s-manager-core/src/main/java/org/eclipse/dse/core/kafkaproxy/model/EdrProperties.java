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

package org.eclipse.dse.core.kafkaproxy.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Represents EDR (Endpoint Data Reference) properties retrieved from Vault
 */
public class EdrProperties {
    
    private final String bootstrapServers;
    private final String username;
    private final String password;
    private final String securityProtocol;
    private final String saslMechanism;
    private final String tlsClientCert;
    private final String tlsClientKey;
    private final String tlsCaSecret;
    
    // OAuth2 client credentials for OAUTHBEARER authentication
    private final String oauth2ClientId;
    private final String oauth2ClientSecret;
    private final String oauth2TenantId;
    private final String oauth2Scope;
    
    @JsonCreator
    public EdrProperties(
            @JsonProperty("bootstrap_servers") String bootstrapServers,
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("security_protocol") String securityProtocol,
            @JsonProperty("sasl_mechanism") String saslMechanism,
            @JsonProperty("tls_client_cert") String tlsClientCert,
            @JsonProperty("tls_client_key") String tlsClientKey,
            @JsonProperty("tls_ca_secret") String tlsCaSecret,
            @JsonProperty("oauth2_client_id") String oauth2ClientId,
            @JsonProperty("oauth2_client_secret") String oauth2ClientSecret,
            @JsonProperty("oauth2_tenant_id") String oauth2TenantId,
            @JsonProperty("oauth2_scope") String oauth2Scope) {
        this.bootstrapServers = bootstrapServers;
        this.username = username;
        this.password = password;
        this.securityProtocol = securityProtocol;
        this.saslMechanism = saslMechanism;
        this.tlsClientCert = tlsClientCert;
        this.tlsClientKey = tlsClientKey;
        this.tlsCaSecret = tlsCaSecret;
        this.oauth2ClientId = oauth2ClientId;
        this.oauth2ClientSecret = oauth2ClientSecret;
        this.oauth2TenantId = oauth2TenantId;
        this.oauth2Scope = oauth2Scope;
    }
    
    public String getBootstrapServers() {
        return bootstrapServers;
    }
    
    public String getUsername() {
        return username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public String getSecurityProtocol() {
        return securityProtocol;
    }
    
    public String getSaslMechanism() {
        return saslMechanism;
    }
    

    
    public String getTlsClientCert() {
        return tlsClientCert;
    }
    
    public String getTlsClientKey() {
        return tlsClientKey;
    }
    
    public String getTlsCaSecret() {
        return tlsCaSecret != null ? tlsCaSecret : "proxy-provider-tls-ca"; // default ConfigMap name
    }
    
    public String getOauth2ClientId() {
        return oauth2ClientId;
    }
    
    public String getOauth2ClientSecret() {
        return oauth2ClientSecret;
    }
    
    public String getOauth2TenantId() {
        return oauth2TenantId;
    }
    
    public String getOauth2Scope() {
        return oauth2Scope != null ? oauth2Scope : "api://kafka-proxy/.default";
    }
    
    public boolean isTlsEnabled() {
        return "SSL".equals(securityProtocol) || "SASL_SSL".equals(securityProtocol);
    }
    
    /**
     * Returns true if both client certificate and key are provided for mutual TLS
     */
    public boolean hasMutualTls() {
        return tlsClientCert != null && !tlsClientCert.isEmpty() &&
                tlsClientKey != null && !tlsClientKey.isEmpty();
    }
    
    /**
     * Returns true if OAuth2 client credentials are configured for OAUTHBEARER authentication.
     */
    public boolean hasOauth2Credentials() {
        return oauth2ClientId != null && !oauth2ClientId.isEmpty() &&
                oauth2ClientSecret != null && !oauth2ClientSecret.isEmpty() &&
                oauth2TenantId != null && !oauth2TenantId.isEmpty();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EdrProperties that = (EdrProperties) o;
        return Objects.equals(bootstrapServers, that.bootstrapServers) &&
               Objects.equals(username, that.username) &&
               Objects.equals(password, that.password) &&
               Objects.equals(securityProtocol, that.securityProtocol) &&
               Objects.equals(saslMechanism, that.saslMechanism) &&
               Objects.equals(tlsClientCert, that.tlsClientCert) &&
               Objects.equals(tlsClientKey, that.tlsClientKey) &&
               Objects.equals(tlsCaSecret, that.tlsCaSecret) &&
               Objects.equals(oauth2ClientId, that.oauth2ClientId) &&
               Objects.equals(oauth2ClientSecret, that.oauth2ClientSecret) &&
               Objects.equals(oauth2TenantId, that.oauth2TenantId) &&
               Objects.equals(oauth2Scope, that.oauth2Scope);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(bootstrapServers, username, password, securityProtocol, 
                           saslMechanism,
                           tlsClientCert, tlsClientKey, tlsCaSecret,
                           oauth2ClientId, oauth2ClientSecret, oauth2TenantId, oauth2Scope);
    }
    
    @Override
    public String toString() {
        return "EdrProperties{" +
                "bootstrapServers='" + bootstrapServers + '\'' +
                ", username='" + username + '\'' +
                ", securityProtocol='" + securityProtocol + '\'' +
                ", saslMechanism='" + saslMechanism + '\'' +
                ", tlsEnabled=" + isTlsEnabled() +
                ", hasMutualTls=" + hasMutualTls() +
                ", tlsCaSecret='" + tlsCaSecret + '\'' +
                ", hasOauth2Credentials=" + hasOauth2Credentials() +
                ", oauth2TenantId='" + oauth2TenantId + '\'' +
                '}';
    }
}