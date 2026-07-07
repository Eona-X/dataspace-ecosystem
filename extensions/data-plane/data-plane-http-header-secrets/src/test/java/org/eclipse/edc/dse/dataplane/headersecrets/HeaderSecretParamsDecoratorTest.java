package org.eclipse.edc.dse.dataplane.headersecrets;

import org.eclipse.edc.connector.dataplane.http.spi.HttpDataAddress;
import org.eclipse.edc.connector.dataplane.http.spi.HttpRequestParams;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeaderSecretParamsDecoratorTest {

    private final Vault vault = mock(Vault.class);
    private final Monitor monitor = mock(Monitor.class);
    private final HeaderSecretParamsDecorator decorator = new HeaderSecretParamsDecorator(vault, monitor);

    @Test
    void decorate_shouldAddHeader_whenSingleHeaderSecretIsPresent() {
        String secretAlias = "bearer-secret-alias";
        String secretValue = "bearer-secret-value";
        when(vault.resolveSecret(secretAlias)).thenReturn(secretValue);

        HttpDataAddress address = HttpDataAddress.Builder.newInstance()
                .baseUrl("http://test.base.url")
                .property("header-secret:Authorization", secretAlias)
                .build();

        HttpRequestParams.Builder params = decorator.decorate(createRequest(), address, newParamsBuilder());

        assertThat(params.build().getHeaders()).containsEntry("Authorization", secretValue);
    }

    @Test
    void decorate_shouldAddAllHeaders_whenMultipleHeaderSecretsArePresent() {
        String bearerAlias = "bearer-secret-alias";
        String apiKeyAlias = "api-key-secret-alias";
        when(vault.resolveSecret(bearerAlias)).thenReturn("bearer-secret-value");
        when(vault.resolveSecret(apiKeyAlias)).thenReturn("api-key-secret-value");

        HttpDataAddress address = HttpDataAddress.Builder.newInstance()
                .baseUrl("http://test.base.url")
                .property("header-secret:Authorization", bearerAlias)
                .property("header-secret:x-api-key", apiKeyAlias)
                .build();

        HttpRequestParams.Builder params = decorator.decorate(createRequest(), address, newParamsBuilder());

        assertThat(params.build().getHeaders())
                .containsEntry("Authorization", "bearer-secret-value")
                .containsEntry("x-api-key", "api-key-secret-value");
    }

    @Test
    void decorate_shouldThrowAndAddNoHeader_whenSecretIsMissingFromVault() {
        String missingAlias = "missing-secret-alias";
        when(vault.resolveSecret(missingAlias)).thenReturn(null);

        HttpDataAddress address = HttpDataAddress.Builder.newInstance()
                .baseUrl("http://test.base.url")
                .property("header-secret:Authorization", missingAlias)
                .build();

        DataFlowStartMessage request = createRequest();

        assertThatExceptionOfType(EdcException.class)
                .isThrownBy(() -> decorator.decorate(request, address, HttpRequestParams.Builder.newInstance()));

        verify(monitor).severe(anyString());
    }

    @Test
    void decorate_shouldAddNoHeaderAndAggregateAllErrors_whenSeveralSecretsAreMissing() {
        String presentAlias = "present-secret-alias";
        String missingAlias1 = "missing-secret-alias-1";
        String missingAlias2 = "missing-secret-alias-2";
        when(vault.resolveSecret(presentAlias)).thenReturn("present-secret-value");
        when(vault.resolveSecret(missingAlias1)).thenReturn(null);
        when(vault.resolveSecret(missingAlias2)).thenReturn(null);

        HttpDataAddress address = HttpDataAddress.Builder.newInstance()
                .baseUrl("http://test.base.url")
                .property("header-secret:Authorization", presentAlias)
                .property("header-secret:x-api-key", missingAlias1)
                .property("header-secret:x-custom", missingAlias2)
                .build();

        DataFlowStartMessage request = createRequest();

        assertThatExceptionOfType(EdcException.class)
                .isThrownBy(() -> decorator.decorate(request, address, HttpRequestParams.Builder.newInstance()))
                .withMessageContaining(missingAlias1)
                .withMessageContaining(missingAlias2);

        verify(vault).resolveSecret(presentAlias);
        verify(vault).resolveSecret(missingAlias1);
        verify(vault).resolveSecret(missingAlias2);
        verify(monitor, times(2)).severe(anyString());
    }

    @Test
    void decorate_shouldLeaveParamsUnaffected_whenNoHeaderSecretPropertyIsPresent() {
        HttpDataAddress address = HttpDataAddress.Builder.newInstance()
                .baseUrl("http://test.base.url")
                .authKey("existing-auth-key")
                .secretName("existing-secret-name")
                .build();

        HttpRequestParams.Builder params = decorator.decorate(createRequest(), address, newParamsBuilder());

        assertThat(params.build().getHeaders()).isEmpty();
    }

    private HttpRequestParams.Builder newParamsBuilder() {
        return HttpRequestParams.Builder.newInstance()
                .baseUrl("http://test.base.url")
                .method("GET");
    }

    private DataFlowStartMessage createRequest() {
        return DataFlowStartMessage.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .processId(UUID.randomUUID().toString())
                .sourceDataAddress(DataAddress.Builder.newInstance().type("test-type").build())
                .destinationDataAddress(DataAddress.Builder.newInstance().type("test-type").build())
                .build();
    }
}
