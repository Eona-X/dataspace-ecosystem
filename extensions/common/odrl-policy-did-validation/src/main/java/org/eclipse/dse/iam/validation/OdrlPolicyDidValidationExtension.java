package org.eclipse.dse.iam.validation;

import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.validator.jsonobject.JsonObjectValidator;
import org.eclipse.edc.validator.jsonobject.validators.MandatoryIdNotBlank;
import org.eclipse.edc.validator.jsonobject.validators.MandatoryObject;
import org.eclipse.edc.validator.jsonobject.validators.MandatoryValue;
import org.eclipse.edc.validator.jsonobject.validators.TypeIs;
import org.eclipse.edc.validator.spi.JsonObjectValidatorRegistry;

import static org.eclipse.edc.connector.controlplane.contract.spi.types.negotiation.ContractRequest.CONTRACT_REQUEST_COUNTER_PARTY_ADDRESS;
import static org.eclipse.edc.connector.controlplane.contract.spi.types.negotiation.ContractRequest.CONTRACT_REQUEST_TYPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.types.negotiation.ContractRequest.POLICY;
import static org.eclipse.edc.connector.controlplane.contract.spi.types.negotiation.ContractRequest.PROTOCOL;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_ASSIGNER_ATTRIBUTE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_POLICY_TYPE_OFFER;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_TARGET_ATTRIBUTE;


@Extension(value = "ODRL Policy DID Validator")
public class OdrlPolicyDidValidationExtension implements ServiceExtension {

    @Inject
    private JsonObjectValidatorRegistry validatorRegistry;

    @Inject
    private Monitor monitor;

    @Override
    public void initialize(ServiceExtensionContext context) {
        // Register enhanced ContractRequest validator with DID validation
        validatorRegistry.register(CONTRACT_REQUEST_TYPE, createContractRequestValidator());
        
        monitor.info("ODRL Policy DID Validator registered with DID format validation for assigner field");
    }


    private JsonObjectValidator createContractRequestValidator() {
        return JsonObjectValidator.newValidator()
                .verify(CONTRACT_REQUEST_COUNTER_PARTY_ADDRESS, MandatoryValue::new)
                .verify(PROTOCOL, MandatoryValue::new)
                .verify(POLICY, MandatoryObject::new)
                .verifyObject(POLICY, this::offerValidatorWithDidCheck)
                .build();
    }


    private JsonObjectValidator.Builder offerValidatorWithDidCheck(JsonObjectValidator.Builder builder) {
        return builder
                .verifyId(MandatoryIdNotBlank::new)
                .verify(path -> new TypeIs(path, ODRL_POLICY_TYPE_OFFER))
                .verify(ODRL_ASSIGNER_ATTRIBUTE, MandatoryObject::new)
                .verifyObject(ODRL_ASSIGNER_ATTRIBUTE, b -> b
                        .verifyId(MandatoryIdNotBlank::new)
                        .verifyId(OdrlPolicyDidValidator::new))  // Add DID format validation
                .verify(ODRL_TARGET_ATTRIBUTE, MandatoryObject::new)
                .verifyObject(ODRL_TARGET_ATTRIBUTE, b -> b.verifyId(MandatoryIdNotBlank::new));
    }
}
