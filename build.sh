#!/bin/sh
set -euxo

#echo "${REGISTRY_PWD}" | docker login -u "${REGISTRY_USER}" --password-stdin "${REGISTRY_HOST}"
echo $DOCKER_AUTH_CONFIG_DEV > ./gradle/config.json
./gradlew --gradle-user-home cache/ clean shadowJar dockerize dockerPush -PimageTag=0.1.0 \
          -PregistryHost=${REGISTRY_HOST} \
          -PregistryNs=eona-x/dse
