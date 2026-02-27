# === Stage 1: Build ===
FROM eclipse-temurin:21-jdk AS build

ENV GRADLE_OPTS="-Xmx512m"

WORKDIR /app

# Copy Gradle wrapper and build config (for dependency caching)
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Copy source (including jOOQ generated sources in src/main/generated/)
COPY src/ src/

# Build JAR — skip tests and jOOQ codegen (generated sources already in src/main/generated/)
RUN ./gradlew bootJar -x test -x generateJooq --no-daemon

# === Stage 2: Runtime ===
FROM eclipse-temurin:21-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
