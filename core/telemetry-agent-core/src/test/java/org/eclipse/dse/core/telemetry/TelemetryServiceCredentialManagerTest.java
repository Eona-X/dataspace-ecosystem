package org.eclipse.dse.core.telemetry;

import org.eclipse.dse.edc.spi.telemetryagent.TelemetryServiceClient;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.system.ExecutorInstrumentation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelemetryServiceCredentialManagerTest {

    private final TokenCache cache = mock();
    private final TelemetryServiceClient telemetryServiceClient = mock();
    private TelemetryServiceCredentialManager manager;


    @BeforeEach
    void setUp() {
        manager = new TelemetryServiceCredentialManager(mock(), telemetryServiceClient, cache, ExecutorInstrumentation.noop(), true);
    }

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    @Test
    void save_success() {
        var token = TokenRepresentation.Builder.newInstance()
                .token("test")
                .expiresIn(100L)
                .build();

        when(telemetryServiceClient.fetchCredential()).thenReturn(Result.success(token));

        manager.start();

        await().untilAsserted(() -> verify(cache).save(token));
    }

    @Test
    void save_clientFails_noCredentialStored() {
        when(telemetryServiceClient.fetchCredential()).thenReturn(Result.failure("error"));

        manager.start();

        await().untilAsserted(() -> verifyNoInteractions(cache));
    }

    @Test
    void disabled_start_savesDummyTokenAndSkipsClient() {
        var disabled = new TelemetryServiceCredentialManager(mock(), telemetryServiceClient, cache, ExecutorInstrumentation.noop(), false);

        disabled.start();

        verify(cache).save(argThat(t -> "".equals(t.getToken())));
        verifyNoInteractions(telemetryServiceClient);
    }

    @Test
    void disabled_stop_isNoOp() throws Exception {
        var disabled = new TelemetryServiceCredentialManager(mock(), telemetryServiceClient, cache, ExecutorInstrumentation.noop(), false);

        var result = disabled.stop().get();

        assertTrue(result);
    }
}