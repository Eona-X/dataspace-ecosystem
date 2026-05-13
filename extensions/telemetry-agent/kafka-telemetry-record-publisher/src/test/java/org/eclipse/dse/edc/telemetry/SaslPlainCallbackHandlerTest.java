package org.eclipse.dse.edc.telemetry;

import org.junit.jupiter.api.Test;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SaslPlainCallbackHandlerTest {

    private static final String TEST_USERNAME = "dev-telemetry";
    private static final String TEST_PASSWORD = "password";
    private static final String SASL_MECHANISM = "PLAIN";

    @Test
    void shouldHandleNameCallbackAndPasswordCallbackAfterConfigure() throws Exception {
        SaslPlainCallbackHandler handler = new SaslPlainCallbackHandler();
        AppConfigurationEntry entry = buildJaasEntry(Map.of(
                SaslPlainCallbackHandler.USERNAME_KEY, TEST_USERNAME,
                SaslPlainCallbackHandler.PASSWORD_KEY, TEST_PASSWORD
        ));

        handler.configure(Map.of(), SASL_MECHANISM, List.of(entry));

        NameCallback nameCallback = new NameCallback("user");
        PasswordCallback passwordCallback = new PasswordCallback("pass", false);

        handler.handle(new Callback[]{nameCallback, passwordCallback});

        assertThat(nameCallback.getName()).isEqualTo(TEST_USERNAME);
        assertThat(new String(passwordCallback.getPassword())).isEqualTo(TEST_PASSWORD);
    }

    @Test
    void shouldThrowIllegalStateExceptionWhenUsernameIsNotConfigured() {
        SaslPlainCallbackHandler handler = new SaslPlainCallbackHandler();
        NameCallback nameCallback = new NameCallback("user");

        assertThatThrownBy(() -> handler.handle(new Callback[]{nameCallback}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No username configured");
    }

    @Test
    void shouldThrowIllegalStateExceptionWhenPasswordIsNotConfigured() {
        SaslPlainCallbackHandler handler = new SaslPlainCallbackHandler();
        PasswordCallback passwordCallback = new PasswordCallback("pass", false);

        assertThatThrownBy(() -> handler.handle(new Callback[]{passwordCallback}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No password configured");
    }

    @Test
    void shouldThrowUnsupportedCallbackExceptionWhenCallbackTypeIsUnknown() {
        SaslPlainCallbackHandler handler = new SaslPlainCallbackHandler();
        Callback unknownCallback = mock(Callback.class);

        assertThatThrownBy(() -> handler.handle(new Callback[]{unknownCallback}))
                .isInstanceOf(UnsupportedCallbackException.class);
    }

    private AppConfigurationEntry buildJaasEntry(Map<String, ?> options) {
        return new AppConfigurationEntry(
                "org.apache.kafka.common.security.plain.PlainLoginModule",
                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                options
        );
    }
}
