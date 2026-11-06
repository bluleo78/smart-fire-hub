import java.io.File
import java.util.Properties

buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.testcontainers:postgresql:1.20.4")
        classpath("org.flywaydb:flyway-core:10.20.1")
        classpath("org.flywaydb:flyway-database-postgresql:10.20.1")
        classpath("org.postgresql:postgresql:42.7.4")
        classpath("org.jooq:jooq-codegen:3.19.16")
    }
}

plugins {
    java
    id("org.springframework.boot") version "3.4.1"
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

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    // WD-487 PoC - MyBatis (starter 는 쓰지 않는다: DataSource 가 여럿이라 자동 구성이 잘못 붙을 수 있고, 팩토리 생성을 Tibero 하네스와 공유해야 한다)
    implementation("org.mybatis:mybatis:3.5.16")
    implementation("org.mybatis:mybatis-spring:3.0.4")
    // 관측성 — Micrometer + 헬스체크/메트릭 endpoints (Task 13)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Tibero 플랫폼 DB 용 Flyway 플러그인(WD-488)의 기반 — Flyway 는 Tibero 를 지원하지 않아 Oracle 모듈을 상속해 제품명·사전 뷰 차이만 덮어쓴다.
    // 버전은 Spring Boot BOM 이 flyway-core 와 같은 값으로 맞춘다(10.20.1).
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
    implementation("org.jobrunr:jobrunr-spring-boot-3-starter:7.3.2")
    // WebFlux for SSE proxy + WebClient for API calls
    implementation("org.springframework.boot:spring-boot-starter-webflux")
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
    implementation("org.jsoup:jsoup:1.18.3")
    // MinIO(S3 호환) 오브젝트 스토리지 — FILE 데이터셋
    implementation("io.minio:minio:8.5.17")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
        exclude(group = "net.bytebuddy")
    }
    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
    testImplementation("net.bytebuddy:byte-buddy:1.17.5")
    testImplementation("net.bytebuddy:byte-buddy-agent:1.17.5")
    testImplementation("org.springframework.security:spring-security-test")
    // Testcontainers — 통합 테스트용 격리 PostgreSQL 컨테이너.
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // WireMock for API call integration tests
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
    // MockWebServer for embedding provider unit tests (버전은 Spring Boot BOM이 관리)
    testImplementation("com.squareup.okhttp3:mockwebserver")
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

// 스키마 변경 시 `./gradlew generateJooq`로 생성 소스를 갱신한다. 생성 소스는 저장소에 추적한다.
// 코드는 일회용 PostGIS + pgvector 컨테이너에 전체 Flyway 마이그레이션을 적용한 뒤 생성한다.
// DB 접속 정보를 설정 시점에 고정하는 nu.studer 플러그인 대신 동적 JDBC URL을 받는 GenerationTool을 쓴다.
tasks.register("generateJooq") {
    group = "jooq"
    description = "Testcontainers PostgreSQL에 마이그레이션 적용 후 jOOQ 소스를 생성"
    val migrationDir = layout.projectDirectory.dir("src/main/resources/db/migration").asFile.absolutePath
    val outputDir = layout.projectDirectory.dir("src/main/generated").asFile.absolutePath
    val postgresDockerfile = rootProject.projectDir.toPath()
        .resolve("../../docker/postgres/Dockerfile")
        .normalize()
    inputs.dir(migrationDir)
    inputs.file(postgresDockerfile)
    inputs.property("jooqCodegenVersion", "3.19.16")
    outputs.dir(outputDir)

    doLast {
        System.setProperty("api.version", "1.41")

        // Gradle 태스크는 테스트 JVM 밖에서 실행되므로 OrbStack 소켓을 Testcontainers 사용자 설정으로 전달한다.
        if (System.getenv("DOCKER_HOST") == null) {
            val orbStackSocket = File(System.getProperty("user.home"), ".orbstack/run/docker.sock")
            if (orbStackSocket.exists()) {
                val userConfigFile = File(System.getProperty("user.home"), ".testcontainers.properties")
                val properties = Properties()
                if (userConfigFile.exists()) {
                    userConfigFile.inputStream().use { properties.load(it) }
                }
                if (properties.getProperty("docker.host") == null) {
                    properties.setProperty("docker.host", "unix://${orbStackSocket.absolutePath}")
                    userConfigFile.outputStream().use {
                        properties.store(it, "smart-fire-hub: OrbStack 자동 감지(generateJooq)")
                    }
                }
            }
        }

        val imageName = org.testcontainers.images.builder.ImageFromDockerfile()
            .withDockerfile(postgresDockerfile)
            .get()
        val container = org.testcontainers.containers.PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse(imageName).asCompatibleSubstituteFor("postgres")
        )
            .withDatabaseName("smartfirehub_codegen")
            .withUsername("app")
            .withPassword("app")
            .withCommand("postgres", "-c", "max_connections=200")

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
    // 저장소 루트(apps/firehub-api 의 두 단계 위). Docker 빌드(/app)처럼 조부모가 없는 경로에서도
    // 설정 단계가 NPE 로 죽지 않도록 parentFile 체인 대신 경로 정규화로 계산한다.
    systemProperty(
        "smartfirehub.repositoryRoot",
        rootProject.projectDir.toPath().resolve("../..").normalize().toFile().absolutePath
    )
    useJUnitPlatform()
    // 기본값(512MB)은 Spring Boot+Testcontainers+jOOQ+JaCoCo 계측이 겹친 통합테스트에는 작아서,
    // 다른 세션들과 자원을 나눠 쓰는 부하 상황에서 워커 JVM이 OutOfMemoryError로 죽는 원인이 됐다.
    maxHeapSize = "1536m"
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
    filter {
        includeTestsMatching("com.smartfirehub.dataset.*")
        includeTestsMatching("com.smartfirehub.dataimport.*")
        includeTestsMatching("com.smartfirehub.pipeline.*")
        includeTestsMatching("com.smartfirehub.analytics.*")
        includeTestsMatching("com.smartfirehub.securitylevel.pythonread.*")
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
        // WD-493 — OutputTableSessionLock(세션 잠금)은 아직 플랫폼 DB 에서 잡히고 SQL REPLACE 의 출력 잠금(OUTPUT_LOCK_SQL)은 파이프라인
        // 연결(데이터 저장소)에서 잡힌다. 분리 모드에서는 두 잠금이 다른 DB 라 서로 기다리지 않는다 — 잠금 위치 이전이 WD-493 범위다.
        excludeTestsMatching("com.smartfirehub.pipeline.service.OutputTableSessionLockTest.세션_잠금을_쥔_동안_같은_출력의_SQL_REPLACE는_기다린다")
    }
    // 분리 모드 결과는 컨테이너 두 개 상태에 달려 있어 캐시하지 않는다.
    outputs.upToDateWhen { false }
    // 커버리지 리포트는 기본 test 의 실행 데이터로 만든다 — 이 태스크는 리포트를 끌어오지 않는다.
    setFinalizedBy(emptyList<Any>())
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
}
