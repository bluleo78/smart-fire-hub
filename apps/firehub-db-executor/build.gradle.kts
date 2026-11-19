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
    // Jackson 3(tools.jackson) — webmvc 스타터가 spring-boot-starter-jackson 을 끌어오므로 별도 의존이 필요 없다(WD-793, Jackson 2 호환 모듈 제거).
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
    // Jackson 골든 시험(WD-793)이 골든 파일을 다시 쓰게 하는 스위치만 Gradle 속성(-PjacksonGoldenWrite=true)에서 시험 JVM 으로 넘긴다.
    // 평소(속성 없음)에는 비교만 한다.
    providers.gradleProperty("jacksonGoldenWrite").orNull?.let { systemProperty("jackson.golden.write", it) }
}

spotless {
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        googleJavaFormat("1.34.1")
    }
}
