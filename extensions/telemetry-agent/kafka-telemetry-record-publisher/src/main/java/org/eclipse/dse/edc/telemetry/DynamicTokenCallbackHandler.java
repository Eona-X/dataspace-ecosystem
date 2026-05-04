package org.eclipse.dse.edc.telemetry;

import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import javax.security.auth.login.AppConfigurationEntry;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Kafka AuthenticateCallbackHandler that allows dynamic token injection for SASL/PLAIN (Hybrid Mode).
 * <p>
 * This handler is focused on the JWT-over-PLAIN mechanism where the JWT is passed as the password.
 * </p>
 */
public class DynamicTokenCallbackHandler implements AuthenticateCallbackHandler {

    public static final String INSTANCE_ID_KEY = "instance.id";
    private static final String OAUTH2_USERNAME = "oauth2";
    private static final Map<String, OAuthBearerToken> TOKEN_REGISTRY = new ConcurrentHashMap<>();

    private String instanceId;

    public DynamicTokenCallbackHandler() {
        // Required by Kafka's reflection-based instantiation
    }

    private DynamicTokenCallbackHandler(String instanceId) {
        this.instanceId = instanceId;
    }

    /**
     * Creates a managed instance of the handler.
     */
    public static DynamicTokenCallbackHandler createManaged() {
        return new DynamicTokenCallbackHandler(UUID.randomUUID().toString());
    }

    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Updates the token for this specific instance.
     */
    public void updateToken(OAuthBearerToken token) {
        TOKEN_REGISTRY.put(instanceId, token);
    }

    /**
     * Removes the token for this instance.
     */
    public void remove() {
        TOKEN_REGISTRY.remove(instanceId);
    }

    @Override
    public void configure(Map<String, ?> configs, String saslMechanism, List<AppConfigurationEntry> jaasConfigEntries) {
        for (AppConfigurationEntry entry : jaasConfigEntries) {
            String id = (String) entry.getOptions().get(INSTANCE_ID_KEY);
            if (id != null) {
                this.instanceId = id;
                break;
            }
        }
    }

    @Override
    public void handle(Callback[] callbacks) throws UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            if (callback instanceof NameCallback nameCallback) {
                nameCallback.setName(OAUTH2_USERNAME);
            } else if (callback instanceof PasswordCallback passwordCallback) {
                handlePasswordCallback(passwordCallback);
            } else {
                throw new UnsupportedCallbackException(callback);
            }
        }
    }

    private void handlePasswordCallback(PasswordCallback callback) {
        OAuthBearerToken token = TOKEN_REGISTRY.get(instanceId);
        if (token == null) {
            throw new IllegalStateException("No token available for SASL/PLAIN authentication (instance: " + instanceId + ")");
        }
        callback.setPassword(token.value().toCharArray());
    }

    @Override
    public void close() {
        remove();
    }
}
