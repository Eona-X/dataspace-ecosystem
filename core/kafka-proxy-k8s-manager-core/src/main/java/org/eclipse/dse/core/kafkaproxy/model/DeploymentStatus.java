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

import java.time.Instant;
import java.util.Objects;

/**
 * Represents the deployment status of a Kafka proxy
 */
public class DeploymentStatus {
    
    public enum Status {
        PENDING,
        DEPLOYING,
        DEPLOYED,
        FAILED,
        DELETING,
        DELETED
    }
    
    private final String edrKey;
    private final Status status;
    private final String message;
    private final Instant timestamp;
    
    public DeploymentStatus(String edrKey, Status status, String message) {
        this.edrKey = edrKey;
        this.status = status;
        this.message = message;
        this.timestamp = Instant.now();
    }
    
    public String getEdrKey() {
        return edrKey;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public String getMessage() {
        return message;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeploymentStatus that = (DeploymentStatus) o;
        return Objects.equals(edrKey, that.edrKey) &&
                status == that.status &&
                Objects.equals(message, that.message);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(edrKey, status, message);
    }
    
    @Override
    public String toString() {
        return "DeploymentStatus{" +
                "edrKey='" + edrKey + '\'' +
                ", status=" + status +
                ", message='" + message + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}