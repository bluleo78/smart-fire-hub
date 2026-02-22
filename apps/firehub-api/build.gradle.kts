plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("nu.studer.jooq") version "9.0"
    id("com.diffplug.spotless") version "6.25.0"
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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    runtimeOnly("org.postgresql:postgresql")
    jooqGenerator("org.postgresql:postgresql")
    // CSV/Excel 파싱
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    implementation("com.opencsv:opencsv:5.9")
    // Jobrunr - 비동기 작업 스케줄링
    implementation("org.jobrunr:jobrunr-spring-boot-3-starter:7.3.2")
    // WebFlux for SSE proxy + WebClient for API calls
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    // JSONPath for API response parsing
    implementation("com.jayway.jsonpath:json-path:2.9.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
        exclude(group = "net.bytebuddy")
    }
    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
    testImplementation("net.bytebuddy:byte-buddy:1.17.5")
    testImplementation("net.bytebuddy:byte-buddy-agent:1.17.5")
    testImplementation("org.springframework.security:spring-security-test")
    // WireMock for API call integration tests
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
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

jooq {
    configurations {
        create("main") {
            jooqConfiguration.apply {
                jdbc.apply {
                    driver = "org.postgresql.Driver"
                    url = "jdbc:postgresql://localhost:5432/smartfirehub"
                    user = "app"
                    password = "app"
                }
                generator.apply {
                    name = "org.jooq.codegen.DefaultGenerator"
                    database.apply {
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = "public"
                    }
                    target.apply {
                        packageName = "com.smartfirehub.jooq"
                        directory = "src/main/generated"
                    }
                }
            }
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
    useJUnitPlatform()
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "-Dnet.bytebuddy.experimental=true"
    )
}
