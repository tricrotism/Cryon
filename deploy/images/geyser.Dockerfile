# Standalone Geyser image (Bedrock -> Java translation) sitting in front of the Velocity proxies.
# Scales independently of the proxies; terminates Bedrock UDP and speaks the Java protocol upstream.
# Carries the Cryon Geyser extension, so the JRE has to match the one the extension is compiled with
# (JDK 25, this repo's toolchain). Geyser itself requires Java 21 or newer, so 25 is in range.
#
# Build from the repo root:  docker build -f deploy/images/geyser.Dockerfile -t <registry>/cryon-geyser .

# --- stage 1: build the shaded Cryon Geyser extension ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :geyser:shadowJar --no-daemon

# --- stage 2: runtime Geyser image ---
FROM eclipse-temurin:25-jre AS runtime
ENV JAVA_OPTS="-Xms512M -Xmx512M"
WORKDIR /geyser

# Provide the Geyser Standalone jar at deploy/geyser.jar (https://geysermc.org/download).
COPY deploy/geyser.jar ./geyser.jar
# The Cryon Geyser extension (shaded). Geyser loads everything in extensions/ on boot.
COPY --from=build /src/geyser/build/libs/*-all.jar extensions/cryon-geyser.jar
COPY deploy/images/entrypoint-geyser.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && mkdir -p config extensions

EXPOSE 19132/udp
ENTRYPOINT ["/entrypoint.sh"]
