package org.eclipse.dse.spi.telemetry;

import org.eclipse.edc.runtime.metamodel.annotation.ExtensionPoint;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.ServiceResult;

@ExtensionPoint
public interface TelemetryService {

    ServiceResult<TokenRepresentation> createAccessToken(TokenRepresentation tokenRepresentation);

}
