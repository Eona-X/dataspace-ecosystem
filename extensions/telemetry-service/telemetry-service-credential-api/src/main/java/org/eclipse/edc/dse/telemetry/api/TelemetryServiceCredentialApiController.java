package org.eclipse.edc.dse.telemetry.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.eclipse.dse.spi.telemetry.TelemetryService;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.web.spi.exception.InvalidRequestException;

import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.eclipse.edc.web.spi.exception.ServiceResultHandler.exceptionMapper;

@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path("/v1alpha")
public class TelemetryServiceCredentialApiController implements TelemetryServiceCredentialApi {


    private final TelemetryService telemetryService;

    public TelemetryServiceCredentialApiController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }


    @GET
    @Path("/sas-token")
    @Override
    public TokenRepresentation generateToken(@HeaderParam(AUTHORIZATION) String token) {
        if (token == null) {
            throw new InvalidRequestException("Missing '%s' header in request".formatted(AUTHORIZATION));
        }

        return telemetryService.createAccessToken(TokenRepresentation.Builder.newInstance().token(token).build())
                .orElseThrow(exceptionMapper(TokenRepresentation.class));
    }

}
