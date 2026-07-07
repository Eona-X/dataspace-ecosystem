package org.eclipse.edc.util;

import org.eclipse.edc.policy.model.Action;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.iam.ClaimToken;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.iam.VerificationContext;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;

import java.util.List;

import static org.eclipse.edc.spi.core.CoreConstants.DSE_VC_TYPE_SCOPE_ALIAS;

public class IdentityServiceValidator {

    private final IdentityService identityService;

    private final Monitor monitor;

    protected static final String READ_ALL_CREDENTIAL_SCOPE = "%s:VerifiableCredential:read".formatted(DSE_VC_TYPE_SCOPE_ALIAS);

    private static final String DISCOVERABILITY_USE_ACTION = "discoverability:use";

    public IdentityServiceValidator(IdentityService identityService, Monitor monitor) {
        this.identityService = identityService;
        this.monitor = monitor;
    }

    public ClaimToken validate(TokenRepresentation uncheckedToken) {
        List<String> scopes = List.of(READ_ALL_CREDENTIAL_SCOPE);
        Result<ClaimToken> validClaims = identityService.verifyJwtToken(uncheckedToken, createContext(scopes));
        if (validClaims.failed()) {
            monitor.warning("Token validation failed " + validClaims.getFailureMessages());
        }
        return validClaims.getContent();
    }

    private VerificationContext createContext(List<String> scopes) {
        return VerificationContext.Builder.newInstance()
                .policy(Policy.Builder.newInstance()
                        .permission(Permission.Builder.newInstance()
                                .action(Action.Builder.newInstance()
                                        .type(DISCOVERABILITY_USE_ACTION).build()).build()).build())
                .scopes(scopes)
                .build();
    }
}
