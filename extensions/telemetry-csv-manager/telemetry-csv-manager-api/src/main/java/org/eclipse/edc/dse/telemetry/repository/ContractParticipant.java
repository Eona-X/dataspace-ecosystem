package org.eclipse.edc.dse.telemetry.repository;

import org.eclipse.edc.dse.telemetry.model.ParticipantId;

public record ContractParticipant(String contractId, ParticipantId participant) {
}
