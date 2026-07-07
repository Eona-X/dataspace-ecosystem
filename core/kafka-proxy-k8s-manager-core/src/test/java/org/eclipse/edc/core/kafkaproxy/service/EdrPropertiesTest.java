/*
 *  Copyright (c) 2024 Eclipse Dataspace Connector Project
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Eclipse Dataspace Connector Project - initial implementation
 */

package org.eclipse.dse.core.kafkaproxy.service;

import org.eclipse.dse.core.kafkaproxy.model.EdrProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EdrPropertiesTest {
    
    @Test
    void shouldDetectTlsEnabled_whenSecurityProtocolIsSsl() {
        var properties = new EdrProperties(
                "localhost:9092",
                "user",
                "pass",
                "SSL",
                "PLAIN",
                null,
                null,
                null,
                null,  // oauth2ClientId
                null,  // oauth2ClientSecret
                null,  // oauth2TenantId
                null   // oauth2Scope
        );
        
        assertThat(properties.isTlsEnabled()).isTrue();
    }
    
    @Test
    void shouldDetectTlsEnabled_whenSecurityProtocolIsSaslSsl() {
        var properties = new EdrProperties(
                "localhost:9092",
                "user",
                "pass",
                "SASL_SSL",
                "PLAIN",
                null,
                null,
                null,
                null,  // oauth2ClientId
                null,  // oauth2ClientSecret
                null,  // oauth2TenantId
                null   // oauth2Scope
        );
        
        assertThat(properties.isTlsEnabled()).isTrue();
    }
    
    @Test
    void shouldStoreTlsCaCrt() {
        var properties = new EdrProperties(
                "localhost:9092",
                "user",
                "pass",
                "SASL_SSL",
                "PLAIN",
                null,
                null,
                "BEGIN CERTIFICATE...",
                null,
                null,
                null,
                null
        );

        assertThat(properties.getTlsCaCrt()).isEqualTo("BEGIN CERTIFICATE...");
    }

    @Test
    void testToStringDoesNotContainFullCrt() {
        var properties = new EdrProperties(
                "localhost:9092",
                "user",
                "pass",
                "SASL_SSL",
                "PLAIN",
                null,
                null,
                "-----BEGIN CERTIFICATE-----\n" +
                        "-----END CERTIFICATE-----",
                null,
                null,
                null,
                null
        );

        String toString = properties.toString();
        assertThat(toString).contains("hasTlsCaCrt=true");
        assertThat(toString).doesNotContain("BEGIN CERTIFICATE...");
    }

    @Test
    void shouldStoreRealTlsCaCrt() {
        String realCrt = "-----BEGIN CERTIFICATE-----\n" +
                "MIIEUDCCArigAwIBAgIUElS+4mCEgw3WSnNs5ymJ2NEiX1gwDQYJKoZIhvcNAQEM\n" +
                "BQAwQDE+MDwGA1UEAww1ZWYzY2EwYjktZGQzMi00ZTRkLWFjMmItMDIwZWMyNzc3\n" +
                "ZDAyIEdFTiAxIFByb2plY3QgQ0EwHhcNMjUxMTA1MTQwMTUzWhcNMzUxMTAzMTQw\n" +
                "MTUzWjBAMT4wPAYDVQQDDDVlZjNjYTBiOS1kZDMyLTRlNGQtYWMyYi0wMjBlYzI3\n" +
                "NzdkMDIgR0VOIDEgR0VOIDEgUHJvamVjdCBDQTCCAaIwDQYJKoZIhvcNAQEBBQADggGPADCC\n" +
                "AYoCggGBAKNIyXsK1k0o3wq2dqEsJyoFTCehDfnqdG7GIn7kzSxpvan6D0jFEOC8\n" +
                "E6NT0j2gKEAkEfxsWVRPQYUKCwikM6W+Nd/cQ3R/pO1sCrWfyXpNrtUmw9v0szFY\n" +
                "bvX/xtJVaVDUfeOsQLQaDPkO2PkeUPeN5HgmWUlSq7ggLG70gC8x+GyrZJUxpoYT\n" +
                "1SJ2N0M243Ngijwyz39g5m5XQ3/5xir0yH67axs5lJJdSNqrQ+4nnEjPSZtf0fdJ\n" +
                "YMreSnn3PytS97Wn0RfRebmfxcXcCReV60rH9BAnlNuE2ktVrcEz1xr7hoU+qhP5\n" +
                "51vIbUdGrHqnGwXG+bJ5TbA9IETgrAkJnlKEO8a52wfzGdPxhv9OGbPp5br8wBW1\n" +
                "R7fQ/qT3s9FEqv65lS1xk8h3OLa0YUtwcuWWiyC3qJGiDLPDoPIo603DCXsMOz/i\n" +
                "PbqIq5HsaY+xUWFUGlvGiu9kc+urlb+gONTHMdv4+WCLF3Z5vu1+ApY2MhxdbivG\n" +
                "wI2/SUuNXwIDAQABo0IwQDAdBgNVHQ4EFgQUEVu/Mwv7t4AYtbT2Yu4kxFwdDv0w\n" +
                "EgYDVR0TAQH/BAgwBgEB/wIBADALBgNVHQ8EBAMCAQYwDQYJKoZIhvcNAQEMBQAD\n" +
                "ggGBAA86ODAZm44lSNk0NUtBWFJhBWYfY3g+4rhKJ1MwRHeEtEvLF+GvSlYF4DhD\n" +
                "3kfEO3GZcEq0DCLX1Asn+asVlUyMTnRHtrEGLalMFFcksyCUhT6n3m1/wzjMKBtS\n" +
                "gwRExwrQPMBPBw1Vn3rFG9rs0CqQ/+VtvdWeQz9rHk0bfffTrTSsPVXuJ7Kyh3Mg\n" +
                "EXUCMm71JNQhZG3mWzXudublCoccVfFmQqewJWJZNMs34YQp9eB5jM57JpWqXgOY\n" +
                "5NqwHCbtfbJXawV65MEIHFcmYpis7J0VqO4XBEit9u7GTni5iPQSHKccMj9oWltR\n" +
                "LXtTRZt2OEf5CNy8OEkSsAPFXGrnBzb0XcA9chO1r09DakhbGIMfcldmWvqxtmji\n" +
                "NqkwQva8lYrj1bzRA4IK8ugn8WQ95KcBLhjbKGcf1QE5TwUl0h+sTYRYEmaypVin\n" +
                "0Rhu0ReWv3I/CkcP4waFCSl4DfOlU7ff4zE6IITnkh3utHnJPe7Ka23OsRQHL22D\n" +
                "ohl1pQ==\n" +
                "-----END CERTIFICATE-----";

        var properties = new EdrProperties(
                "localhost:9092",
                "user",
                "pass",
                "SASL_SSL",
                "PLAIN",
                null,
                null,
                realCrt,
                null,
                null,
                null,
                null
        );

        assertThat(properties.getTlsCaCrt()).isEqualTo(realCrt);
        assertThat(properties.toString()).doesNotContain("MIIEUDCCArigAwIBAgIUElS+4mCEgw3WSnNs5ymJ2NEiX1gwDQYJKoZIhvcNAQEM");
    }

    @Test
    void shouldDetectTlsDisabled_whenSecurityProtocolIsPlaintext() {
        var properties = new EdrProperties(
                "localhost:9092",
                "user",
                "pass",
                "PLAINTEXT",
                "PLAIN",
                null,
                null,
                null,
                null,  // oauth2ClientId
                null,  // oauth2ClientSecret
                null,  // oauth2TenantId
                null   // oauth2Scope
        );
        
        assertThat(properties.isTlsEnabled()).isFalse();
    }
}