#!/bin/sh
set -euxo

#echo "${REGISTRY_PWD}" | docker login -u "${REGISTRY_USER}" --password-stdin "${REGISTRY_HOST}"
echo $DOCKER_AUTH_CONFIG_DEV > ./gradle/config.json
./gradlew --gradle-user-home cache/ clean shadowJar dockerize dockerPush  \
          -PimageTag=0.3.1 \
          -PregistryHost=${REGISTRY_HOST} \
          -PregistryNs=eona-x/dse \
          -Pdocker.registry=${REGISTRY_HOST}/eona-x/dse \
          -Pdocker.image.tag=0.3.1
