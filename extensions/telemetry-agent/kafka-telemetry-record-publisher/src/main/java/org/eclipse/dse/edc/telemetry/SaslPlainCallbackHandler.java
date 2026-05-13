package org.eclipse.dse.edc.telemetry;

import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import java.util.List;
import java.util.Map;

/**
 * A Kafka AuthenticateCallbackHandler for SASL/PLAIN authentication.
 * <p>
 * The credentials (username and password) are provided via configuration
 * and injected through the JAAS config options.
 * </p>
 */
public class SaslPlainCallbackHandler implements AuthenticateCallbackHandler {

    public static final String USERNAME_KEY = "username";
    public static final String PASSWORD_KEY = "password";

    private String username;
    private String password;

    public SaslPlainCallbackHandler() {
        // Required by Kafka's reflection-based instantiation
    }

    @Override
    public void configure(Map<String, ?> configs, String saslMechanism, List<AppConfigurationEntry> jaasConfigEntries) {
        for (AppConfigurationEntry entry : jaasConfigEntries) {
            String username = (String) entry.getOptions().get(USERNAME_KEY);
            if (username != null) {
                this.username = username;
            }
            String password = (String) entry.getOptions().get(PASSWORD_KEY);
            if (password != null) {
                this.password = password;
            }
        }
    }

    @Override
    public void handle(Callback[] callbacks) throws UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            if (callback instanceof NameCallback nameCallback) {
                if (username == null) {
                    throw new IllegalStateException("No username configured in JAAS options");
                }
                nameCallback.setName(username);
            } else if (callback instanceof PasswordCallback passwordCallback) {
                if (password == null) {
                    throw new IllegalStateException("No password configured in JAAS options");
                }
                passwordCallback.setPassword(password.toCharArray());
            } else {
                throw new UnsupportedCallbackException(callback);
            }
        }
    }

    @Override
    public void close() {
        // No resources to clean up
    }
}
