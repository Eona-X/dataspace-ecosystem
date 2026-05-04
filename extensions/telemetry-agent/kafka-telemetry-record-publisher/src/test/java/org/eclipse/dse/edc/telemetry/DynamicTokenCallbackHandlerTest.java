package org.eclipse.dse.edc.telemetry;

import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerTokenCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicTokenCallbackHandlerTest {

    @AfterEach
    void cleanRegistry() throws Exception {
        Field registryField = DynamicTokenCallbackHandler.class.getDeclaredField("TOKEN_REGISTRY");
        registryField.setAccessible(true);
        ((ConcurrentHashMap<?, ?>) registryField.get(null)).clear();
    }

    @Test
    void shouldAssignUniqueInstanceIdWhenCreatingManagedHandler() {
        DynamicTokenCallbackHandler handler1 = DynamicTokenCallbackHandler.createManaged();
        DynamicTokenCallbackHandler handler2 = DynamicTokenCallbackHandler.createManaged();

        assertThat(handler1.getInstanceId()).isNotBlank();
        assertThat(handler2.getInstanceId()).isNotBlank();
        assertThat(handler1.getInstanceId()).isNotEqualTo(handler2.getInstanceId());
    }

    @Test
    void shouldHandleNameCallbackAndPasswordCallback() throws Exception {
        DynamicTokenCallbackHandler handler = DynamicTokenCallbackHandler.createManaged();
        OAuthBearerToken token = mock(OAuthBearerToken.class);
        String tokenValue = "my-jwt-token";
        when(token.value()).thenReturn(tokenValue);
        handler.updateToken(token);

        NameCallback nameCallback = new NameCallback("user");
        PasswordCallback passwordCallback = new PasswordCallback("pass", false);

        handler.handle(new Callback[]{nameCallback, passwordCallback});

        assertThat(nameCallback.getName()).isEqualTo("oauth2");
        assertThat(new String(passwordCallback.getPassword())).isEqualTo(tokenValue);
    }

    @Test
    void shouldThrowIllegalStateExceptionWhenPasswordCallbackHasNoToken() {
        DynamicTokenCallbackHandler handler = DynamicTokenCallbackHandler.createManaged();
        PasswordCallback passwordCallback = new PasswordCallback("pass", false);

        assertThatThrownBy(() -> handler.handle(new Callback[]{passwordCallback}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No token available");
    }

    @Test
    void shouldThrowUnsupportedCallbackExceptionWhenCallbackTypeIsUnknown() {
        DynamicTokenCallbackHandler handler = DynamicTokenCallbackHandler.createManaged();
        Callback unknownCallback = mock(Callback.class);

        assertThatThrownBy(() -> handler.handle(new Callback[]{unknownCallback}))
                .isInstanceOf(UnsupportedCallbackException.class);
    }
    
    @Test
    void shouldClearTokenWhenRemoveIsCalled() {
        DynamicTokenCallbackHandler handler = DynamicTokenCallbackHandler.createManaged();
        handler.updateToken(mock(OAuthBearerToken.class));

        handler.remove();

        PasswordCallback callback = new PasswordCallback("pass", false);
        assertThatThrownBy(() -> handler.handle(new Callback[]{callback}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldCleanUpRegistryWhenCloseIsCalled() {
        DynamicTokenCallbackHandler handler = DynamicTokenCallbackHandler.createManaged();
        handler.updateToken(mock(OAuthBearerToken.class));

        handler.close();

        PasswordCallback callback = new PasswordCallback("pass", false);
        assertThatThrownBy(() -> handler.handle(new Callback[]{callback}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldNotAffectOtherInstancesWhenCloseIsCalled() throws Exception {
        DynamicTokenCallbackHandler handler1 = DynamicTokenCallbackHandler.createManaged();
        DynamicTokenCallbackHandler handler2 = DynamicTokenCallbackHandler.createManaged();

        OAuthBearerToken token2 = mock(OAuthBearerToken.class);
        String val2 = "token-2";
        when(token2.value()).thenReturn(val2);

        handler1.updateToken(mock(OAuthBearerToken.class));
        handler2.updateToken(token2);

        handler1.close();

        PasswordCallback callback = new PasswordCallback("pass", false);
        handler2.handle(new Callback[]{callback});
        assertThat(new String(callback.getPassword())).isEqualTo(val2);
    }

    @Test
    void shouldResolveInstanceIdWhenConfigureIsCalled() {
        DynamicTokenCallbackHandler handler = new DynamicTokenCallbackHandler();
        String expectedId = "my-instance-id";
        AppConfigurationEntry entry = buildJaasEntry(Map.of(DynamicTokenCallbackHandler.INSTANCE_ID_KEY, expectedId));

        handler.configure(Map.of(), "PLAIN", List.of(entry));

        assertThat(handler.getInstanceId()).isEqualTo(expectedId);
    }

    @Test
    void shouldNotSetInstanceIdWhenJaasEntryHasNoInstanceIdKey() {
        DynamicTokenCallbackHandler handler = new DynamicTokenCallbackHandler();
        AppConfigurationEntry entry = buildJaasEntry(Map.of());

        handler.configure(Map.of(), "PLAIN", List.of(entry));

        assertThat(handler.getInstanceId()).isNull();
    }

    @Test
    void shouldNotSetInstanceIdWhenJaasConfigEntriesIsEmpty() {
        DynamicTokenCallbackHandler handler = new DynamicTokenCallbackHandler();

        handler.configure(Map.of(), "PLAIN", List.of());

        assertThat(handler.getInstanceId()).isNull();
    }

    private AppConfigurationEntry buildJaasEntry(Map<String, ?> options) {
        return new AppConfigurationEntry(
                "org.apache.kafka.common.security.plain.PlainLoginModule",
                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                options
        );
    }
}
