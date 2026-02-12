package org.eclipse.edc.dse.controlplane.asset.decorator;

import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.dse.controlplane.asset.decorator.AssetCreatedAtDecorator.PROPERTY_CREATED_AT;

class AssetCreatedAtDecoratorTest {

    private final AssetCreatedAtDecorator decorator = new AssetCreatedAtDecorator();

    @Test
    void decorate_shouldPreserveExistingCreatedAtTimestamp() {
        Map<String, Object> props = Map.of("foo", "bar");
        var createdAt = Instant.now().minusSeconds(100);
        var builder = Asset.Builder.newInstance()
                .dataAddress(DataAddress.Builder.newInstance().type("type").build())
                .properties(props);
        var existing = Asset.Builder.newInstance()
                .createdAt(createdAt.toEpochMilli())
                .build();

        decorator.decorate(existing, builder);

        assertThat(builder.build().getProperties())
                .containsAllEntriesOf(props)
                .containsEntry(PROPERTY_CREATED_AT, createdAt.truncatedTo(ChronoUnit.MILLIS));
    }

}