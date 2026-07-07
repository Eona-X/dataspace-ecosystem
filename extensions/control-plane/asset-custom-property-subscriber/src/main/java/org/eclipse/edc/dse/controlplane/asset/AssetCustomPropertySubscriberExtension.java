package org.eclipse.edc.dse.controlplane.asset;


import org.eclipse.edc.connector.controlplane.asset.spi.event.AssetCreated;
import org.eclipse.edc.connector.controlplane.asset.spi.event.AssetUpdated;
import org.eclipse.edc.connector.controlplane.asset.spi.index.AssetIndex;
import org.eclipse.edc.dse.controlplane.asset.decorator.AssetCreatedAtDecorator;
import org.eclipse.edc.dse.controlplane.asset.decorator.AssetUpdatedAtDecorator;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.event.EventRouter;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.transaction.spi.TransactionContext;

import java.time.Clock;

public class AssetCustomPropertySubscriberExtension implements ServiceExtension {

    @Inject
    private EventRouter eventRouter;

    @Inject
    private AssetIndex assetIndex;

    @Inject
    private TransactionContext transactionContext;

    @Inject
    // Used only by AssetUpdatedAtDecorator; AssetCreatedAtDecorator does not require a Clock.
    private Clock clock;

    @Override
    public String name() {
        return "Asset Custom Property Subscriber";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        eventRouter.registerSync(
                AssetCreated.class,
                new AssetCustomPropertySubscriber(assetIndex, transactionContext)
                        .register(new AssetCreatedAtDecorator())
        );
        eventRouter.registerSync(
                AssetUpdated.class,
                new AssetCustomPropertySubscriber(assetIndex, transactionContext)
                        .register(new AssetCreatedAtDecorator())
                        .register(new AssetUpdatedAtDecorator(clock))
        );
    }

}
