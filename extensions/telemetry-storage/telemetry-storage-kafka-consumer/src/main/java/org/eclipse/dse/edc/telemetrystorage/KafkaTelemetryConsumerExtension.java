package org.eclipse.dse.edc.telemetrystorage;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecord;
import org.eclipse.dse.edc.telemetrystorage.mapper.TelemetryEventMapper;
import org.eclipse.dse.spi.telemetrystorage.TelemetryEventStore;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.StoreFailure;
import org.eclipse.edc.spi.result.StoreResult;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.types.TypeManager;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * EDC extension that consumes telemetry records from Kafka and persists them
 * to the central {@link TelemetryEventStore}.
 *
 * <p><b>Error Handling Strategy:</b>
 * <ul>
 *   <li><b>Permanent failures</b> (e.g. corrupted/unparseable JSON, invalid mapping):
 *       The offset is committed to skip the poisoned message. The error is logged as SEVERE.</li>
 *   <li><b>Transient failures</b> (e.g. database unavailable): The offset is NOT committed.
 *       Polling pauses with exponential backoff. After {@value DEFAULT_MAX_RETRIES} consecutive
 *       failures the consumer stops and must be restarted.</li>
 * </ul>
 *
 * <p><b>Offset Commit Strategy:</b>
 * Offsets are committed once per batch (not per message) to reduce broker load.
 * On infrastructure failure, offsets of all records successfully processed so far
 * in the current batch are committed before stopping.
 *
 * <p><b>Thread Safety:</b>
 * The consumer runs on a single dedicated daemon thread. All lifecycle transitions
 * (STARTING → RUNNING → STOPPED) use {@link AtomicReference#compareAndSet} to prevent
 * races between the consumer thread and the EDC runtime thread calling {@link #shutdown()}.
 *
 * <p><b>Kafka Heartbeat / Rebalance:</b>
 * {@value DEFAULT_MAX_RETRIES} retries with exponential backoff can block the consumer thread
 * for up to ~31 seconds. To prevent a group rebalance during this window,
 * {@code max.poll.interval.ms} must be configured above this value (default: 5 minutes,
 * which is sufficient for the default retry parameters).
 */
@Extension(value = KafkaTelemetryConsumerExtension.NAME)
public class KafkaTelemetryConsumerExtension implements ServiceExtension {

    public static final String NAME = "Kafka Telemetry Consumer";
    private static final String THREAD_NAME = "kafka-telemetry-consumer";
    private static final String ENABLE_AUTO_COMMIT_FALSE = "false";
    private static final String AUTO_OFFSET_RESET_EARLIEST = "earliest";
    private static final long DEFAULT_INITIAL_RETRY_DELAY_MS = 1_000;
    private static final long DEFAULT_MAX_RETRY_DELAY_MS = 30_000;
    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final Duration POLL_DURATION = Duration.ofMillis(1_000);
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    // Instance fields — not static final — so tests can override via reflection
    // without hitting Java's compile-time constant inlining.
    long initialRetryDelayMs = DEFAULT_INITIAL_RETRY_DELAY_MS;
    long maxRetryDelayMs = DEFAULT_MAX_RETRY_DELAY_MS;
    int maxRetries = DEFAULT_MAX_RETRIES;

    private enum State {
        STARTING, RUNNING, STOPPED
    }

    private TelemetryEventMapper mapper;

    @Setting(key = "dse.telemetry-storage.kafka.bootstrap.servers")
    private String bootstrapServers;

    @Setting(required = false, key = "dse.telemetry-storage.kafka.topic", defaultValue = "telemetry")
    private String topic;

    @Setting(required = false, key = "dse.telemetry-storage.kafka.group.id", defaultValue = "telemetry-storage-group")
    private String groupId;

    @Inject
    private TelemetryEventStore store;

    @Inject
    private TypeManager typeManager;

    @Inject
    private Monitor monitor;

    private KafkaConsumer<String, String> consumer;
    private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
    private ExecutorService executor;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start() {
        Objects.requireNonNull(bootstrapServers,
                "Missing required setting: dse.telemetry-storage.kafka.bootstrap.servers");

        mapper = new TelemetryEventMapper();
        consumer = new KafkaConsumer<>(buildConsumerProperties());
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::pollLoop);
        monitor.info("Kafka telemetry consumer started for topic: " + topic + " (group: " + groupId + ")");
    }

    private Properties buildConsumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, ENABLE_AUTO_COMMIT_FALSE);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, AUTO_OFFSET_RESET_EARLIEST);
        return properties;
    }

    private void pollLoop() {
        if (!state.compareAndSet(State.STARTING, State.RUNNING)) {
            monitor.warning("Consumer thread started but state is already "
                    + state.get() + " — aborting.");
            return;
        }

        try {
            consumer.subscribe(Collections.singletonList(topic));
            while (isRunning()) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_DURATION);
                if (!processBatch(records)) {
                    break;
                }
            }
        } catch (WakeupException e) {
            if (state.get() != State.STOPPED) {
                throw e;
            }
            monitor.debug("WakeupException caught: expected during shutdown");
        } finally {
            consumer.close();
            monitor.debug("Kafka consumer closed");
        }
    }

    private boolean processBatch(ConsumerRecords<String, String> records) {
        Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();

        for (ConsumerRecord<String, String> record : records) {
            if (!isRunning()) {
                break;
            }

            if (processWithRetry(record)) {
                // offset + 1 because Kafka commits the *next* offset to fetch.
                offsetsToCommit.put(
                        new TopicPartition(record.topic(), record.partition()),
                        new OffsetAndMetadata(record.offset() + 1)
                );
            } else {
                monitor.severe("INFRASTRUCTURE DOWN — stopping consumer until restart. "
                        + "Last attempted offset: " + record.offset()
                        + " partition: " + record.partition());

                state.compareAndSet(State.RUNNING, State.STOPPED);
                safeCommitOffsets(offsetsToCommit);
                return false;
            }
        }

        safeCommitOffsets(offsetsToCommit);
        return true;
    }

    /**
     * Commits offsets, swallowing and logging any exception so that a commit failure
     * cannot crash the consumer thread or mask a different in-flight error.
     */
    private void safeCommitOffsets(Map<TopicPartition, OffsetAndMetadata> offsets) {
        if (offsets.isEmpty()) {
            return;
        }
        try {
            consumer.commitSync(offsets);
        } catch (KafkaException e) {
            monitor.severe("OFFSET COMMIT FAILED — offsets may be re-processed after restart: "
                    + e.getMessage());
        }
    }

    /**
     * Attempts to process a single record, retrying on transient failures with
     * exponential backoff up to {@value DEFAULT_MAX_RETRIES} attempts.
     *
     * @return {@code true}  if the record was definitively handled (persisted,
     *                       skipped as permanent error, or skipped as duplicate);
     *         {@code false} if all retries were exhausted.
     */
    private boolean processWithRetry(ConsumerRecord<String, String> record) {
        long retryDelay = initialRetryDelayMs;

        for (int attempt = 0; attempt < maxRetries && isRunning(); attempt++) {
            if (processRecord(record)) {
                return true;
            }
            monitor.warning("Transient failure — retry " + (attempt + 1) + "/" + maxRetries
                    + " for offset " + record.offset() + " in " + retryDelay + "ms");
            sleep(retryDelay);
            retryDelay = Math.min(retryDelay * 2, maxRetryDelayMs);
        }

        monitor.severe("MAX RETRIES EXHAUSTED for offset " + record.offset()
                + " partition " + record.partition());
        return false;
    }

    /**
     * Processes a single Kafka record end-to-end: deserialize → map → persist.
     *
     * @return {@code true}  if the record was handled (persisted, duplicate detected
     *                       via ALREADY_EXISTS, or skipped due to a permanent error)
     *         {@code false} if a transient error occurred and the record should be retried
     */
    private boolean processRecord(ConsumerRecord<String, String> record) {
        try {
            var telemetryRecord = typeManager.readValue(record.value(), TelemetryRecord.class);
            var telemetryEvent = mapper.mapToEvent(telemetryRecord);

            StoreResult<Void> result = store.save(telemetryEvent);

            if (result.succeeded()) {
                monitor.debug("Persisted telemetry event from offset " + record.offset());
                return true;
            }

            if (result.getFailure().getReason() == StoreFailure.Reason.ALREADY_EXISTS) {
                monitor.warning("DUPLICATE MESSAGE SKIPPED at offset " + record.offset()
                        + " — already persisted (messageId: " + telemetryEvent.id() + ")");
                return true;
            }

            monitor.severe("PERSISTENCE FAILED — RETRYING: offset " + record.offset()
                    + " — " + result.getFailureDetail());
            return false;

        } catch (EdcException | IllegalArgumentException e) {
            monitor.severe("PERMANENT ERROR — SKIPPING message at offset " + record.offset()
                    + " partition " + record.partition() + ": " + e.getMessage());
            return true;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void shutdown() {
        if (state.compareAndSet(State.RUNNING, State.STOPPED)
                || state.compareAndSet(State.STARTING, State.STOPPED)) {

            consumer.wakeup();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    monitor.warning("Consumer thread did not terminate within "
                            + SHUTDOWN_TIMEOUT_SECONDS + "s — forcing shutdown");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            monitor.info("Kafka telemetry consumer extension shut down");
        }
    }

    public boolean isRunning() {
        return state.get() == State.RUNNING && !Thread.currentThread().isInterrupted();
    }
}
