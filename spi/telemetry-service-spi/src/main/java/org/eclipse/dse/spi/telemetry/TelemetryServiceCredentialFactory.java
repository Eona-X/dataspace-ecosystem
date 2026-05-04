package org.eclipse.dse.spi.telemetry;

import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.runtime.metamodel.annotation.ExtensionPoint;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.Result;

@ExtensionPoint
public interface TelemetryServiceCredentialFactory {

    Result<TokenRepresentation> create(ParticipantAgent participantAgent);
}
