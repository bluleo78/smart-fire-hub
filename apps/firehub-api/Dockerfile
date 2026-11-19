# === Stage 1: Build ===
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build

ENV GRADLE_OPTS="-Xmx512m"

WORKDIR /app

# Copy Gradle wrapper and build config (for dependency caching)
# libs/ 에는 저장소에 커밋된 Tibero JDBC jar 가 들어 있다 — build.gradle.kts 가 libs/tibero*.jar 를 fileTree 로 얹는다.
# 비어 있어도(.gitkeep 만) 빌드는 성공한다. 의존성 해석 전에 두어야 캐시 레이어와 bootJar 가 같은 classpath 를 본다.
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts lombok.config ./
COPY libs/ libs/
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
# -XX:-UseBASE64Intrinsics: CVE-2026-114938(Java4Shell) 완화. x86 AVX-512 VBMI CPU 에서 java.util.Base64 디코드
# 인트린직이 힙 밖으로 쓴다. 고객사 설치본처럼 호스트 CPU 를 고를 수 없는 곳까지 막으려고 이미지에 박는다.
# JAVA_TOOL_OPTIONS(k8s·compose 가 덮어씀)가 아니라 커맨드라인에 둬서 배포 설정과 무관하게 항상 적용된다.
# 수정판 JDK(21.0.13.1+) 베이스로 바꾼 뒤에도 남겨 둬도 된다 — Base64 처리만 순수 Java 로 돌아 조금 느려진다.
ENTRYPOINT ["java", "-XX:-UseBASE64Intrinsics", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
