ARG REGISTRY_URL="docker.io"
FROM ${REGISTRY_URL}/eclipse-temurin:21.0.9_10-jre-alpine-3.23

# Update Alpine packages to remove vulnerabilities
RUN apk update && apk --no-cache upgrade --available

WORKDIR /app

# Create non-root user
RUN addgroup -g 1000 appuser && adduser -u 1000 -G appuser -s /bin/sh -D appuser

COPY ./build/libs/kafka-proxy-k8s-manager.jar /app/

RUN mkdir -p /shared /tmp && chown -R appuser:appuser /app /shared /tmp

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

USER appuser

CMD ["sh", "-c", "java $JAVA_OPTS -jar kafka-proxy-k8s-manager.jar"]