package org.eclipse.dse.core.telemetry;

import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecordPublisherFactory;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecordStore;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryServiceClient;
import org.eclipse.dse.spi.telemetry.RequestTelemetryPolicyContext;
import org.eclipse.dse.spi.telemetry.TelemetryPolicy;
import org.eclipse.edc.http.spi.EdcHttpClient;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.jwt.signer.spi.JwsSignerProvider;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Provides;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.retry.ExponentialWaitStrategy;
import org.eclipse.edc.spi.system.ExecutorInstrumentation;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.telemetry.Telemetry;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.statemachine.retry.EntityRetryProcessConfiguration;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;

import static org.eclipse.dse.spi.telemetry.RequestTelemetryPolicyContext.TELEMETRY_REQUEST_SCOPE;
import static org.eclipse.edc.statemachine.StateMachineConfiguration.DEFAULT_BATCH_SIZE;
import static org.eclipse.edc.statemachine.StateMachineConfiguration.DEFAULT_ITERATION_WAIT;
import static org.eclipse.edc.statemachine.StateMachineConfiguration.DEFAULT_SEND_RETRY_BASE_DELAY;
import static org.eclipse.edc.statemachine.StateMachineConfiguration.DEFAULT_SEND_RETRY_LIMIT;


@Provides({TelemetryAgent.class, TelemetryServiceCredentialManager.class})
public class TelemetryAgentCoreExtension implements ServiceExtension {

    @Setting(description = "The iteration wait time in milliseconds in the telemetry agent state machine. Default value " + DEFAULT_ITERATION_WAIT, type = "long")
    private static final String TELEMETRY_AGENT_MACHINE_ITERATION_WAIT_MILLIS = "dse.telemetry-agent.state-machine.iteration-wait-millis";

    @Setting(description = "The batch size in the telemetry agent state machine. Default value " + DEFAULT_BATCH_SIZE, type = "int")
    private static final String TELEMETRY_AGENT_MACHINE_BATCH_SIZE = "dse.telemetry-agent.state-machine.batch-size";

    @Setting(description = "How many times a specific operation must be tried before terminating the telemetry agent with error", type = "int", defaultValue = DEFAULT_SEND_RETRY_LIMIT + "")
    private static final String TELEMETRY_AGENT_SEND_RETRY_LIMIT = "dse.telemetry-agent.send.retry.limit";

    @Setting(description = "The base delay for the telemetry agent retry mechanism in millisecond", type = "long", defaultValue = DEFAULT_SEND_RETRY_BASE_DELAY + "")
    private static final String TELEMETRY_AGENT_SEND_RETRY_BASE_DELAY_MS = "dse.telemetry-agent.send.retry.base-delay.ms";

    @Setting(description = "Authority did", key = "dse.authority.did", required = true)
    public String authorityDid;

    @Setting(description = "Vault alias of the private key used to sign access token for the telemetry service", key = "dse.credential-manager.private-key.alias", required = true)
    public String privateKeyAlias;

    @Inject
    private TelemetryRecordPublisherFactory publisherFactory;

    @Inject
    private Monitor monitor;

    @Inject
    private TypeManager typeManager;

    @Inject
    private Clock clock;

    @Inject
    private TelemetryRecordStore store;

    @Inject
    private ExecutorInstrumentation executorInstrumentation;

    @Inject
    private Telemetry telemetry;

    @Inject
    private EdcHttpClient httpClient;

    @Inject
    private DidResolverRegistry didResolverRegistry;

    @Inject
    private JwsSignerProvider jwsSignerProvider;

    @Inject
    private IdentityService identityService;

    @Inject
    private PolicyEngine policyEngine;

    @Inject
    private TelemetryPolicy telemetryPolicy;

    private TelemetryAgent telemetryAgent;

    private TelemetryServiceCredentialManager credentialsManager;


    @Override
    public String name() {
        return "Telemetry Agent Core";
    }

    @Override
    public void start() {
        telemetryAgent.start();
        credentialsManager.start();
    }

    @Override
    public void shutdown() {
        if (telemetryAgent != null) {
            telemetryAgent.stop();
        }
        if (credentialsManager != null) {
            credentialsManager.stop();
        }
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        // Register telemetry policy scope for proper policy evaluation
        policyEngine.registerScope(TELEMETRY_REQUEST_SCOPE, RequestTelemetryPolicyContext.class);

        var cache = new TokenCache();
        var iterationWaitMillis = context.getSetting(TELEMETRY_AGENT_MACHINE_ITERATION_WAIT_MILLIS, DEFAULT_ITERATION_WAIT);
        var waitStrategy = new ExponentialWaitStrategy(iterationWaitMillis);
        telemetryAgent = TelemetryAgent.Builder.newInstance()
                .waitStrategy(waitStrategy)
                .batchSize(context.getSetting(TELEMETRY_AGENT_MACHINE_BATCH_SIZE, DEFAULT_BATCH_SIZE))
                .clock(clock)
                .entityRetryProcessConfiguration(entityRetryProcessConfiguration(context))
                .executorInstrumentation(executorInstrumentation)
                .store(store)
                .monitor(monitor)
                .telemetry(telemetry)
                .publisherFactory(publisherFactory)
                .credentialsCache(cache)
                .build();
        context.registerService(TelemetryAgent.class, telemetryAgent);

        var telemetryServiceClient = defaultTelemetryServiceClient(context);

        credentialsManager = new TelemetryServiceCredentialManager(monitor, telemetryServiceClient, cache, executorInstrumentation);
        context.registerService(TelemetryServiceCredentialManager.class, credentialsManager);
    }

    @Provider
    public TelemetryServiceClient defaultTelemetryServiceClient(ServiceExtensionContext context) {
        return new TelemetryServiceClientImpl(httpClient, typeManager, didResolverRegistry,
                authorityDid, identityService, policyEngine, telemetryPolicy, context.getParticipantId(), clock);
    }

    @NotNull
    private static EntityRetryProcessConfiguration entityRetryProcessConfiguration(ServiceExtensionContext context) {
        var retryLimit = context.getSetting(TELEMETRY_AGENT_SEND_RETRY_LIMIT, DEFAULT_SEND_RETRY_LIMIT);
        var retryBaseDelay = context.getSetting(TELEMETRY_AGENT_SEND_RETRY_BASE_DELAY_MS, DEFAULT_SEND_RETRY_BASE_DELAY);
        return new EntityRetryProcessConfiguration(retryLimit, () -> new ExponentialWaitStrategy(retryBaseDelay));
    }

}