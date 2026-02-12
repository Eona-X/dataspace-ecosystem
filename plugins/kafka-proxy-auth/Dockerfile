# Multi-architecture support: kafka-proxy supports linux/amd64 and linux/arm64
# The base image will be selected based on the target platform
FROM grepplabs/kafka-proxy:0.4.3-all
USER root

COPY bin/entra-token-provider /usr/local/bin/
COPY bin/entra-token-verifier /usr/local/bin/
COPY bin/entra-token-info /usr/local/bin/


RUN chmod 755 /usr/local/bin/entra-token-provider \
    /usr/local/bin/entra-token-verifier \
    /usr/local/bin/entra-token-info

RUN chgrp -R 0 /opt/kafka-proxy && \
    chmod -R g=u /opt/kafka-proxy

# Switch to non-root user
USER 1000