package org.eclipse.edc.util;

import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToActionTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToConstraintTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToDutyTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToOperatorTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToPermissionTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToProhibitionTransformer;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;

import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.VOCAB;
import static org.eclipse.edc.jsonld.spi.Namespaces.DCAT_PREFIX;
import static org.eclipse.edc.jsonld.spi.Namespaces.DCAT_SCHEMA;
import static org.eclipse.edc.jsonld.spi.Namespaces.DCT_PREFIX;
import static org.eclipse.edc.jsonld.spi.Namespaces.DCT_SCHEMA;
import static org.eclipse.edc.jsonld.spi.Namespaces.DSPACE_PREFIX;
import static org.eclipse.edc.jsonld.spi.Namespaces.DSPACE_SCHEMA;
import static org.eclipse.edc.policy.model.OdrlNamespace.ODRL_PREFIX;
import static org.eclipse.edc.policy.model.OdrlNamespace.ODRL_SCHEMA;
import static org.eclipse.edc.spi.constants.CoreConstants.EDC_NAMESPACE;
import static org.eclipse.edc.spi.constants.CoreConstants.EDC_PREFIX;

public interface VcCatalogInitializer {

    static void registerNamespaces(JsonLd jsonLd, String scope) {
        jsonLd.registerNamespace(VOCAB, EDC_NAMESPACE, scope);
        jsonLd.registerNamespace(EDC_PREFIX, EDC_NAMESPACE, scope);
        jsonLd.registerNamespace(ODRL_PREFIX, ODRL_SCHEMA, scope);
        jsonLd.registerNamespace(DCAT_PREFIX, DCAT_SCHEMA, scope);
        jsonLd.registerNamespace(DCT_PREFIX, DCT_SCHEMA, scope);
        jsonLd.registerNamespace(DSPACE_PREFIX, DSPACE_SCHEMA, scope);
    }

    static void registerTransformers(TypeTransformerRegistry transformerRegistry) {
        transformerRegistry.register(new JsonObjectToPermissionTransformer());
        transformerRegistry.register(new JsonObjectToProhibitionTransformer());
        transformerRegistry.register(new JsonObjectToDutyTransformer());
        transformerRegistry.register(new JsonObjectToActionTransformer());
        transformerRegistry.register(new JsonObjectToConstraintTransformer());
        transformerRegistry.register(new JsonObjectToOperatorTransformer());
    }

}
