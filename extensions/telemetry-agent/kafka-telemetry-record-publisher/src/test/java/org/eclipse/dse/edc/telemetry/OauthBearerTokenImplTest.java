package org.eclipse.dse.edc.telemetry;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OauthBearerTokenImplTest {

    private static final long FIXED_EPOCH_SECONDS = 1_700_000_000L;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochSecond(FIXED_EPOCH_SECONDS), ZoneId.of("UTC"));

    private static final long EXPIRATION_SECONDS = 1_700_003_600L;   // +1h
    private static final long ISSUED_AT_SECONDS  = 1_699_999_000L;

    @Test
    void shouldExtractPrincipalNameWhenSubClaimIsPresent() {
        String token = buildToken("{\"sub\":\"alice\",\"exp\":" + EXPIRATION_SECONDS + "}");
        OauthBearerTokenImpl result = OauthBearerTokenImpl.from(token, FIXED_CLOCK);
        assertThat(result.principalName()).isEqualTo("alice");
    }

    @Test
    void shouldExtractLifetimeMsWhenExpClaimIsPresent() {
        String token = buildToken("{\"sub\":\"alice\",\"exp\":" + EXPIRATION_SECONDS + "}");
        OauthBearerTokenImpl result = OauthBearerTokenImpl.from(token, FIXED_CLOCK);
        assertThat(result.lifetimeMs()).isEqualTo(EXPIRATION_SECONDS * 1000L);
    }

    @Test
    void shouldExtractStartTimeMsWhenIatClaimIsPresent() {
        String token = buildToken(
                "{\"sub\":\"alice\",\"exp\":" + EXPIRATION_SECONDS +
                        ",\"iat\":" + ISSUED_AT_SECONDS + "}");
        OauthBearerTokenImpl result = OauthBearerTokenImpl.from(token, FIXED_CLOCK);
        assertThat(result.startTimeMs()).isEqualTo(ISSUED_AT_SECONDS * 1000L);
    }

    @Test
    void shouldUseClockMillisWhenIatClaimIsAbsent() {
        String token = buildToken("{\"sub\":\"alice\",\"exp\":" + EXPIRATION_SECONDS + "}");
        OauthBearerTokenImpl result = OauthBearerTokenImpl.from(token, FIXED_CLOCK);
        assertThat(result.startTimeMs()).isEqualTo(FIXED_EPOCH_SECONDS * 1000L);
    }

    @Test
    void shouldReturnOriginalTokenValueWhenParsing() {
        String token = buildToken("{\"sub\":\"alice\",\"exp\":" + EXPIRATION_SECONDS + "}");
        OauthBearerTokenImpl result = OauthBearerTokenImpl.from(token, FIXED_CLOCK);
        assertThat(result.value()).isEqualTo(token);
    }

    @Test
    void shouldParseScopeWhenScopeIsJsonArray() {
        String token = buildToken(
                "{\"sub\":\"alice\",\"exp\":" + EXPIRATION_SECONDS +
                        ",\"scope\":[\"read\",\"write\"]}");
        OauthBearerTokenImpl result = OauthBearerTokenImpl.from(token, FIXED_CLOCK);
        assertThat(result.scope()).containsExactlyInAnyOrder("read", "write");
    }

    @Test
    void shouldParseScopeWhenScopeIsSpaceSeparatedString() {
        String token = buildToken(
                "{\"sub\":\"alice\",\"exp\":" + EXPIRATION_SECONDS +
                        ",\"scope\":\"read write admin\"}");
        OauthBearerTokenImpl result = OauthBearerTokenImpl.from(token, FIXED_CLOCK);
        assertThat(result.scope()).containsExactlyInAnyOrder("read", "write", "admin");
    }

    @Test
    void shouldReturnEmptyScopeWhenScopeClaimIsAbsent() {
        String token = buildToken("{\"sub\":\"alice\",\"exp\":" + EXPIRATION_SECONDS + "}");
        OauthBearerTokenImpl result = OauthBearerTokenImpl.from(token, FIXED_CLOCK);
        assertThat(result.scope()).isEmpty();
    }

    @Test
    void shouldReturnEmptyScopeWhenScopeClaimIsNull() {
        String token = buildToken("{\"sub\":\"alice\",\"exp\":" + EXPIRATION_SECONDS + ",\"scope\":null}");
        OauthBearerTokenImpl result = OauthBearerTokenImpl.from(token, FIXED_CLOCK);
        assertThat(result.scope()).isEmpty();
    }

    @Test
    void shouldIgnoreBlankScopeEntriesWhenScopeArrayContainsEmptyStrings() {
        String token = buildToken(
                "{\"sub\":\"alice\",\"exp\":" + EXPIRATION_SECONDS +
                        ",\"scope\":[\"read\",\"\",\"write\"]}");
        OauthBearerTokenImpl result = OauthBearerTokenImpl.from(token, FIXED_CLOCK);
        assertThat(result.scope()).containsExactlyInAnyOrder("read", "write");
    }

    @Test
    void shouldIgnoreBlankScopeEntriesWhenScopeStringContainsExtraSpaces() {
        String token = buildToken(
                "{\"sub\":\"alice\",\"exp\":" + EXPIRATION_SECONDS +
                        ",\"scope\":\" read  write \"}");
        OauthBearerTokenImpl result = OauthBearerTokenImpl.from(token, FIXED_CLOCK);
        assertThat(result.scope()).containsExactlyInAnyOrder("read", "write");
    }

    @Test
    void shouldReturnUnmodifiableScopeWhenAccessingScope() {
        String token = buildToken(
                "{\"sub\":\"alice\",\"exp\":" + EXPIRATION_SECONDS +
                        ",\"scope\":[\"read\"]}");
        OauthBearerTokenImpl result = OauthBearerTokenImpl.from(token, FIXED_CLOCK);
        Set<String> scope = result.scope();
        assertThatThrownBy(() -> scope.add("admin"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenTokenIsNull() {
        assertThatThrownBy(() -> OauthBearerTokenImpl.from(null, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token cannot be null");
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenTokenHasFewerThanThreeParts() {
        String token = "header.payload";
        assertThatThrownBy(() -> OauthBearerTokenImpl.from(token, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 parts");
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenTokenHasMoreThanThreeParts() {
        String token = "a.b.c.d";
        assertThatThrownBy(() -> OauthBearerTokenImpl.from(token, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 parts");
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenPayloadIsNotValidBase64() {
        String token = "header.!!!invalid!!!.signature";
        assertThatThrownBy(() -> OauthBearerTokenImpl.from(token, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64url");
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenPayloadIsNotJson() {
        String notJson = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("this is not json".getBytes());
        String token = "header." + notJson + ".signature";
        assertThatThrownBy(() -> OauthBearerTokenImpl.from(token, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenPayloadIsJsonArrayInsteadOfObject() {
        String jsonArray = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("[1,2,3]".getBytes());
        String token = "header." + jsonArray + ".signature";
        assertThatThrownBy(() -> OauthBearerTokenImpl.from(token, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenSubClaimIsAbsent() {
        String token = buildToken("{\"exp\":" + EXPIRATION_SECONDS + "}");
        assertThatThrownBy(() -> OauthBearerTokenImpl.from(token, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sub")
                .hasMessageContaining("expected a String");
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenSubClaimIsNull() {
        String token = buildToken("{\"sub\":null,\"exp\":" + EXPIRATION_SECONDS + "}");
        assertThatThrownBy(() -> OauthBearerTokenImpl.from(token, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sub")
                .hasMessageContaining("expected a String");
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenSubClaimIsNotAString() {
        String token = buildToken("{\"sub\":123,\"exp\":" + EXPIRATION_SECONDS + "}");
        assertThatThrownBy(() -> OauthBearerTokenImpl.from(token, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sub")
                .hasMessageContaining("expected a String");
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenExpClaimIsAbsent() {
        String token = buildToken("{\"sub\":\"alice\"}");
        assertThatThrownBy(() -> OauthBearerTokenImpl.from(token, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exp")
                .hasMessageContaining("expected a Number");
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenExpClaimIsNull() {
        String token = buildToken("{\"sub\":\"alice\",\"exp\":null}");
        assertThatThrownBy(() -> OauthBearerTokenImpl.from(token, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exp")
                .hasMessageContaining("expected a Number");
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenExpClaimIsNotANumber() {
        String token = buildToken("{\"sub\":\"alice\",\"exp\":\"not-a-number\"}");
        assertThatThrownBy(() -> OauthBearerTokenImpl.from(token, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exp")
                .hasMessageContaining("expected a Number");
    }

    private String buildToken(String jsonPayload) {
        String encodedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(jsonPayload.getBytes());
        return "eyJhbGciOiJSUzI1NiJ9." + encodedPayload + ".fakesignature";
    }
}
