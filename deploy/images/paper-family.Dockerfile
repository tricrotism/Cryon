# Paper game-server image, one per family (build with --build-arg FAMILY=<name>). Immutable: the
# family's feature jars are baked in and production mode keeps the hot-reload watchers off. Per-pod
# identity (family/instance/address) comes from CRYON_* env injected by the Fleet; deployment config
# (redis/db) is mounted from a ConfigMap and rendered by the entrypoint.
#
# Build from the repo root:  docker build -f deploy/images/paper-family.Dockerfile --build-arg FAMILY=hub -t <registry>/cryon-hub .

# --- stage 1: build the shaded Cryon core ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :paper:shadowJar --no-daemon

# --- stage 2: runtime family image ---
FROM eclipse-temurin:25-jre AS runtime
ARG FAMILY=hub
ENV CRYON_SERVER_FAMILY=${FAMILY} \
    JAVA_OPTS="-Xms3G -Xmx3G -XX:+UseG1GC"
WORKDIR /server
RUN apt-get update && apt-get install -y --no-install-recommends gettext-base && rm -rf /var/lib/apt/lists/*

# Provide your Paper 26.2 server jar at deploy/paper.jar (your dev-bundle build).
COPY deploy/paper.jar ./paper.jar
# The Cryon core loader (shaded).
COPY --from=build /src/paper/build/libs/*-all.jar plugins/Cryon.jar
# Baked per-family feature jars: shared contracts in api/, features in modules/.
COPY deploy/families/${FAMILY}/api/ plugins/Cryon/api/
COPY deploy/families/${FAMILY}/modules/ plugins/Cryon/modules/
COPY deploy/images/entrypoint-paper.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && mkdir -p plugins/Cryon config

EXPOSE 25565
ENTRYPOINT ["/entrypoint.sh"]
