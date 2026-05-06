package org.eclipse.dse.edc.telemetry;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecordPublisherFactory;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.types.TypeManager;

import java.time.Duration;
import java.util.Properties;

import static java.time.Clock.systemUTC;

/**
 * EDC extension that provides a Kafka-based {@link TelemetryRecordPublisherFactory}.
 *
 * <p>The token is refreshed on every factory call by {@code TelemetryAgent}, which
 * guarantees the {@link DynamicTokenCallbackHandler} always holds a valid token without
 * requiring a proactive refresh scheduler.
 */
@Extension(value = KafkaTelemetryRecordPublisherExtension.NAME)
public class KafkaTelemetryRecordPublisherExtension implements ServiceExtension {

    public static final String NAME = "Kafka Telemetry Publisher Extension";
    private static final String LOGIN_MODULE = "org.apache.kafka.common.security.plain.PlainLoginModule";
    private static final String ACKS_ALL = "all";
    private static final int MAX_RETRIES = 3;

    @Setting(key = "dse.telemetry-service.kafka.bootstrap.servers")
    private String bootstrapServers;

    @Setting(required = false, key = "dse.telemetry-service.kafka.topic", defaultValue = "telemetry")
    private String topic;

    @Setting(required = false, key = "dse.telemetry-service.kafka.security.protocol", defaultValue = "SASL_PLAINTEXT")
    private String securityProtocol;

    @Setting(required = false, key = "dse.telemetry-service.kafka.ssl.truststore.location")
    private String sslTruststoreLocation;

    @Setting(required = false, key = "dse.telemetry-service.kafka.ssl.truststore.password")
    private String sslTruststorePassword;

    @Setting(required = false, key = "dse.telemetry-service.kafka.ssl.truststore.type", defaultValue = "JKS")
    private String sslTruststoreType;

    @Setting(required = false, key = "dse.telemetry-service.kafka.sasl.mechanism", defaultValue = "PLAIN")
    private String saslMechanism;

    @Inject
    private TypeManager typeManager;

    @Inject
    private Monitor monitor;

    private volatile KafkaProducer<String, String> producer;
    private DynamicTokenCallbackHandler callbackHandler;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start() {
        callbackHandler = DynamicTokenCallbackHandler.createManaged();
        monitor.info("Kafka telemetry publisher extension started with instanceId: " + callbackHandler.getInstanceId());
    }

    @Provider
    public TelemetryRecordPublisherFactory telemetryRecordPublisherFactory(ServiceExtensionContext context) {
        return token -> {
            if (token != null) {
                try {
                    OauthBearerTokenImpl bearerToken = OauthBearerTokenImpl.from(token.getToken(), systemUTC());
                    callbackHandler.updateToken(bearerToken);
                } catch (IllegalArgumentException e) {
                    monitor.severe("Failed to parse OIDC token, Kafka authentication will fail: " + e.getMessage());
                }
            }

            if (producer == null) {
                synchronized (this) {
                    if (producer == null) {
                        producer = new KafkaProducer<>(buildProducerProperties());
                        monitor.info("Kafka telemetry producer connected to: " + bootstrapServers);
                    }
                }
            }

            return new KafkaTelemetryRecordPublisher(producer, topic, typeManager, monitor);
        };
    }

    @Override
    public void shutdown() {
        if (producer != null) {
            producer.close(Duration.ofSeconds(30));
            monitor.info("Kafka telemetry producer shut down gracefully");
        }
        if (callbackHandler != null) {
            callbackHandler.close();
        }
    }

    private Properties buildProducerProperties() {
        Properties properties = new Properties();
        setBasicProducerProperties(properties);
        setSecurityProperties(properties);
        return properties;
    }

    private void setBasicProducerProperties(Properties properties) {
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, ACKS_ALL);
        properties.put(ProducerConfig.RETRIES_CONFIG, MAX_RETRIES);
    }

    private void setSecurityProperties(Properties properties) {
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        properties.put(SaslConfigs.SASL_MECHANISM, saslMechanism);
        setSaslCallbackConfig(properties);
        if (SecurityProtocol.SASL_SSL.name().equalsIgnoreCase(securityProtocol)) {
            setSslConfig(properties);
        }
    }

    private void setSaslCallbackConfig(Properties properties) {
        String jaasConfig = String.format(
                "%s required %s=\"%s\";",
                LOGIN_MODULE,
                DynamicTokenCallbackHandler.INSTANCE_ID_KEY,
                callbackHandler.getInstanceId()
        );
        properties.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig);
        properties.put(SaslConfigs.SASL_LOGIN_CALLBACK_HANDLER_CLASS, DynamicTokenCallbackHandler.class.getName());
        properties.put(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS, DynamicTokenCallbackHandler.class.getName());
    }

    private void setSslConfig(Properties properties) {
        if (sslTruststoreLocation != null) {
            properties.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, sslTruststoreLocation);
        }
        if (sslTruststorePassword != null) {
            properties.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, sslTruststorePassword);
        }
        properties.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, sslTruststoreType);
    }
}
