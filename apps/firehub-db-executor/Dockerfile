# === Stage 1: Build ===
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build

ENV GRADLE_OPTS="-Xmx512m"

WORKDIR /app

# 의존성 캐시를 위해 빌드 설정을 먼저 복사한다. libs/ 에는 Tibero 드라이버 jar 가 (있으면) 들어 있다 — 비어 있어도 빌드는 성공한다.
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY libs/ libs/
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src/ src/
RUN ./gradlew bootJar -x test --no-daemon

# === Stage 2: Runtime ===
FROM eclipse-temurin:21-jre

# compose healthcheck 가 curl 로 /actuator/health 를 호출하는데 JRE 이미지에는 curl 이 없다(api 이미지와 같은 방식으로 설치).
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8090

# 컨테이너 메모리 limit 의 75% 를 최대 힙으로 사용한다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
