package org.eclipse.edc.dse.telemetry.api;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Exposes the public JWKS (JSON Web Key Set) endpoint so that downstream verifiers
 * (e.g. the Kafka proxy) can retrieve the authority's public keys for JWT validation.
 */
@Path("/v1alpha")
@Produces(APPLICATION_JSON)
public class JwksApiController {

    private static final String JWKS_KEYS_FIELD = "keys";
    private static final String JWK_KID_FIELD = "kid";
    static final long CACHE_TTL_MS = 5 * 60 * 1_000L; //5 minutes

    private final Vault vault;
    private final String publicKeyAlias;
    private final Monitor monitor;
    private final Clock clock;

    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();

    public JwksApiController(Vault vault, String publicKeyAlias, Monitor monitor, Clock clock) {
        this.vault = vault;
        this.publicKeyAlias = publicKeyAlias;
        this.monitor = monitor;
        this.clock = clock;
    }

    @GET
    @Path("/jwks.json")
    public Map<String, Object> getJwks() {
        CacheEntry entry = cache.get();
        if (entry != null && !entry.isExpired(clock)) {
            return entry.jwks();
        }

        synchronized (this) {
            // Double-check after acquiring lock
            entry = cache.get();
            if (entry != null && !entry.isExpired(clock)) {
                return entry.jwks();
            }

            String publicKeyPem = vault.resolveSecret(publicKeyAlias);
            if (publicKeyPem == null) {
                monitor.warning("Public key not found in vault for alias: " + publicKeyAlias);
                cache.set(null);
                throw new NotFoundException("Public key with alias '" + publicKeyAlias + "' not found");
            }

            Map<String, Object> jwks = buildJwks(publicKeyPem);
            cache.set(new CacheEntry(jwks, clock.millis() + CACHE_TTL_MS));
            return jwks;
        }
    }

    private Map<String, Object> buildJwks(String publicKeyPem) {
        List<JWK> jwkList;
        try {
            Object parsed = JWK.parseFromPEMEncodedObjects(publicKeyPem);
            jwkList = toJwkList(parsed);
        } catch (JOSEException e) {
            monitor.severe("Failed to parse public key PEM for alias '" + publicKeyAlias + "': " + e.getMessage());
            throw new IllegalStateException("Cannot parse PEM key from vault alias '" + publicKeyAlias + "'", e);
        }

        var resultKeys = new ArrayList<Map<String, Object>>(jwkList.size());
        for (var jwk : jwkList) {
            var json = jwk.toPublicJWK().toJSONObject();
            json.put(JWK_KID_FIELD, publicKeyAlias);
            resultKeys.add(json);
        }

        monitor.debug("JWKS built successfully with " + resultKeys.size() + " key(s) for alias: " + publicKeyAlias);
        return Collections.singletonMap(JWKS_KEYS_FIELD, resultKeys);
    }

    @SuppressWarnings("unchecked")
    private List<JWK> toJwkList(Object parsed) {
        if (parsed instanceof List<?> list) {
            return (List<JWK>) list;
        }
        if (parsed instanceof JWK jwk) {
            return Collections.singletonList(jwk);
        }
        throw new IllegalStateException(
                "Unexpected type returned by JWK.parseFromPEMEncodedObjects: " + parsed.getClass().getName());
    }

    private record CacheEntry(Map<String, Object> jwks, long expiresAtMs) {
        boolean isExpired(Clock clock) {
            return clock.millis() > expiresAtMs;
        }
    }
}
