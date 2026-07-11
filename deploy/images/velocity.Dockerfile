# Velocity proxy image with the Cryon proxy loader. Stateless and horizontally scalable behind a
# TCP load balancer; every replica reads the same server registry over Redis.
#
# Build from the repo root:  docker build -f deploy/images/velocity.Dockerfile -t <registry>/cryon-velocity .

# --- stage 1: build the shaded Cryon proxy loader ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :velocity:shadowJar --no-daemon

# --- stage 2: runtime proxy image ---
FROM eclipse-temurin:25-jre AS runtime
ENV JAVA_OPTS="-Xms1G -Xmx1G"
WORKDIR /proxy
RUN apt-get update && apt-get install -y --no-install-recommends gettext-base && rm -rf /var/lib/apt/lists/*

# Provide the Velocity proxy jar at deploy/velocity.jar (https://papermc.io/downloads/velocity).
COPY deploy/velocity.jar ./velocity.jar
# The Cryon proxy loader (shaded).
COPY --from=build /src/velocity/build/libs/*-all.jar plugins/cryon.jar
# For Bedrock: drop Floodgate-Velocity at deploy/floodgate-velocity.jar and uncomment.
# COPY deploy/floodgate-velocity.jar plugins/floodgate-velocity.jar
COPY deploy/images/entrypoint-velocity.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && mkdir -p plugins/cryon

EXPOSE 25565
ENTRYPOINT ["/entrypoint.sh"]
