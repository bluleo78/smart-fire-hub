plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
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
    // Boot 4 에서 spring-boot-starter-web 은 spring-boot-starter-webmvc 로 이름이 바뀌었다(옛 이름은 deprecated 별칭).
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Jackson 2 유지(WD-792, Jackson 3 전환은 WD-793) — Boot 4 기본은 Jackson 3 이라 Jackson 2 ObjectMapper 빈·HTTP 변환기는 이 모듈이 준다.
    // PipelineStopClient 등이 com.fasterxml ObjectMapper 를 주입받고, 응답 JSON 모양(BigDecimal 평문 등)을 api 와 같게 유지한다.
    implementation("org.springframework.boot:spring-boot-jackson2")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // HikariCP 직접 사용 — spring-boot-starter-jdbc 를 쓰지 않아 DataSource 자동 구성이 끼어들지 않는다(메타 DB 없음).
    implementation("com.zaxxer:HikariCP")
    // Tibero JDBC 드라이버 — Maven Central 에 없고 라이선스상 저장소에 커밋하지 않는다(*.jar 는 gitignore).
    // apps/firehub-db-executor/libs/ 에 커밋된 Tibero JDBC(tibero*.jar)를 런타임 클래스패스에 포함한다.
    // libs 가 비어 있어도 fileTree 는 빈 집합이라 빌드·테스트는 동작한다(health 는 DOWN).
    runtimeOnly(fileTree("libs") { include("tibero*.jar") })

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 는 @AutoConfigureMockMvc 를 webmvc-test 모듈로 뺐다.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly(fileTree("libs") { include("tibero*.jar") })
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

spotless {
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        googleJavaFormat("1.34.1")
    }
}
