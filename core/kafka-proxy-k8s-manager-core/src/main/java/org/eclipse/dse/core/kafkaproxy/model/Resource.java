package org.eclipse.dse.core.kafkaproxy.model;


public record Resource(String edrKey, String proxyName, EdrProperties properties, String deploymentUid) {
}
