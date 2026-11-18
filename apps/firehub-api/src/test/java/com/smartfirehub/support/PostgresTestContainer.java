package com.smartfirehub.support;

import java.time.Duration;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 통합 테스트 전체가 JVM 안에서 공유하는 PostGIS + pgvector PostgreSQL 컨테이너. */
public final class PostgresTestContainer {

  public static final PostgreSQLContainer<?> INSTANCE = newContainer("smartfirehub_test");

  static {
    INSTANCE.start();
  }

  /**
   * 분리 모드 회귀 실행 여부. {@code separateDataStoreTest} Gradle 태스크만 {@code
   * smartfirehub.separateDataStore=true} 를 켠다 — 기본 test 는 별칭 모드(같은 DB) 그대로다.
   */
  public static final boolean SEPARATE_DATA_STORE =
      Boolean.getBoolean("smartfirehub.separateDataStore");

  private PostgresTestContainer() {}

  /**
   * Spring, Flyway URL을 컨테이너 주소로 덮어쓴다. 파이프라인 데이터소스 URL 은 application-test.yml 에서 {@code
   * firehub.data-store.postgres.url}(기본 spring.datasource.url)을 따르므로 여기서 등록하지 않는다 — 등록하면 분리 모드
   * 테스트({@link DataStorePostgresTestContainer#registerSeparateDataStoreProperties})의 같은 키 오버라이드와 순서
   * 경합한다(WD-494).
   */
  public static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", INSTANCE::getJdbcUrl);
    registry.add("spring.flyway.url", INSTANCE::getJdbcUrl);
    // separateDataStoreTest 태스크(WD-494)는 같은 테스트를 분리 모드로 다시 돌린다 — 시스템 속성이 켜져 있으면 데이터 저장소
    // 키를 두 번째 컨테이너로 돌려, 기존 통합 테스트가 그대로 "플랫폼 DB ≠ 데이터 저장소 DB" 회귀가 된다.
    if (SEPARATE_DATA_STORE) {
      DataStorePostgresTestContainer.registerSeparateDataStoreProperties(registry);
    }
  }

  /**
   * 플랫폼 DB 슈퍼유저({@code app}) DataSource. 스키마 소유자 풀({@code firehub.data-store.postgres.owner-url})은
   * 분리 모드에서 데이터 저장소 DB 를 가리키므로, 플랫폼 테이블(public)에 DDL·RLS 우회 조작을 해야 하는 테스트는 이것을 쓴다(WD-494). 별칭 모드에서는
   * 같은 DB·같은 자격증명이라 동작이 같다.
   */
  public static DataSource platformOwnerDataSource() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(INSTANCE.getJdbcUrl());
    ds.setUser(INSTANCE.getUsername());
    ds.setPassword(INSTANCE.getPassword());
    return ds;
  }

  /**
   * 데이터 저장소 테이블·테넌트 롤이 실제로 있는 DB 의 JDBC URL — 별칭 모드면 이 컨테이너, 분리 모드 회귀면 {@link
   * DataStorePostgresTestContainer}. 테스트가 슬롯 롤·슈퍼유저로 직접 붙어 데이터 저장소를 볼 때 쓴다(두 컨테이너 자격증명은 같다).
   */
  public static String dataStoreJdbcUrl() {
    return SEPARATE_DATA_STORE
        ? DataStorePostgresTestContainer.INSTANCE.getJdbcUrl()
        : INSTANCE.getJdbcUrl();
  }

  /**
   * 모노레포 Dockerfile 이미지로 PostgreSQL 컨테이너를 만든다(시작은 하지 않음). 분리 모드 데이터 저장소용 두 번째 컨테이너({@link
   * DataStorePostgresTestContainer})도 같은 이미지·자격증명을 쓰도록 공유한다.
   *
   * @param databaseName 컨테이너 안에 만들 데이터베이스 이름
   */
  static PostgreSQLContainer<?> newContainer(String databaseName) {
    // Docker 데몬의 API 버전 자동 협상이 OrbStack 에서 실패하지 않도록 지원 버전을 고정한다.
    System.setProperty("api.version", "1.41");

    String imageName = testImageName();

    return new PostgreSQLContainer<>(
            DockerImageName.parse(imageName).asCompatibleSubstituteFor("postgres"))
        .withDatabaseName(databaseName)
        .withUsername("app")
        .withPassword("app")
        .withCommand("postgres", "-c", "max_connections=200")
        // 기동 대기 상한을 기본 60초에서 5분으로 늘린다(WD-784). 여러 세션이 한 호스트를 나눠 쓰는 push 게이트(load 10~15)에서
        // PostGIS 초기화가 60초를 넘겨 「ready to accept connections」 대기가 끝나면, 그 포크의 시험 전체(약 6천 건)가 한꺼번에
        // 실패했다. 정상일 때는 몇 초 안에 떠 상한이 늘어도 시간이 더 들지 않는다.
        .withStartupTimeout(Duration.ofMinutes(5));
  }

  /**
   * Gradle 이 준비해 넘긴 시험용 PostgreSQL 이미지 이름(WD-798). 이미지 이름 규칙(Dockerfile 내용 해시)·빌드
   * 인자(OS_PATCH=skip)·빌드 여부 판단은 build.gradle.kts 의 {@code TestPostgresImage} 한 곳에 있다 — 시험 JVM 이 직접
   * 빌드하면 포크마다 빌드가 돌고 규칙이 두 곳으로 갈라지기 때문이다(WD-794 때는 여기와 generateJooq 가 같은 규칙을 따로 들고 있었다).
   *
   * @throws IllegalStateException Gradle 밖(IDE 의 JUnit 직접 실행 등)이라 속성이 없을 때
   */
  private static String testImageName() {
    String imageName = System.getProperty("smartfirehub.testPostgresImage");
    if (imageName == null || imageName.isBlank()) {
      throw new IllegalStateException(
          "시스템 속성 smartfirehub.testPostgresImage 가 없습니다. 시험은 Gradle 로 실행하세요(./gradlew test) — "
              + "시험용 PostgreSQL 이미지는 Gradle 이 준비해 이 속성으로 넘깁니다. "
              + "IDE 에서 돌릴 때는 Gradle 로 실행하도록 설정하세요. "
              + "Gradle 로 돌렸는데도 이 오류면 Docker 를 쓸 수 없어 이미지를 준비하지 못한 것입니다(Gradle 경고 참고).");
    }
    return imageName;
  }
}
