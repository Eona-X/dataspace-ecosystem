ARG REGISTRY_URL="docker.io"
FROM ${REGISTRY_URL}/eclipse-temurin:21.0.9_10-jre-alpine-3.23

# Update Alpine packages to remove vulnerabilities
RUN apk update && apk --no-cache upgrade --available

# Optional JVM arguments, such as memory settings
ARG JVM_ARGS=""
ARG JAR
ARG OTEL_JAR

WORKDIR /app

COPY ${JAR} telemetry-service.jar
COPY ${OTEL_JAR} opentelemetry-javaagent.jar

HEALTHCHECK NONE

# default api
ENV WEB_HTTP_PORT="8080"
ENV WEB_HTTP_PATH="/api"

# credential api
ENV WEB_HTTP_CREDENTIAL_PORT="8181"
ENV WEB_HTTP_CREDENTIAL_PATH="/api/credential"

# ARG can not be used in ENTRYPOINT so storing value in an ENV variable
ENV ENV_JVM_ARGS=$JVM_ARGS

# Execute the container as non-root user
USER appuser

CMD ["java", \
    "-javaagent:/app/opentelemetry-javaagent.jar", \
     "-Djava.util.logging.config.file=/app/logging.properties", \
     "-Dotel.javaagent.configuration-file=/app/opentelemetry.properties", \
     "-Dotel.metrics.exporter=prometheus", \
     "-Djava.security.egd=file:/dev/urandom", \
     "-jar", \
     "telemetry-service.jar"]