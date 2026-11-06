package com.smartfirehub.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 분리 모드({@code firehub.data-store.postgres.separate=true}) 테스트용 두 번째 PostgreSQL 컨테이너(WD-494).
 *
 * <p>왜 따로 두는가: 분리 모드에서는 플랫폼 DB(첫 컨테이너)와 데이터 저장소가 다른 DB 다. 같은 컨테이너의 같은 DB 를 가리키면 "데이터 저장소 행이 플랫폼 DB
 * 에 쓰였다" 같은 배선 결함을 테스트가 구분하지 못한다. 이미지·자격증명(app/app 슈퍼유저)은 {@link PostgresTestContainer} 와 같고 DB 이름만
 * 다르다.
 *
 * <p>컨테이너는 비어 있는 채로 둔다 — {@code data} 스키마·{@code app_tenant} 롤 등은 분리 모드 컨텍스트가 기동하며 데이터 저장소 Flyway
 * 기준선({@code db/datastore-postgres})과 롤 비밀번호 동기화로 만든다(WD-494 Task 4). 여기서 손으로 만들면 기준선의 누락을 가린다.
 */
public final class DataStorePostgresTestContainer {

  /** 데이터 저장소 런타임 롤 비밀번호 — 분리 모드 풀은 {@code app_tenant} 로 접속한다(Global Constraints: 롤 이름 고정). */
  public static final String RUNTIME_PASSWORD = "app_tenant";

  public static final PostgreSQLContainer<?> INSTANCE =
      PostgresTestContainer.newContainer("smartfirehub_datastore");

  static {
    INSTANCE.start();
  }

  private DataStorePostgresTestContainer() {}

  /**
   * 분리 모드 키를 켜고 데이터 저장소 연결(런타임·소유자·파이프라인 URL)을 두 번째 컨테이너로 돌린다. 플랫폼 DB 키({@code spring.datasource.*}
   * ·{@code spring.flyway.*})는 건드리지 않는다 — 그쪽은 {@link
   * PostgresTestContainer#registerDatabaseProperties} 가 첫 컨테이너로 둔다.
   */
  public static void registerSeparateDataStoreProperties(DynamicPropertyRegistry registry) {
    registry.add("firehub.data-store.postgres.separate", () -> "true");
    registry.add("firehub.data-store.postgres.url", INSTANCE::getJdbcUrl);
    registry.add("firehub.data-store.postgres.password", () -> RUNTIME_PASSWORD);
    registry.add("firehub.data-store.postgres.owner-url", INSTANCE::getJdbcUrl);
    registry.add("firehub.data-store.postgres.owner-username", INSTANCE::getUsername);
    registry.add("firehub.data-store.postgres.owner-password", INSTANCE::getPassword);
    registry.add("app.pipeline.datasource.url", INSTANCE::getJdbcUrl);
  }

  /** 두 번째 컨테이너에 슈퍼유저(app)로 접속한다 — 테스트의 검증 조회·정리용. */
  public static Connection openSuperuserConnection() throws SQLException {
    return DriverManager.getConnection(
        INSTANCE.getJdbcUrl(), INSTANCE.getUsername(), INSTANCE.getPassword());
  }
}
