package org.eclipse.dse.edc.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OauthBearerTokenImpl implements OAuthBearerToken {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private static final String CLAIM_SUBJECT = "sub";
    private static final String CLAIM_EXPIRATION = "exp";
    private static final String CLAIM_ISSUED_AT = "iat";
    private static final String CLAIM_SCOPE = "scope";

    private static final int JWT_PARTS_COUNT = 3;
    private static final int JWT_PAYLOAD_INDEX = 1;
    private static final long SECONDS_TO_MILLIS = 1000L;

    private final String value;
    private final Set<String> scope;
    private final long lifetimeMs;
    private final String principalName;
    private final Long startTimeMs;

    public OauthBearerTokenImpl(String value, Set<String> scope, long lifetimeMs, String principalName, Long startTimeMs) {
        this.value = value;
        this.scope = Collections.unmodifiableSet(scope);
        this.lifetimeMs = lifetimeMs;
        this.principalName = principalName;
        this.startTimeMs = startTimeMs;
    }

    public static OauthBearerTokenImpl from(String token, Clock clock) {
        if (token == null) {
            throw new IllegalArgumentException("token cannot be null");
        }
        String[] parts = splitAndValidate(token);
        Map<String, Object> claims = decodePayload(parts[JWT_PAYLOAD_INDEX]);
        return buildFromClaims(token, claims, clock);
    }

    private static String[] splitAndValidate(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != JWT_PARTS_COUNT) {
            throw new IllegalArgumentException(
                    "Invalid token format — expected " + JWT_PARTS_COUNT +
                            " parts (header.payload.signature), got " + parts.length);
        }
        return parts;
    }

    private static Map<String, Object> decodePayload(String encodedPayload) {
        String json;
        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(encodedPayload);
            json = new String(decodedBytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Failed to decode JWT payload (base64url)", e);
        }

        try {
            return MAPPER.readValue(json, MAP_TYPE_REF);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse JWT payload as JSON", e);
        }
    }

    private static OauthBearerTokenImpl buildFromClaims(String token, Map<String, Object> claims, Clock clock) {
        String principalName = extractPrincipalName(claims);
        long lifetimeMs = extractLifetimeMs(claims);
        Long startTimeMs = extractStartTimeMs(claims, clock);
        Set<String> scope = parseScope(claims);

        return new OauthBearerTokenImpl(token, scope, lifetimeMs, principalName, startTimeMs);
    }

    private static String extractPrincipalName(Map<String, Object> claims) {
        if (!(claims.get(CLAIM_SUBJECT) instanceof String principalName)) {
            throw new IllegalArgumentException(
                    "Missing or invalid '" + CLAIM_SUBJECT + "' claim in JWT — expected a String");
        }
        return principalName;
    }

    private static long extractLifetimeMs(Map<String, Object> claims) {
        if (!(claims.get(CLAIM_EXPIRATION) instanceof Number expirationInSeconds)) {
            throw new IllegalArgumentException(
                    "Missing or invalid '" + CLAIM_EXPIRATION + "' claim in JWT — expected a Number (seconds since epoch)");
        }
        return expirationInSeconds.longValue() * SECONDS_TO_MILLIS;
    }

    private static Long extractStartTimeMs(Map<String, Object> claims, Clock clock) {
        return claims.get(CLAIM_ISSUED_AT) instanceof Number issuedAtInSeconds
                ? issuedAtInSeconds.longValue() * SECONDS_TO_MILLIS
                : clock.millis();
    }

    private static Set<String> parseScope(Map<String, Object> claims) {
        Set<String> scope = new HashSet<>();
        Object scopeObj = claims.get(CLAIM_SCOPE);

        if (scopeObj instanceof List<?> scopeList) {
            for (Object entry : scopeList) {
                if (entry instanceof String scopeValue && !scopeValue.isBlank()) {
                    scope.add(scopeValue);
                }
            }
        } else if (scopeObj instanceof String scopeString && !scopeString.isBlank()) {
            for (String s : scopeString.split("\\s+")) {
                if (!s.isBlank()) {
                    scope.add(s);
                }
            }
        }
        return scope;
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public Set<String> scope() {
        return scope;
    }

    @Override
    public long lifetimeMs() {
        return lifetimeMs;
    }

    @Override
    public String principalName() {
        return principalName;
    }

    @Override
    public Long startTimeMs() {
        return startTimeMs;
    }
}
