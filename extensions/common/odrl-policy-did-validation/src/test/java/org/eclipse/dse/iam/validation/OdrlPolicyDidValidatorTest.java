
package org.eclipse.dse.iam.validation;

import jakarta.json.Json;
import jakarta.json.JsonString;
import org.eclipse.edc.validator.jsonobject.JsonLdPath;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OdrlPolicyDidValidatorTest {

    private final OdrlPolicyDidValidator validator = new OdrlPolicyDidValidator(JsonLdPath.path("odrl:assigner", "@id"));

    @Test
    void shouldSucceed_whenDidFormatIsValid() {
        JsonString didString = Json.createValue("did:web:provider.example.com");

        var result = validator.validate(didString);

        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void shouldSucceed_whenDidIsWeb() {
        JsonString didString = Json.createValue("did:web:provider.example.com:path:to:resource");

        var result = validator.validate(didString);

        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void shouldSucceed_whenDidIsKey() {
        JsonString didString = Json.createValue("did:key:example123");

        var result = validator.validate(didString);

        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void shouldFail_whenDidFormatIsInvalid() {
        JsonString didString = Json.createValue("anis");

        var result = validator.validate(didString);

        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureMessages()).anyMatch(m -> m.contains("Invalid DID format"));
    }

    @Test
    void shouldFail_whenDidMissingMethod() {
        JsonString didString = Json.createValue("did:");

        var result = validator.validate(didString);

        assertThat(result.failed()).isTrue();
    }

    @Test
    void shouldSucceed_whenDidIsNull() {
        var result = validator.validate(null);

        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void shouldSucceed_whenDidIsEmpty() {
        JsonString didString = Json.createValue("");

        var result = validator.validate(didString);

        assertThat(result.succeeded()).isTrue();
    }
}
