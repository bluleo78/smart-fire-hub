# === Stage 1: Build ===
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build

ENV GRADLE_OPTS="-Xmx512m"

WORKDIR /app

# 의존성 캐시를 위해 빌드 설정을 먼저 복사한다. libs/ 에는 저장소에 커밋된 Tibero 드라이버 jar 가 들어 있다.
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
# -XX:-UseBASE64Intrinsics: CVE-2026-114938(Java4Shell) 완화. x86 AVX-512 VBMI CPU 에서 java.util.Base64 디코드
# 인트린직이 힙 밖으로 쓴다. 고객사 설치본처럼 호스트 CPU 를 고를 수 없는 곳까지 막으려고 이미지에 박는다.
# JAVA_TOOL_OPTIONS(k8s·compose 가 덮어씀)가 아니라 커맨드라인에 둬서 배포 설정과 무관하게 항상 적용된다.
# 수정판 JDK(21.0.13.1+) 베이스로 바꾼 뒤에도 남겨 둬도 된다 — Base64 처리만 순수 Java 로 돌아 조금 느려진다.
ENTRYPOINT ["java", "-XX:-UseBASE64Intrinsics", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
