# Standalone Geyser image (Bedrock -> Java translation) sitting in front of the Velocity proxies.
# Scales independently of the proxies; terminates Bedrock UDP and speaks the Java protocol upstream.
#
# Build from the repo root:  docker build -f deploy/images/geyser.Dockerfile -t <registry>/cryon-geyser .

FROM eclipse-temurin:21-jre
ENV JAVA_OPTS="-Xms512M -Xmx512M"
WORKDIR /geyser

# Provide the Geyser Standalone jar at deploy/geyser.jar (https://geysermc.org/download).
COPY deploy/geyser.jar ./geyser.jar
COPY deploy/images/entrypoint-geyser.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 19132/udp
ENTRYPOINT ["/entrypoint.sh"]
