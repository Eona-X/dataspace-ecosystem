package org.eclipse.dse.edc.telemetry;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecord;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.types.TypeManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaTelemetryRecordPublisherTest {

    private static final String TOPIC = "telemetry";
    private static final String CONTRACT_ID = "contract-123";
    private static final String SERIALIZED_RECORD = "{\"contractId\":\"contract-123\"}";

    @Mock private KafkaProducer<String, String> producer;
    @Mock private TypeManager typeManager;
    @Mock private Monitor monitor;

    private KafkaTelemetryRecordPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaTelemetryRecordPublisher(producer, TOPIC, typeManager, monitor);
    }

    @Test
    void shouldSendRecordSuccessfully() throws Exception {
        TelemetryRecord record = buildRecord(CONTRACT_ID);
        mockSuccessfulSend(record);

        Boolean result = publisher.sendRecord(record);

        assertThat(result).isTrue();

        List<ProducerRecord<String, String>> sent = captureSentRecords();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).topic()).isEqualTo(TOPIC);
        assertThat(sent.get(0).key()).isEqualTo(CONTRACT_ID);
        assertThat(sent.get(0).value()).isEqualTo(SERIALIZED_RECORD);
    }

    @Test
    void shouldAddMessageIdIfMissing() throws Exception {
        TelemetryRecord record = buildRecord(CONTRACT_ID);
        mockSuccessfulSend(record);

        publisher.sendRecord(record);

        assertThat(record.getProperties()).containsKey("messageId");
        assertThat(record.getProperties().get("messageId")).isNotNull().isInstanceOf(String.class);
    }

    @Test
    void shouldNotOverwriteExistingMessageId() throws Exception {
        TelemetryRecord record = buildRecord(CONTRACT_ID);
        String existingId = "existing-id";
        record.getProperties().put("messageId", existingId);
        mockSuccessfulSend(record);

        publisher.sendRecord(record);

        assertThat(record.getProperties().get("messageId")).isEqualTo(existingId);
    }

    @Test
    void shouldUseNullKeyWhenContractIdMissing() throws Exception {
        TelemetryRecord record = buildRecord(null);
        mockSuccessfulSend(record);

        publisher.sendRecord(record);

        List<ProducerRecord<String, String>> sent = captureSentRecords();
        assertThat(sent.get(0).key()).isNull();
    }

    @Test
    void shouldHandleNonStringContractIdByIgnoringIt() throws Exception {
        TelemetryRecord record = TelemetryRecord.Builder.newInstance()
                .type("test-type")
                .property("contractId", 123)
                .build();
        mockSuccessfulSend(record);

        Boolean result = publisher.sendRecord(record);

        assertThat(result).isTrue();
        verify(monitor).warning(contains("contractId is not a String"));

        List<ProducerRecord<String, String>> sent = captureSentRecords();
        assertThat(sent.get(0).key()).isNull();
    }

    @Test
    void shouldReturnFalseWhenRecordTooLarge() throws Exception {
        TelemetryRecord record = buildRecord(CONTRACT_ID);
        when(typeManager.writeValueAsString(record)).thenReturn(SERIALIZED_RECORD);

        Future<RecordMetadata> future = mock(Future.class);
        when(future.get()).thenThrow(new ExecutionException(new RecordTooLargeException()));
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);

        Boolean result = publisher.sendRecord(record);

        assertThat(result).isFalse();
        verify(monitor).warning(contains("RecordTooLarge"));
    }

    @Test
    void shouldReturnFalseOnGenericExecutionException() throws Exception {
        TelemetryRecord record = buildRecord(CONTRACT_ID);
        when(typeManager.writeValueAsString(record)).thenReturn(SERIALIZED_RECORD);

        Future<RecordMetadata> future = mock(Future.class);
        when(future.get()).thenThrow(new ExecutionException(new RuntimeException("boom")));
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);

        Boolean result = publisher.sendRecord(record);

        assertThat(result).isFalse();
        verify(monitor).severe(contains("Failed to send telemetry record"));
    }

    @Test
    void shouldReturnFalseOnInterruptedException() throws Exception {
        TelemetryRecord record = buildRecord(CONTRACT_ID);
        when(typeManager.writeValueAsString(record)).thenReturn(SERIALIZED_RECORD);

        Future<RecordMetadata> future = mock(Future.class);
        when(future.get()).thenThrow(new InterruptedException());
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);

        Boolean result = publisher.sendRecord(record);

        assertThat(result).isFalse();
        verify(monitor).severe(contains("interrupted"));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    void shouldReturnFalseWhenProducerSendThrowsRuntimeException() {
        TelemetryRecord record = buildRecord(CONTRACT_ID);
        when(typeManager.writeValueAsString(record)).thenReturn(SERIALIZED_RECORD);
        when(producer.send(any(ProducerRecord.class))).thenThrow(new RuntimeException("kafka down"));

        Boolean result = publisher.sendRecord(record);

        assertThat(result).isFalse();
        verify(monitor).severe(contains("Unexpected error"));
    }

    @Test
    void shouldReturnFalseWhenSerializationFailsWithEdcException() {
        TelemetryRecord record = buildRecord(CONTRACT_ID);
        when(typeManager.writeValueAsString(record)).thenThrow(new EdcException("serialization error"));

        Boolean result = publisher.sendRecord(record);

        assertThat(result).isFalse();
        verify(monitor).severe(contains("Failed to serialize"));
        verifyNoInteractions(producer);
    }

    @Test
    void shouldReturnFalseWhenSerializationThrowsRuntimeException() {
        TelemetryRecord record = buildRecord(CONTRACT_ID);
        when(typeManager.writeValueAsString(record)).thenThrow(new RuntimeException("serializer crash"));

        Boolean result = publisher.sendRecord(record);

        assertThat(result).isFalse();
        verify(monitor).severe(contains("Unexpected error"));
        verifyNoInteractions(producer);
    }

    @Test
    void shouldReturnFalseWhenTopicIsNull() {
        KafkaTelemetryRecordPublisher publisherNullTopic =
                new KafkaTelemetryRecordPublisher(producer, null, typeManager, monitor);

        assertThat(publisherNullTopic.sendRecord(buildRecord(CONTRACT_ID))).isFalse();
        verify(monitor).warning(contains("Invalid topic"));
    }

    @Test
    void shouldReturnFalseWhenTopicIsBlank() {
        KafkaTelemetryRecordPublisher publisherBlankTopic =
                new KafkaTelemetryRecordPublisher(producer, "  ", typeManager, monitor);

        assertThat(publisherBlankTopic.sendRecord(buildRecord(CONTRACT_ID))).isFalse();
        verify(monitor).warning(contains("Invalid topic"));
    }

    @Test
    void shouldReturnFalseWhenProducerIsNull() {
        KafkaTelemetryRecordPublisher publisherNoProducer =
                new KafkaTelemetryRecordPublisher(null, TOPIC, typeManager, monitor);

        Boolean result = publisherNoProducer.sendRecord(buildRecord(CONTRACT_ID));

        assertThat(result).isFalse();
        verify(monitor).warning(contains("Producer not available"));
    }

    @Test
    void shouldReturnFalseWhenRecordIsNull() {
        Boolean result = publisher.sendRecord(null);

        assertThat(result).isFalse();
        verifyNoInteractions(producer);
    }

    @Test
    void shouldNotSendWhenClosed() {
        publisher.close();

        Boolean result = publisher.sendRecord(buildRecord(CONTRACT_ID));

        assertThat(result).isFalse();
        verify(monitor).warning(contains("closed"));
        verifyNoInteractions(producer);
        verifyNoInteractions(typeManager);
    }

    @Test
    void shouldNotCloseProducerOnClose() {
        publisher.close();

        verify(monitor).debug(contains("closed"));
        verify(producer, never()).close();
    }

    private void mockSuccessfulSend(TelemetryRecord record) throws Exception {
        when(typeManager.writeValueAsString(record)).thenReturn(SERIALIZED_RECORD);
        Future<RecordMetadata> future = mock(Future.class);
        when(future.get()).thenReturn(mock(RecordMetadata.class));
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
    }

    private TelemetryRecord buildRecord(String contractId) {
        TelemetryRecord.Builder builder = TelemetryRecord.Builder.newInstance().type("test-type");
        if (contractId != null) {
            builder.property("contractId", contractId);
        }
        return builder.build();
    }

    private List<ProducerRecord<String, String>> captureSentRecords() {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.captor();
        verify(producer).send(captor.capture());
        return captor.getAllValues();
    }
}
