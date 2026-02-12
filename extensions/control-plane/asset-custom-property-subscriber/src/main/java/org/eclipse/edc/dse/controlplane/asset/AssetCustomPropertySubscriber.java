package org.eclipse.edc.dse.controlplane.asset;

import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.asset.spi.event.AssetEvent;
import org.eclipse.edc.connector.controlplane.asset.spi.index.AssetIndex;
import org.eclipse.edc.dse.controlplane.asset.decorator.AssetDecorator;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.event.Event;
import org.eclipse.edc.spi.event.EventEnvelope;
import org.eclipse.edc.spi.event.EventSubscriber;
import org.eclipse.edc.transaction.spi.TransactionContext;

import java.util.ArrayList;
import java.util.List;

import static java.util.Optional.ofNullable;

public class AssetCustomPropertySubscriber implements EventSubscriber {

    private final AssetIndex assetIndex;
    private final TransactionContext transactionContext;
    private final List<AssetDecorator> decorators = new ArrayList<>();

    public AssetCustomPropertySubscriber(AssetIndex assetIndex, TransactionContext transactionContext) {
        this.assetIndex = assetIndex;
        this.transactionContext = transactionContext;
    }

    public AssetCustomPropertySubscriber register(AssetDecorator decorator) {
        decorators.add(decorator);
        return this;
    }

    @Override
    public <E extends Event> void on(EventEnvelope<E> eventEnvelope) {
        if (!decorators.isEmpty()) {
            transactionContext.execute(() -> {
                var event = (AssetEvent) eventEnvelope.getPayload();
                var existing = ofNullable(assetIndex.findById(event.getAssetId()))
                        .orElseThrow(() -> new EdcException("Failed to find Asset with id %s SHOULD NOT HAPPEN".formatted(event.getAssetId())));

                var builder = Asset.Builder.newInstance()
                        .id(existing.getId())
                        .createdAt(existing.getCreatedAt())
                        .properties(existing.getProperties())
                        .privateProperties(existing.getPrivateProperties())
                        .dataAddress(existing.getDataAddress());

                decorators.forEach(d -> d.decorate(existing, builder));
                var asset = builder.build();
                assetIndex.updateAsset(asset).orElseThrow(failure -> new EdcException(failure.getFailureDetail()));
            });
        }
    }

}
