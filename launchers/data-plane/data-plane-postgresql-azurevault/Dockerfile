ARG REGISTRY_URL="docker.io"
# Please make sure to keep this image consistent with the one used
# for the cert-installer init container
FROM ${REGISTRY_URL}/eclipse-temurin:21.0.9_10-jre-alpine-3.23

# Update Alpine packages to remove vulnerabilities
RUN apk update && apk --no-cache upgrade --available

# Optional JVM arguments, such as memory settings
ARG JVM_ARGS=""
ARG JAR
ARG OTEL_JAR

WORKDIR /app

# Create a group and user (non-root) with fixed UID/GID
RUN addgroup -g 1000 appuser && adduser -u 1000 -G appuser -s /bin/sh -D appuser

COPY ${JAR} dataplane.jar
COPY ${OTEL_JAR} opentelemetry-javaagent.jar

# Change ownership of /app to the non-root user
RUN chown -R appuser:appuser /app

HEALTHCHECK NONE

# default api
ENV WEB_HTTP_PORT="8080"
ENV WEB_HTTP_PATH="/api"

# data plane public api
ENV WEB_HTTP_PUBLIC_PORT="8181"
ENV WEB_HTTP_PUBLIC_PATH="/api/public"

# data plane data api
ENV WEB_HTTP_DATA_PORT="8282"
ENV WEB_HTTP_DATA_PATH="/api/data"

# data plane control api
ENV WEB_HTTP_CONTROL_PORT="8383"
ENV WEB_HTTP_CONTROL_PATH="/api/control"

# ARG can not be used in ENTRYPOINT so storing value in an ENV variable
ENV ENV_JVM_ARGS=$JVM_ARGS

# Execute the container as non-root user
USER appuser

CMD ["java", \
    "-javaagent:/app/opentelemetry-javaagent.jar", \
     "-Djava.util.logging.config.file=/app/logging.properties", \
     "-Dotel.javaagent.configuration-file=/app/opentelemetry.properties", \
     "-Djava.security.egd=file:/dev/urandom", \
     "-jar", \
     "dataplane.jar"]