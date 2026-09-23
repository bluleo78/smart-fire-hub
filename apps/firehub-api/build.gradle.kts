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
    // 관측성 — Micrometer + 헬스체크/메트릭 endpoints (Task 13)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
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
    testRuntimeOnly("org.postgresql:postgresql")
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
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "-Dnet.bytebuddy.experimental=true"
    )
    // 테스트 완료 후 JaCoCo 리포트 자동 생성
    finalizedBy(tasks.jacocoTestReport)
}
