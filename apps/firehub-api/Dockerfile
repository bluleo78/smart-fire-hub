# === Stage 1: Build ===
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build

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
    apt-get install -y --no-install-recommends curl python3 python3-pip && \
    pip3 install --no-cache-dir --break-system-packages \
        psycopg2-binary \
        requests \
        pandas \
        openpyxl \
        beautifulsoup4 \
        lxml && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

# 컨테이너 메모리 인지 힙 설정: 파드/컨테이너 memory limit의 75%를 최대 힙으로 사용.
# JVM 기본값은 limit의 25%(≈384MB@1536Mi)라 대용량 임포트 스트리밍 중에도 힙이 부족해지기 쉽다.
# 스트리밍(배치 처리)으로 근본 해소하되, 이미지에 힙 헤드룸을 baking해 k8s 매니페스트 변경 없이도 방어한다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
