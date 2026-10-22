plugins {
    java
    id("org.springframework.boot") version "3.4.1"
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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // HikariCP 직접 사용 — spring-boot-starter-jdbc 를 쓰지 않아 DataSource 자동 구성이 끼어들지 않는다(메타 DB 없음).
    implementation("com.zaxxer:HikariCP")
    // Tibero JDBC 드라이버 — Maven Central 에 없고 라이선스상 저장소에 커밋하지 않는다(*.jar 는 gitignore).
    // 개발자가 apps/firehub-db-executor/libs/ 에 tibero*.jar 를 직접 넣으면 런타임 클래스패스에 포함된다.
    // libs 가 비어 있어도 fileTree 는 빈 집합이라 빌드·테스트는 동작한다(health 는 DOWN).
    runtimeOnly(fileTree("libs") { include("tibero*.jar") })

    testImplementation("org.springframework.boot:spring-boot-starter-test")
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
