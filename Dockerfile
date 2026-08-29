# ==========================================================================
# LOCAL Dockerfile (default Spring profile)
#   - Chat: Ollama       (on the host machine)
#   - Embeddings: Google Gemini (hosted, free tier)
#   - Relational DB: H2 file (inside the container)
#   - Vector DB: Aiven pgvector
#
# Build:  docker build -t local-rag:local .
# Run:    docker run --rm -p 8080:8080 --env-file .env \
#           -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
#           --add-host host.docker.internal:host-gateway \
#           local-rag:local
#
# NOTE: Ollama runs on your HOST, not in this container. Inside a container
# "localhost" is the container itself, so we point the app at
# host.docker.internal (the host machine) via OLLAMA_BASE_URL.
# ==========================================================================

# ---- Stage 1: build the jar with Maven (no local Maven needed) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first (only re-download when pom.xml changes)
COPY pom.xml .
COPY .mvn/ .mvn/
RUN mvn -B -q dependency:go-offline

# Build
COPY src/ src/
RUN mvn -B -q clean package -DskipTests

# ---- Stage 2: slim runtime image ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as a non-root user
RUN useradd --system --create-home appuser
USER appuser

COPY --from=build /app/target/*.jar app.jar

# Ollama location is overridable at runtime (defaults to host machine).
ENV OLLAMA_BASE_URL=http://host.docker.internal:11434

EXPOSE 8080

# spring.ai.ollama.base-url is read from the OLLAMA_BASE_URL env var (relaxed binding).
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.ai.ollama.base-url=${OLLAMA_BASE_URL}"]
