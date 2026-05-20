package org.eclipse.edc.dse.telemetry.repository;

public record ContractStats(String participantId, String contractId, Integer responseStatus, Long msgSize, Long eventCount) {

    /**
     * Convenience constructor for queries that don't include participantId.
     */
    public ContractStats(String contractId, Integer responseStatus, Long msgSize, Long eventCount) {
        this(null, contractId, responseStatus, msgSize, eventCount);
    }
}
