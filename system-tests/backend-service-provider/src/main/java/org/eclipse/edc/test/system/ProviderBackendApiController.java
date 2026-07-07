package org.eclipse.edc.test.system;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.web.spi.exception.NotAuthorizedException;

import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/provider")
public class ProviderBackendApiController {

    private final ECPublicKey publicKey;
    private final Monitor monitor;


    public ProviderBackendApiController(ECPublicKey publicKey, Monitor monitor) {
        this.publicKey = publicKey;
        this.monitor = monitor;

    }

    @Path("/data")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> getData(@DefaultValue("some information") @QueryParam("message") String message) {
        return Map.of("message", message);
    }

    @Path("/headers")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> getHeaders(@Context HttpHeaders headers) {
        return headers.getRequestHeaders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> String.join(",", entry.getValue())));
    }

    @Path("/postdata")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> postData(Map<String, Object> requestBody) {
        monitor.info("Received request body: " + requestBody);
        requestBody.put("processedBy", "ProviderBackendApiController");
        return requestBody;
    }

    @Path("/oauth2data")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> getOauth2Data(@DefaultValue("some information") @QueryParam("message") String message, @HeaderParam("Authorization") String authorization) {
        if (authorization == null || !isAuthorized(authorization)) {
            throw new NotAuthorizedException("The authorization token is not valid: " + authorization);
        } else {
            return Map.of("message", message);
        }
    }

    @Path("/failure")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> failure() {
        throw new NotAuthorizedException("Not authorized");
    }

    private boolean isAuthorized(String authorization) {
        if (!authorization.startsWith("Bearer ")) {
            return false;
        }

        var token = authorization.replace("Bearer ", "");

        try {
            var jwt = SignedJWT.parse(token);
            return jwt.verify(new ECDSAVerifier(publicKey));
        } catch (ParseException | JOSEException e) {
            return false;
        }
    }


}
