import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties

buildscript {
    repositories { mavenCentral() }
    dependencies {
        // flyway·jooq-codegen·testcontainers·postgresql 은 buildscript 클래스패스라 Spring Boot BOM 밖이다. 런타임 BOM
        // (Boot 4.1.2: flyway 12.4.0, jooq 3.21.9, testcontainers 2.0.5, postgresql 42.7.14)과 직접 맞춰 고정해야 하며,
        // 특히 코드젠 jOOQ 가 런타임과 어긋나면 생성 소스가 달라진다(drift). Boot 를 올리면 이 값들도 함께 맞춘다.
        // Testcontainers 2 는 모듈 아티팩트 이름에 testcontainers- 접두가 붙었다(postgresql → testcontainers-postgresql).
        classpath("org.testcontainers:testcontainers-postgresql:2.0.5")
        classpath("org.flywaydb:flyway-core:12.4.0")
        classpath("org.flywaydb:flyway-database-postgresql:12.4.0")
        classpath("org.postgresql:postgresql:42.7.14")
        classpath("org.jooq:jooq-codegen:3.21.9")
    }
}

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "6.25.0"
    jacoco
}

// JaCoCo 커버리지 설정 — 로컬 리포트 전용 (CI 연동 없음)
// 0.8.13: Java 25(class major version 69) 지원 추가
jacoco {
    toolVersion = "0.8.13"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    // 제외: jOOQ 코드젠 결과물 — 테스트 대상이 아니며 리포트 수치를 왜곡함
    // 제외: 단순 DTO/예외 클래스 — 엔트리/생성자 중심이라 의미 있는 로직 없음
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "com/smartfirehub/jooq/**",
                        "**/dto/**",
                        "**/*Exception.class",
                    )
                }
            }
        )
    )
}

group = "com.smartfirehub"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.44")
    compileOnly("org.jetbrains:annotations:26.0.2")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.jetbrains:annotations:26.0.2")
    testCompileOnly("org.projectlombok:lombok:1.18.44")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.44")

    // Spring @NonNull 대신 쓰는 JSpecify 를 전이 의존성(micrometer 등)에 기대지 않으려고 명시한다.
    // 1.0.0 은 compileClasspath 에서 이미 해석되는 버전이다(Spring 7·Boot 4.1 과 같다).
    implementation("org.jspecify:jspecify:1.0.0")
    // Boot 4 에서 spring-boot-starter-web 은 spring-boot-starter-webmvc 로 이름이 바뀌었다(옛 이름은 deprecated 별칭).
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Jackson 2 유지(WD-792) — Boot 4 기본은 Jackson 3 이라, Jackson 2 ObjectMapper 빈·HTTP 메시지 변환기 자동 구성을 이 모듈이 따로 준다.
    // 코드 전반이 com.fasterxml ObjectMapper 를 주입받으므로 없으면 컨텍스트가 뜨지 않는다. Jackson 3 전환은 WD-793.
    implementation("org.springframework.boot:spring-boot-jackson2")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    // WD-487 PoC - MyBatis (starter 는 쓰지 않는다: DataSource 가 여럿이라 자동 구성이 잘못 붙을 수 있고, 팩토리 생성을 Tibero 하네스와 공유해야 한다)
    // mybatis-spring 3.x 는 Spring 6 까지라 Spring 7(Boot 4)을 지원하는 4.x 로 올린다. 4.1.0 은 Boot 4.1 기준으로 만든 판이다.
    implementation("org.mybatis:mybatis:3.5.19")
    implementation("org.mybatis:mybatis-spring:4.1.0")
    // 관측성 — Micrometer + 헬스체크/메트릭 endpoints (Task 13)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Boot 4 는 Flyway 자동 구성을 별도 모듈로 뺐다 — flyway-core 만 두면 자동 구성이 없어 마이그레이션이 조용히 건너뛰어진다.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Tibero 플랫폼 DB 용 Flyway 플러그인(WD-488)의 기반 — Flyway 는 Tibero 를 지원하지 않아 Oracle 모듈을 상속해 제품명·사전 뷰 차이만 덮어쓴다.
    // 버전은 Spring Boot BOM 이 flyway-core 와 같은 값으로 맞춘다(12.4.0).
    implementation("org.flywaydb:flyway-database-oracle")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    // Tibero JDBC 드라이버 — Maven Central 에 없고 라이선스상 저장소에 커밋하지 않는다(*.jar 는 gitignore).
    // apps/firehub-api/libs/ 에 커밋된 Tibero JDBC(tibero*.jar)를 런타임 클래스패스에 포함한다.
    // libs 폴더가 없거나 비어 있어도 fileTree 는 빈 집합이라 빌드·테스트는 그대로 동작한다.
    runtimeOnly(fileTree("libs") { include("tibero*.jar") })
    // PG driver — implementation 으로 둬서 OutboxListenerLoop가 PGConnection을 직접 사용할 수 있음
    implementation("org.postgresql:postgresql")
    // CSV/Excel 파싱
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    implementation("org.apache.pdfbox:pdfbox:2.0.32")
    implementation("com.opencsv:opencsv:5.9")
    // Jobrunr - 비동기 작업 스케줄링
    // Boot 4 스타터(jobrunr-spring-boot-4-starter)는 8.3.0 부터 있다. 8.x 는 7.x 대비 jobrunr_jobs_stats 뷰에 awaiting 컬럼을 더하는
    // 마이그레이션(v016) 하나만 붙고, 9.x 는 거기에 v017 을 더한다(PG·Oracle 은 빈 파일, MySQL 계열만 뷰 재생성). 저장소 스키마 변경이
    // 가장 적고 메이저를 한 단계만 넘는 8.x 최신판을 고른다(WD-792). 테이블은 Flyway 가 관리(skip-create)하므로 v016 은 우리 마이그레이션
    // V167(PG·Tibero)로 반영했다. JobRunr 를 올릴 때는 jar 의 org/jobrunr/storage/sql/{common,oracle}/migrations 목록을 다시 비교한다.
    implementation("org.jobrunr:jobrunr-spring-boot-4-starter:8.8.2")
    // WebFlux for SSE proxy + WebClient for API calls
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    // Boot 4 는 WebClient.Builder 자동 구성(spring.http.clients.reactive.*)을 별도 스타터로 뺐다 — 임베딩 호출 등이 주입받는다.
    implementation("org.springframework.boot:spring-boot-starter-webclient")
    // JSONPath for API response parsing
    implementation("com.jayway.jsonpath:json-path:2.9.0")
    // Caffeine cache — analytics dashboard query result caching
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    // Email sending
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // Thymeleaf email templates
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    // Markdown → HTML 변환
    implementation("org.commonmark:commonmark:0.24.0")
    // SQL 파서 — 파이프라인 SQL 스텝 AST 화이트리스트 검증 (#136)
    implementation("com.github.jsqlparser:jsqlparser:5.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    // PDF 생성 (Flying Saucer — HTML/CSS → PDF)
    implementation("org.xhtmlrenderer:flying-saucer-pdf-openpdf:9.4.0")
    // HTML5 → XHTML 변환 (AI 생성 HTML을 Flying Saucer에 전달하기 위함)
    // 1.23.2: 1.18.3 의 DoS(CVE-2026-75140, 깊게 중첩된 HTML) 수정판(WD-792 trivy).
    implementation("org.jsoup:jsoup:1.23.2")
    // MinIO(S3 호환) 오브젝트 스토리지 — FILE 데이터셋
    // 8.6.0: 8.5.17 의 XML 태그 취약점(CVE-2025-59952) 수정판(WD-792 trivy). 이 판부터 okhttp 5 를 끌어온다 — 아래 mockwebserver 도 맞춘다.
    implementation("io.minio:minio:8.6.0")
    // Qdrant 공식 Java 클라이언트(gRPC) — 벡터 저장소를 Qdrant 로 고른 설치본용(ADR 0002, WD-719). 서버는 v1.20 이상.
    implementation("io.qdrant:client:1.20.0")
    // 키워드 바이그램 해시(murmur3)에 직접 쓴다. Spring Boot BOM 은 guava 를 관리하지 않는다. 전이로 오는 33.6.0(gRPC·Qdrant 경유)에
    // DoS(CVE-2026-102554)가 있어 수정판 33.7.2-jre 를 직접 고정한다(WD-792 trivy) — 같은 33.x 라 gRPC·minio 와 API 호환.
    // guava 의 Gradle 모듈 메타데이터가 standard-jvm 요청에 JRE 변형을 내주므로 -android 가 와도 실제는 JRE 판이다(WD-791).
    implementation("com.google.guava:guava:33.7.2-jre")
    // 전이 의존성 보안 수정판 고정(WD-792 trivy, 수정판 있는 HIGH·CRITICAL 0 목표). 직접 쓰지 않으므로 constraints 로 버전만 올린다.
    constraints {
        // minio 경유. 1.78.1/1.81 은 CVE-2025-14813(CRITICAL)·CVE-2026-8763·13506·5598 — 1.85 가 모두 고친 판이다.
        implementation("org.bouncycastle:bcprov-jdk18on:1.85")
        // minio 경유. 1.1.10.7 은 CVE-2026-108106·93451.
        implementation("org.xerial.snappy:snappy-java:1.1.10.9")
        // opencsv 5.9 경유. 1.9.4 는 CVE-2025-48734.
        implementation("commons-beanutils:commons-beanutils:1.11.0")
    }

    // Mockito·byte-buddy 는 Spring Boot BOM(Mockito 5.23.0, byte-buddy 1.18.15)이 관리하는 버전을 그대로 쓴다.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 는 테스트 자동 구성도 기술별 모듈로 나눴다. @WebMvcTest·@AutoConfigureMockMvc 는 webmvc-test 에, MockMvc 에 보안 필터를
    // 거는 자동 구성은 security-test 에 있다(없으면 @WebMvcTest 가 보안 없이 떠서 401/403 기대 시험이 무더기로 어긋난다).
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    // Testcontainers 2 — 모듈 아티팩트 이름에 testcontainers- 접두가 붙었다. 통합 테스트용 격리 PostgreSQL 컨테이너.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // Qdrant 하네스(WD-719) — 기동 시 컬렉션 생성·확인 테스트
    testImplementation("org.testcontainers:testcontainers-qdrant")
    // WireMock for API call integration tests
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
    // MockWebServer for embedding provider unit tests.
    // Spring Boot BOM 은 okhttp 버전을 관리하지 않으므로 직접 고정한다. 이 값은 minio 8.6.0 이 끌어오는 okhttp(런타임 클래스패스 5.1.0)와
    // 같아야 한다 — mockwebserver 는 같은 버전의 okhttp 를 요구하므로, 어긋나면 시험 클래스패스에서 okhttp 가 바뀌어 minio 가 운영과
    // 다른 okhttp 로 시험된다. minio 를 올리면 함께 맞춘다. 5.x 의 mockwebserver 아티팩트는 옛 okhttp3.mockwebserver API 를 그대로 둔다.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.1.0")
    // SQL 실행 단일 관문(GuardedSqlExecutor) 우회 금지 규칙(스펙 §4.1). 로컬 Gradle 캐시에 있는 버전.
    testImplementation("com.tngtech.archunit:archunit:1.3.0")
    testRuntimeOnly("org.postgresql:postgresql")
    // Tibero 실서버 테스트용 드라이버(위 runtimeOnly 와 같은 이유로 선택적, 없으면 빈 집합)
    testRuntimeOnly(fileTree("libs") { include("tibero*.jar") })
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    main {
        java {
            srcDir("src/main/generated")
        }
    }
}

// Testcontainers(내장 docker-java)가 Docker 에 요청할 API 버전(WD-762). 1.20.4 는 정하지 않으면 1.32 로 요청해, 최신 Docker 데몬이
// 최소 API(1.40~1.44) 미만을 거절하며 "Could not find a valid Docker environment" 로 모든 컨테이너 테스트가 죽었다. Testcontainers 2 도
// 같은 api.version 시스템 속성을 읽으므로, 데몬마다 협상 결과가 달라지지 않게 값을 계속 명시한다.
// 1.44 는 Docker 25 이상이 필요하다. 테스트 JVM 과 generateJooq 가 같은 값을 쓰도록 여기 한 곳에 둔다.
val testcontainersDockerApiVersion = "1.44"

// 시험용 PostgreSQL 이미지(docker/postgres/Dockerfile)의 경로. generateJooq 와 모든 Test 태스크가 같은 파일을 입력으로 쓴다.
val testPostgresDockerfile: File = rootProject.projectDir.toPath()
    .resolve("../../docker/postgres/Dockerfile")
    .normalize()
    .toFile()

/**
 * 시험용 PostgreSQL 이미지를 준비하는 한 곳(WD-798). 이름 규칙·빌드 인자·빌드 여부 판단을 여기에만 둔다 —
 * 예전(WD-794)에는 PostgresTestContainer 와 generateJooq 가 같은 규칙을 따로 들고 있었고, 시험 JVM 포크마다 빌드를 불렀다.
 *
 * - 이름: `firehub-test-postgres:<Dockerfile SHA-256 앞 12자>` — Dockerfile 이 같은 동안 이미지를 다시 쓰고, 바뀌면 새 이름으로 새로 빌드한다.
 * - 빌드 인자 OS_PATCH=skip: 시험 이미지는 apt upgrade(~15분)를 건너뛴다. Dockerfile 기본값은 「패치함」이라 운영·손 빌드는 upgrade 한다.
 * - 이미 있으면 빌드하지 않는다: 층 캐시로도 빌드 컨텍스트 전송·층 확인에 몇 초가 드는데, 이름이 내용 해시라 있으면 같은 이미지다.
 *
 * 구성 캐시(--configuration-cache)에서 태스크 액션이 스크립트 객체를 잡지 않도록 스크립트 함수가 아니라 object 로 두고,
 * 호출하는 쪽은 지역 변수(경로·API 버전)만 넘긴다. Docker 는 이 함수가 불릴 때(generateJooq·Test 실행)만 쓰여, compileJava 같은
 * 다른 태스크는 Docker 없이도 돈다.
 */
object TestPostgresImage {
    /** 시험 이미지 빌드 인자 — 이름(해시)과 빌드가 같은 값을 쓰도록 한 곳에 둔다. */
    private val BUILD_ARGS = mapOf("OS_PATCH" to "skip")

    /**
     * 이미지를 준비하고(없으면 빌드) 이름을 돌려준다. Docker 데몬에 닿을 수 없으면 null — 부르는 쪽이 「Docker 없음」
     * (Test 는 경고만, generateJooq 는 실패)을 정한다. Docker 는 있는데 빌드가 실패하면 예외를 그대로 던진다.
     */
    fun prepare(dockerfile: File, dockerApiVersion: String, logger: org.gradle.api.logging.Logger): String? {
        // Gradle 데몬 안의 Testcontainers 가 데몬이 받는 API 버전으로 요청하게 한다(WD-762).
        System.setProperty("api.version", dockerApiVersion)
        configureOrbStackSocket()
        if (!org.testcontainers.DockerClientFactory.instance().isDockerAvailable) return null

        // 해시 입력에 빌드 인자도 넣는다 — 인자만 바꿔도(예: OS_PATCH 값) 이름이 바뀌어, 다르게 빌드된 옛 이미지를 그대로 믿지 않는다.
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(dockerfile.readBytes())
        BUILD_ARGS.toSortedMap().forEach { (key, value) -> digest.update("\n--build-arg $key=$value".toByteArray()) }
        val tag = digest.digest()
            .joinToString("") { "%02x".format(it) }
            .substring(0, 12)
        val imageName = "firehub-test-postgres:$tag"

        val client = org.testcontainers.DockerClientFactory.instance().client()
        val exists = try {
            client.inspectImageCmd(imageName).exec()
            true
        } catch (e: com.github.dockerjava.api.exception.NotFoundException) {
            false
        }
        if (exists) {
            logger.lifecycle("시험용 PostgreSQL 이미지 $imageName 이 이미 있어 빌드를 건너뜁니다")
            return imageName
        }

        logger.lifecycle("시험용 PostgreSQL 이미지 $imageName 을 빌드합니다($BUILD_ARGS)")
        val started = System.nanoTime()
        // deleteOnExit=false: 이름 붙인 이미지를 남겨 다음 실행이 다시 쓰게 한다(WD-794).
        org.testcontainers.images.builder.ImageFromDockerfile(imageName, false)
            .withDockerfile(dockerfile.toPath())
            .withBuildArgs(BUILD_ARGS)
            .get()
        logger.lifecycle("시험용 PostgreSQL 이미지 $imageName 빌드 완료(${(System.nanoTime() - started) / 1_000_000}ms)")
        return imageName
    }

    /**
     * Gradle 태스크는 시험 JVM 밖(데몬)에서 Docker 를 부르므로, DOCKER_HOST 가 없고 OrbStack 소켓이 있으면
     * Testcontainers 사용자 설정(~/.testcontainers.properties)에 그 소켓을 적어 데몬도 찾게 한다(맥 개발 PC).
     */
    private fun configureOrbStackSocket() {
        if (System.getenv("DOCKER_HOST") != null) return
        val orbStackSocket = File(System.getProperty("user.home"), ".orbstack/run/docker.sock")
        if (!orbStackSocket.exists()) return
        val userConfigFile = File(System.getProperty("user.home"), ".testcontainers.properties")
        val properties = Properties()
        if (userConfigFile.exists()) {
            userConfigFile.inputStream().use { properties.load(it) }
        }
        if (properties.getProperty("docker.host") == null) {
            properties.setProperty("docker.host", "unix://${orbStackSocket.absolutePath}")
            userConfigFile.outputStream().use {
                properties.store(it, "smart-fire-hub: OrbStack 자동 감지(시험용 PostgreSQL 이미지 준비)")
            }
        }
    }
}

// 스키마 변경 시 `./gradlew generateJooq`로 생성 소스를 갱신한다. 생성 소스는 저장소에 추적한다.
// 코드는 일회용 PostGIS + pgvector 컨테이너에 전체 Flyway 마이그레이션을 적용한 뒤 생성한다.
// DB 접속 정보를 설정 시점에 고정하는 nu.studer 플러그인 대신 동적 JDBC URL을 받는 GenerationTool을 쓴다.
tasks.register("generateJooq") {
    group = "jooq"
    description = "Testcontainers PostgreSQL에 마이그레이션 적용 후 jOOQ 소스를 생성"
    val migrationDir = layout.projectDirectory.dir("src/main/resources/db/migration").asFile.absolutePath
    val outputDir = layout.projectDirectory.dir("src/main/generated").asFile.absolutePath
    // 구성 캐시가 스크립트 객체를 직렬화하지 못하므로, doLast 가 스크립트 수준 val 대신 지역 변수를 읽게 복사한다.
    val postgresDockerfile = testPostgresDockerfile
    val dockerApiVersion = testcontainersDockerApiVersion
    inputs.dir(migrationDir)
    inputs.file(postgresDockerfile)
    inputs.property("jooqCodegenVersion", "3.21.9")
    outputs.dir(outputDir)

    doLast {
        // 시험과 같은 이미지(같은 이름·빌드 인자)를 쓴다 — 이미 있으면 빌드하지 않는다(WD-798, TestPostgresImage).
        val imageName = TestPostgresImage.prepare(postgresDockerfile, dockerApiVersion, logger)
            ?: throw GradleException("Docker 를 쓸 수 없어 jOOQ 생성용 PostgreSQL 이미지를 준비하지 못했습니다 — Docker 데몬을 켜세요")
        val container = org.testcontainers.containers.PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse(imageName).asCompatibleSubstituteFor("postgres")
        )
            .withDatabaseName("smartfirehub_codegen")
            .withUsername("app")
            .withPassword("app")
            .withCommand("postgres", "-c", "max_connections=200")
            // 기동 대기 상한을 시험(PostgresTestContainer, WD-784)과 같은 5분으로 둔다 — 여러 세션이 한 호스트를 나눠 쓰는
            // 부하에서는 PostGIS 초기화가 기본 60초를 넘겨 generateJooq 가 실패했다(WD-798 확인 중 실측).
            .withStartupTimeout(Duration.ofMinutes(5))

        container.start()
        try {
            org.flywaydb.core.Flyway.configure()
                .dataSource(container.jdbcUrl, container.username, container.password)
                .locations("filesystem:$migrationDir")
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
                .load()
                .migrate()

            val configuration = org.jooq.meta.jaxb.Configuration()
                .withJdbc(
                    org.jooq.meta.jaxb.Jdbc()
                        .withDriver("org.postgresql.Driver")
                        .withUrl(container.jdbcUrl)
                        .withUser(container.username)
                        .withPassword(container.password)
                )
                .withGenerator(
                    org.jooq.meta.jaxb.Generator()
                        .withName("org.jooq.codegen.DefaultGenerator")
                        .withDatabase(
                            org.jooq.meta.jaxb.Database()
                                .withName("org.jooq.meta.postgres.PostgresDatabase")
                                .withInputSchema("public")
                                .withExcludes("st_dump|st_dumppoints|st_dumprings|st_dumpsegments|st_fromflatgeobuf")
                        )
                        .withGenerate(
                            org.jooq.meta.jaxb.Generate()
                                .withRoutines(false)
                                .withUdts(false)
                        )
                        .withTarget(
                            org.jooq.meta.jaxb.Target()
                                .withPackageName("com.smartfirehub.jooq")
                                .withDirectory(outputDir)
                        )
                )
            org.jooq.codegen.GenerationTool.generate(configuration)
        } finally {
            container.stop()
        }
    }
}

spotless {
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        googleJavaFormat("1.34.1")
    }
}

// 로컬 개발(pnpm dev → bootRun)도 운영 Docker 처럼 JVM 을 UTC 로 돌린다(WD-476).
// 서버는 offset 없는 LocalDateTime 을 UTC 로 보내고 웹은 그렇게 읽는데(parseUtcDate), KST 컴퓨터의 기본 시간대로 돌면
// KST 값이 나가 화면 시각이 9시간 밀린다. 테스트 JVM 은 건드리지 않는다(컴퓨터 시간대 의존은 WD-472 범위).
tasks.bootRun {
    jvmArgs("-Duser.timezone=UTC")
}

tasks.withType<Test> {
    // 로컬 OrbStack 소켓이 있으면 개별 테스트 프로세스에 Docker 연결 정보를 넘긴다.
    val orbStackSocket = File(System.getProperty("user.home"), ".orbstack/run/docker.sock")
    if (System.getenv("DOCKER_HOST") == null && orbStackSocket.exists()) {
        environment("DOCKER_HOST", "unix://${orbStackSocket.absolutePath}")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }
    // 테스트 JVM 의 Testcontainers 가 데몬이 받는 API 버전으로 요청하게 한다(기본 1.32 는 거절됨, WD-762).
    systemProperty("api.version", testcontainersDockerApiVersion)
    // 시험용 PostgreSQL 이미지는 포크를 띄우기 전에 Gradle 이 한 번 준비하고, 이름을 시스템 속성으로 넘긴다(WD-798).
    // 예전에는 포크(JVM)마다 PostgresTestContainer 가 빌드를 불러 포크 2 면 같은 빌드가 두 번 돌았다.
    // tasks.withType<Test> 라 기본 test 뿐 아니라 tiberoTest·separateDataStoreTest 도 같은 이미지를 받는다.
    // Dockerfile 을 입력에 넣어, 바뀌면 시험이 다시 돈다(이미지 이름도 바뀐다).
    // doFirst 는 구성 캐시가 스크립트 객체를 잡지 않게 지역 변수만 읽는다(generateJooq 와 같은 방식).
    val postgresDockerfile = testPostgresDockerfile
    val dockerApiVersion = testcontainersDockerApiVersion
    // 저장소 밖에서 앱 폴더만 쓰는 곳(api 이미지 빌드 컨텍스트 등)에는 ../../docker 가 없다 — 없는 파일을 입력으로 걸면
    // 태스크 검증이 실패하므로 있을 때만 건다.
    val hasPostgresDockerfile = postgresDockerfile.exists()
    if (hasPostgresDockerfile) {
        inputs.file(postgresDockerfile)
    }
    doFirst {
        // Docker 를 쓸 수 없거나 Dockerfile 이 없으면 태스크 전체를 멈추지 않고 경고만 낸다 — DB 가 필요 없는 단위 시험
        // (--tests 로 고른 것)은 Docker 없이도 돌아야 한다. 통합 시험은 속성이 없어 PostgresTestContainer 가 이유를 적은 오류로 실패한다.
        // Docker 는 있는데 빌드가 실패하면(네트워크·PGDG 판 내려감 등) 삼키지 않고 태스크를 실패시킨다 — 경고 한 줄로 바꾸면
        // 진짜 원인이 수천 건의 「속성 없음」 시험 실패에 묻힌다.
        val imageName =
            if (hasPostgresDockerfile) TestPostgresImage.prepare(postgresDockerfile, dockerApiVersion, logger) else null
        if (imageName != null) {
            (this as Test).systemProperty("smartfirehub.testPostgresImage", imageName)
        } else {
            logger.warn("Docker 를 쓸 수 없거나 Dockerfile($postgresDockerfile)이 없어 시험용 PostgreSQL 이미지를 준비하지 못했습니다 — DB 를 쓰는 통합 시험은 실패합니다")
        }
    }
    useJUnitPlatform()
    // 기본값(512MB)은 Spring Boot+Testcontainers+jOOQ+JaCoCo 계측이 겹친 통합테스트에는 작아서,
    // 다른 세션들과 자원을 나눠 쓰는 부하 상황에서 워커 JVM이 OutOfMemoryError로 죽는 원인이 됐다.
    maxHeapSize = "1536m"
    // 병렬 포크 수(WD-764): gradle 속성 testForks → 환경변수 FIREHUB_TEST_FORKS → 기본 2 순서로 고른다.
    // 기본 2 는 AI 클론 여럿이 함께 도는 리눅스 에이전트 호스트 실측으로 정했다. 포크마다 힙 1.5GB+ 에 Testcontainers
    // 컨테이너가 따로 뜨므로, 메모리가 작은 환경(맥 16GB 등)은 FIREHUB_TEST_FORKS=1 로 낮춘다.
    // 수치는 .work-loop/project.md 「오래 걸리는 작업」 표를 본다.
    val forksRaw = providers.gradleProperty("testForks").orNull
        ?: providers.environmentVariable("FIREHUB_TEST_FORKS").orNull
        ?: "2"
    val forks = forksRaw.trim().toIntOrNull()
    if (forks == null || forks < 1) {
        // 잘못된 값(0·음수·문자)을 조용히 1 로 바꾸면 의도와 다른 실행이 되므로 설정 단계에서 바로 실패시킨다.
        throw GradleException("testForks(또는 FIREHUB_TEST_FORKS)는 1 이상의 정수여야 합니다: $forksRaw")
    }
    maxParallelForks = forks
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "-Dnet.bytebuddy.experimental=true"
    )
    // 테스트 완료 후 JaCoCo 리포트 자동 생성
    finalizedBy(tasks.jacocoTestReport)
    // 리포트를 빼는 실행(-x jacocoTestReport, 커밋·push 게이트)은 -PnoCoverage 로 계측 에이전트도 끈다(WD-459) —
    // 리포트를 안 만들면 테스트 내내 모든 클래스를 계측하는 비용만 남는다.
    extensions.configure<JacocoTaskExtension> { isEnabled = !providers.gradleProperty("noCoverage").isPresent }
}

// Tibero 실서버 테스트 전용 태스크(WD-497). @TiberoLive 클래스만 고르고, 그 클래스들은 이 태스크가 켜는
// firehub.tibero-live=true 가 있을 때만 돈다 — 기본 test(pre-push 전체 회귀 포함)는 셸에 TIBERO_TEST_* 가 남아 있어도
// 공유 서버(세션 20개, DCIM 팀과 공유)에 붙지 않는다. 접속 정보는 scripts/tibero-test.sh 가 환경변수로 넘긴다.
// - UTC JVM: Tibero 드라이버가 Instant 를 JVM 시간대 벽시계로 저장해 KST 에서는 JobRunr 가 깨진다(ADR 0001 결정 6).
// - 단일 포크: 세션 한도 안에서 계정·풀을 하나씩 쓴다.
val tiberoTest by tasks.registering(Test::class) {
    description = "Tibero 실서버 테스트(@TiberoLive)만 UTC JVM 에서 실행한다."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("tibero") }
    systemProperty("firehub.tibero-live", "true")
    jvmArgs("-Duser.timezone=UTC")
    maxParallelForks = 1
    // 실서버 결과는 매번 달라질 수 있어(라이선스·공유 서버 상태) 캐시하지 않는다.
    outputs.upToDateWhen { false }
    // 커버리지 리포트는 기본 test 의 실행 데이터로 만든다 — 이 태스크는 리포트를 끌어오지 않는다.
    setFinalizedBy(emptyList<Any>())
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
}

// 분리 모드 통합 회귀 태스크(WD-494). 데이터 저장소를 다루는 패키지의 통합 테스트를 플랫폼 DB 와 다른 두 번째 PG 컨테이너
// (firehub.data-store.postgres.separate=true)로 다시 돌린다 — PostgresTestContainer 가 시스템 속성을 보고 분리 모드 키를 등록한다.
// 기본 test 는 별칭 모드(같은 DB·같은 인스턴스)라 "데이터 저장소 객체를 플랫폼 연결로 만지는" 결함을 잡지 못한다.
val separateDataStoreTest by tasks.registering(Test::class) {
    description = "데이터 저장소 관련 통합 테스트를 분리 모드(플랫폼 DB ≠ 데이터 저장소 DB)로 실행한다."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("smartfirehub.separateDataStore", "true")
    // 단일 포크: 포크마다 PG 컨테이너를 두 개(플랫폼·데이터 저장소) 띄우고, 포크 2 로는 재 보지 않았다 —
    // 기본 test 의 testForks 설정(기본 2)을 물려받지 않게 tiberoTest 처럼 1 로 고정한다.
    maxParallelForks = 1
    filter {
        includeTestsMatching("com.smartfirehub.dataset.*")
        includeTestsMatching("com.smartfirehub.dataimport.*")
        includeTestsMatching("com.smartfirehub.pipeline.*")
        includeTestsMatching("com.smartfirehub.analytics.*")
        // 보안 등급 판정은 데이터 저장소에서 도는 질의·파이프라인을 가로지른다 — pythonread 만이 아니라 패키지 전체를 분리 모드로 본다(WD-705).
        includeTestsMatching("com.smartfirehub.securitylevel.*")
        includeTestsMatching("com.smartfirehub.global.datastore.*")
        includeTestsMatching("com.smartfirehub.global.tenant.*")
    }
    // 제외 목록 — 분리 모드에서 성립할 수 없거나 범위 밖 이슈가 원인인 테스트. 줄마다 사유(이슈 번호)를 단다.
    filter {
        // 기본(별칭) 모드 전용 단언 — @DataStoreDb 빈이 @Primary 와 같은 인스턴스인지, 분리 모드 빈이 없는지를 본다.
        excludeTestsMatching("com.smartfirehub.global.datastore.DataStoreDbAliasTest")
        excludeTestsMatching("com.smartfirehub.global.datastore.DataStoreFlywayConfigTest\$NotSeparate")
        // WD-494 분리 모드 설계상 두 DB — V124 백필 DO 블록(플랫폼 마이그레이션)은 같은 DB 의 dataset 메타를 돌며 data 물리 테이블을 고친다.
        // INSERT_UPDATE 테스트는 트리거 동작이 아니라 선행 조건인 백필 호출(V124 백필은 두 DB 를 가로지름) 때문에 뺀다.
        // 분리 모드 데이터 저장소는 기준선 + createTable 이 _updated_at 을 책임지고, 트리거 동작 자체는 DataTableServiceTest 가 분리 모드로 본다.
        excludeTestsMatching("com.smartfirehub.pipeline.PipelineIncrementalSchemaTest.백필_DO_블록을_재실행하면_새_TABLE_데이터셋의_물리테이블에도_적용된다")
        excludeTestsMatching("com.smartfirehub.pipeline.PipelineIncrementalSchemaTest.INSERT와_UPDATE에서_updated_at이_실제로_채워지고_갱신된다")
    }
    // 분리 모드 결과는 컨테이너 두 개 상태에 달려 있어 캐시하지 않는다.
    outputs.upToDateWhen { false }
    // 커버리지 리포트는 기본 test 의 실행 데이터로 만든다 — 이 태스크는 리포트를 끌어오지 않는다.
    setFinalizedBy(emptyList<Any>())
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
}
