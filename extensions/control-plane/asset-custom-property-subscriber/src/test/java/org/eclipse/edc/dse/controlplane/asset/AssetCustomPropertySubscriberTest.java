package org.eclipse.edc.dse.controlplane.asset;

import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.asset.spi.event.AssetCreated;
import org.eclipse.edc.connector.controlplane.asset.spi.index.AssetIndex;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.event.EventEnvelope;
import org.eclipse.edc.spi.result.StoreResult;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.transaction.spi.NoopTransactionContext;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetCustomPropertySubscriberTest {

    private static final String TEST_PROP = UUID.randomUUID().toString();

    private final AssetIndex assetIndex = mock();
    private final TransactionContext transactionContext = new NoopTransactionContext();
    private final AssetCustomPropertySubscriber subscriber = new AssetCustomPropertySubscriber(assetIndex, transactionContext);

    @Test
    void success() {
        var assetId = UUID.randomUUID().toString();
        Map<String, Object> props = Map.of("foo", "bar");
        subscriber.register((existing, builder) -> builder.property(TEST_PROP, "test"));
        var asset = Asset.Builder.newInstance()
                .properties(props)
                .dataAddress(DataAddress.Builder.newInstance().type("type").build())
                .build();

        var captor = ArgumentCaptor.forClass(Asset.class);
        when(assetIndex.findById(assetId)).thenReturn(asset);
        when(assetIndex.updateAsset(captor.capture())).thenReturn(StoreResult.success());
        var envelope = EventEnvelope.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .at(Instant.now().getEpochSecond())
                .payload(AssetCreated.Builder.newInstance().assetId(assetId).build())
                .build();

        subscriber.on(envelope);

        var updated = captor.getValue();
        assertThat(updated).isNotNull();
        assertThat(updated.getProperties()).containsAllEntriesOf(props).containsEntry(TEST_PROP, "test");
    }

    @Test
    void assetNotFound_shouldThrow() {
        var assetId = UUID.randomUUID().toString();
        subscriber.register(mock());
        when(assetIndex.findById(any())).thenReturn(null);
        var envelope = EventEnvelope.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .at(Instant.now().getEpochSecond())
                .payload(AssetCreated.Builder.newInstance().assetId(assetId).build())
                .build();

        assertThatExceptionOfType(EdcException.class).isThrownBy(() -> subscriber.on(envelope))
                .withMessage("Failed to find Asset with id %s SHOULD NOT HAPPEN".formatted(assetId));

        verify(assetIndex, never()).updateAsset(any());
    }

    @Test
    void updateFails_shouldThrow() {
        subscriber.register(mock());
        var assetId = UUID.randomUUID().toString();
        Map<String, Object> props = Map.of("foo", "bar");
        var asset = Asset.Builder.newInstance()
                .properties(props)
                .dataAddress(DataAddress.Builder.newInstance().type("type").build())
                .build();
        var captor = ArgumentCaptor.forClass(Asset.class);
        when(assetIndex.findById(assetId)).thenReturn(asset);
        when(assetIndex.updateAsset(captor.capture())).thenReturn(StoreResult.notFound("not found"));
        var envelope = EventEnvelope.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .at(Instant.now().getEpochSecond())
                .payload(AssetCreated.Builder.newInstance().assetId(assetId).build())
                .build();

        assertThatExceptionOfType(EdcException.class).isThrownBy(() -> subscriber.on(envelope))
                .withMessageContaining("not found");
    }

}