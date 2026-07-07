package org.eclipse.edc.dse.controlplane.asset.decorator;

import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;

import java.time.Instant;

import static org.eclipse.edc.spi.constants.CoreConstants.EDC_NAMESPACE;

public class AssetCreatedAtDecorator implements AssetDecorator {

    public static final String PROPERTY_CREATED_AT = EDC_NAMESPACE + "createdAt";

    @Override
    public void decorate(Asset existing, Asset.Builder builder) {
        builder.property(PROPERTY_CREATED_AT, Instant.ofEpochMilli(existing.getCreatedAt()));
    }
}
