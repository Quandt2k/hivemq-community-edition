FROM gradle:jdk11 AS builder

# Build HiveMQ
RUN mkdir /tmp/hivemq-source
WORKDIR /tmp/hivemq-source

COPY ./ ./

RUN apt-get update
RUN apt-get install dos2unix
RUN dos2unix gradlew

RUN ./gradlew build -x test

# FROM com2m Alpine Base Image
FROM docker.com2m.de/iot/core/iot-openjdk11-alpine-base:snapshot

# Script parameter for HiveMQ Version, Group ID and User ID.
ARG HIVEMQ_CE_VERSION=2019.2
ARG HIVEMQ_GID=10000
ARG HIVEMQ_UID=10000

# Additional JVM options, may be overwritten by user
ENV JAVA_OPTS "-XX:+UseNUMA"

RUN set -x \
	&& apk update \
	&& apk add --no-cache curl tini

# Build HiveMQ
RUN mkdir /tmp/hivemq-source
WORKDIR /tmp/hivemq-source
COPY ./ ./

RUN ls -la
RUN ./gradlew build -x test
COPY --from=builder /tmp/hivemq-source/build/zip/hivemq-ce-${HIVEMQ_CE_VERSION}-SNAPSHOT.zip -d /opt/
RUN rm -rf /tmp/hivemq-source/
RUN ln -s /opt/hivemq-ce-${HIVEMQ_CE_VERSION}-SNAPSHOT /opt/hivemq

WORKDIR /opt/hivemq

# Configure user and group for HiveMQ
RUN addgroup -g ${HIVEMQ_GID} hivemq \
    && adduser -D -G hivemq -h /opt/hivemq -u ${HIVEMQ_UID} hivemq \
    && chown -R hivemq:hivemq /opt/hivemq-ce-${HIVEMQ_CE_VERSION} \
    && chmod -R 777 /opt \
    && chmod +x /opt/hivemq/bin/run.sh

# Substitute eval for exec and replace OOM flag if necessary (for older releases). This is necessary for proper signal propagation
RUN sed -i -e 's|eval \\"java\\" "$HOME_OPT" "$JAVA_OPTS" -jar "$JAR_PATH"|exec "java" $HOME_OPT $JAVA_OPTS -jar "$JAR_PATH"|' /opt/hivemq/bin/run.sh && \
    sed -i -e "s|-XX:OnOutOfMemoryError='sleep 5; kill -9 %p'|-XX:+CrashOnOutOfMemoryError|" /opt/hivemq/bin/run.sh

# Make config folder available on host
VOLUME /opt/hivemq/conf

# Make broker data persistent throughout stop/start cycles
VOLUME /opt/hivemq/data

# Persist log data
VOLUME /opt/hivemq/log

EXPOSE 1883
EXPOSE 8083

WORKDIR /opt/hivemq
# USER ${HIVEMQ_UID}

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["/opt/hivemq/bin/run.sh"]