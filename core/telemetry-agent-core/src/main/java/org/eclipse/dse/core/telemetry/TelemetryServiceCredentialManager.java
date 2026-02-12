package org.eclipse.dse.core.telemetry;

import org.eclipse.dse.edc.spi.telemetryagent.TelemetryServiceClient;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.retry.WaitStrategy;
import org.eclipse.edc.spi.system.ExecutorInstrumentation;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.eclipse.edc.statemachine.StateMachineConfiguration.DEFAULT_ITERATION_WAIT;

public class TelemetryServiceCredentialManager {

    private static final long DEFAULT_RETRY_DELAY_SECONDS = 10L;
    
    private final WaitStrategy waitStrategy = () -> DEFAULT_ITERATION_WAIT;
    private final AtomicBoolean active = new AtomicBoolean();
    private final int shutdownTimeout = 10;
    private final String name = getClass().toString();

    private final Monitor monitor;
    private final TelemetryServiceClient telemetryServiceClient;
    private final TokenCache cache;
    private final ScheduledExecutorService executor;

    public TelemetryServiceCredentialManager(Monitor monitor, TelemetryServiceClient telemetryServiceClient, TokenCache cache, ExecutorInstrumentation instrumentation) {
        this.monitor = monitor;
        this.telemetryServiceClient = telemetryServiceClient;
        this.cache = cache;
        executor = instrumentation.instrument(
                Executors.newSingleThreadScheduledExecutor(r -> {
                    var thread = Executors.defaultThreadFactory().newThread(r);
                    thread.setName("TelemetryCredentialsManager-" + name);
                    return thread;
                }), name);
    }


    public Future<?> start() {
        active.set(true);
        return scheduleNextIterationIn(0L);
    }

    public CompletableFuture<Boolean> stop() {
        active.set(false);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executor.awaitTermination(shutdownTimeout, SECONDS);
            } catch (InterruptedException e) {
                monitor.severe(format("TelemetryCredentialsManager [%s] await termination failed", name), e);
                return false;
            }
        });
    }

    private Runnable loop() {
        return () -> {
            if (active.get()) {
                performLogic();
            }
        };
    }

    private void performLogic() {
        try {
            monitor.debug("Performing logic to get token");
            var result = telemetryServiceClient.fetchCredential();
            long delay = 0;
            if (result.succeeded()) {
                waitStrategy.success();
                var credential = result.getContent();
                cache.save(credential);
                delay = credential.getExpiresIn() / 2; // Renew credential when it reaches half its TTL
            } else {
                monitor.warning("Failed to fetch credential from Telemetry Service: " + result.getFailureDetail());
            }

            long finalDelay = delay > 0 ? delay : DEFAULT_RETRY_DELAY_SECONDS;
            monitor.debug(format("scheduleNextIteration in: %d seconds", finalDelay));
            
            scheduleNextIterationIn(finalDelay);
        } catch (Error e) {
            active.set(false);
            monitor.severe(format("Credential manager [%s] unrecoverable error", name), e);
        } catch (Throwable e) {
            monitor.severe(format("Credential manager [%s] error caught", name), e);
            scheduleNextIterationIn(waitStrategy.retryInMillis());
        }
    }

    @NotNull
    private Future<?> scheduleNextIterationIn(long delaySeconds) {
        return executor.schedule(loop(), delaySeconds, SECONDS);
    }
}
