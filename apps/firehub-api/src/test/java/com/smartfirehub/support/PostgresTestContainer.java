package com.smartfirehub.support;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

/** 통합 테스트 전체가 JVM 안에서 공유하는 PostGIS + pgvector PostgreSQL 컨테이너. */
public final class PostgresTestContainer {

  public static final PostgreSQLContainer<?> INSTANCE = createContainer();

  static {
    INSTANCE.start();
  }

  private PostgresTestContainer() {}

  /** Spring, Flyway, 파이프라인 데이터소스 URL을 컨테이너 주소로 덮어쓴다. */
  public static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", INSTANCE::getJdbcUrl);
    registry.add("spring.flyway.url", INSTANCE::getJdbcUrl);
    registry.add("app.pipeline.datasource.url", INSTANCE::getJdbcUrl);
  }

  private static PostgreSQLContainer<?> createContainer() {
    // Docker 데몬의 API 버전 자동 협상이 OrbStack 에서 실패하지 않도록 지원 버전을 고정한다.
    System.setProperty("api.version", "1.41");

    String imageName =
        new ImageFromDockerfile().withDockerfile(findPostgresDockerfile()).get();

    return new PostgreSQLContainer<>(
            DockerImageName.parse(imageName).asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("smartfirehub_test")
        .withUsername("app")
        .withPassword("app")
        .withCommand("postgres", "-c", "max_connections=200");
  }

  /** Gradle 및 IDE 실행 위치에 관계없이 모노레포의 기존 PostgreSQL Dockerfile을 찾는다. */
  private static Path findPostgresDockerfile() {
    String repositoryRoot = System.getProperty("smartfirehub.repositoryRoot");
    if (repositoryRoot != null) {
      return Path.of(repositoryRoot).resolve("docker/postgres/Dockerfile");
    }

    Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (directory != null) {
      Path dockerfile = directory.resolve("docker/postgres/Dockerfile");
      if (Files.isRegularFile(dockerfile)) {
        return dockerfile;
      }
      directory = directory.getParent();
    }

    throw new IllegalStateException(
        "모노레포의 docker/postgres/Dockerfile을 찾을 수 없습니다. 실행 위치를 확인하세요.");
  }
}
