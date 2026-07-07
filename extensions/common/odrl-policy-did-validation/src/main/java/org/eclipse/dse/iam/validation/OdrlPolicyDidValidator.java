package org.eclipse.dse.iam.validation;

import jakarta.json.JsonString;
import org.eclipse.edc.validator.jsonobject.JsonLdPath;
import org.eclipse.edc.validator.spi.ValidationResult;
import org.eclipse.edc.validator.spi.Validator;
import org.eclipse.edc.validator.spi.Violation;

import java.util.regex.Pattern;


public class OdrlPolicyDidValidator implements Validator<JsonString> {

    /**
     * Pattern to validate DID format: did:method:method-specific-id
     */
    private static final Pattern DID_PATTERN = Pattern.compile("^did:[a-z0-9]+:[^:]+.*$");
    
    private final JsonLdPath path;

    public OdrlPolicyDidValidator(JsonLdPath path) {
        this.path = path;
    }

    @Override
    public ValidationResult validate(JsonString input) {
        if (input == null) {
            return ValidationResult.success();
        }

        var didValue = input.getString();
        
        if (didValue == null || didValue.trim().isEmpty()) {
            return ValidationResult.success();
        }

        if (!DID_PATTERN.matcher(didValue).matches()) {
            return ValidationResult.failure(Violation.violation(
                    String.format("Invalid DID format: '%s'. Expected format: did:method:method-specific-id (e.g., did:web:example.com:user:alice)", didValue),
                    path.toString()
            ));
        }

        return ValidationResult.success();
    }
}
