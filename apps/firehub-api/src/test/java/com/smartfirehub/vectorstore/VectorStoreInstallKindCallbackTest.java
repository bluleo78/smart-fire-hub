package com.smartfirehub.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.global.platformdb.PlatformDbType;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 벡터 저장소 설치 종류 기록·대조(ADR 0002 결정 10, {@link VectorStoreInstallKindCallback})를 실제 PG 로 확인한다.
 *
 * <p>모든 시나리오는 소유자 커넥션의 트랜잭션 안에서 돌고 끝나면 되돌린다. 공유 테스트 DB 의 기록 행(기동 때 이미 platform 으로 남았다)과 다른 테스트의
 * 카탈로그 행을 건드리지 않기 위해서다. 빈 설치본은 같은 트랜잭션에서 행을 지워 흉내 낸다.
 *
 * <p>소유자 커넥션이어야 하는 이유도 고정한다. 런타임 계정(테넌트 정책 대상)으로 테넌트 문맥 없이 세면 0행이라, 데이터가 든 설치본을 빈 설치본으로 잘못 보고
 * qdrant 를 기록하게 된다.
 */
class VectorStoreInstallKindCallbackTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private DatasetEmbeddingRepository embeddingRepository;

  private final DataSource ownerDataSource = PostgresTestContainer.platformOwnerDataSource();

  private long tenant;
  private DocFixture doc;

  /** 다른 테넌트의 카탈로그 행 하나 — "데이터가 든 설치본" 을 확실히 만든다. */
  @BeforeEach
  void seed() {
    tenant = TenantRlsTestSupport.createActiveTenant(dsl, "vsik");
    inTenantFixture(tenant, () -> doc = EmbeddingTestFixtures.createDocumentDataset(dsl, "vsik"));
    TenantContext.runScoped(
        tenant, () -> embeddingRepository.upsertSourceText(doc.datasetId(), "설치 종류"));
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenant);
    TenantRlsTestSupport.deleteTenants(dsl, tenant);
    TenantRlsTestSupport.deleteUser(dsl, doc.userId());
  }

  @Test
  void 기동이_이미_platform_을_기록해_두었다() throws SQLException {
    inOwnerTx(c -> assertThat(recorded(c)).isEqualTo("platform"));
  }

  @Test
  void 기록이_같으면_통과하고_다르면_기동을_멈춘다() throws SQLException {
    inOwnerTx(
        c -> {
          callback(VectorStoreType.PLATFORM).check(c);
          assertThatThrownBy(() -> callback(VectorStoreType.QDRANT).check(c))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("설치 후 바꿀 수 없습니다")
              .hasMessageContaining("platform")
              .hasMessageContaining("qdrant");
        });
  }

  /** 업그레이드하면서 qdrant 를 같이 켜도, 데이터가 든 설치본은 platform 으로 확정되고 기동이 멈춘다. */
  @Test
  void 기록이_없고_카탈로그_행이_있으면_platform_으로_확정한다() throws SQLException {
    inOwnerTx(
        c -> {
          deleteRecord(c);
          assertThatThrownBy(() -> callback(VectorStoreType.QDRANT).check(c))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("설치 후 바꿀 수 없습니다");
          assertThat(recorded(c)).isEqualTo("platform");
        });
  }

  @Test
  void 기록도_행도_없으면_지금_설정을_기록한다() throws SQLException {
    inOwnerTx(
        c -> {
          deleteRecord(c);
          exec(c, "DELETE FROM document_chunk");
          exec(c, "DELETE FROM dataset_embedding");
          callback(VectorStoreType.QDRANT).check(c);
          assertThat(recorded(c)).isEqualTo("qdrant");
          // 다음 기동부터는 기록과 대조한다.
          assertThatThrownBy(() -> callback(VectorStoreType.PLATFORM).check(c))
              .isInstanceOf(IllegalStateException.class);
        });
  }

  /** 여러 파드가 동시에 처음 뜨면 한쪽 INSERT 가 PK 충돌이다 — 진 쪽은 먼저 기록된 값을 읽어 대조한다(트랜잭션은 계속 쓸 수 있다). */
  @Test
  void 동시_기동에서_먼저_기록한_값을_따른다() throws SQLException {
    inOwnerTx(
        c -> {
          // 판정은 기록 없음에서 시작했지만, 넣기 직전에 다른 파드가 platform 을 넣었다고 가정한다.
          deleteRecord(c);
          exec(
              c,
              "INSERT INTO system_settings (key, value) VALUES ('"
                  + VectorStoreInstallKindCallback.KEY
                  + "', 'platform')");
          assertThat(VectorStoreInstallKindCallback.recordOrReadWinner(c, VectorStoreType.QDRANT))
              .isEqualTo("platform");
          // 세이브포인트로 실패한 문장만 되돌렸다 — 같은 트랜잭션이 계속 동작한다.
          assertThat(recorded(c)).isEqualTo("platform");
        });
  }

  /** 런타임 계정으로 테넌트 문맥 없이 세면 정책 때문에 0행이다 — 그래서 콜백은 소유자 커넥션(Flyway)에서 돈다. */
  @Test
  void 런타임_계정은_테넌트_문맥_없이_행을_보지_못한다() throws SQLException {
    // 앱 DSL 은 런타임 계정(app_tenant)이다. 테넌트 문맥이 없으면 정책 술어가 아무 행도 통과시키지 않는다.
    assertThat(dsl.fetchOne("SELECT count(*) FROM dataset_embedding").get(0, Long.class)).isZero();
    inOwnerTx(
        c -> {
          try (Statement st = c.createStatement();
              ResultSet rs = st.executeQuery("SELECT count(*) FROM dataset_embedding")) {
            rs.next();
            assertThat(rs.getLong(1)).isPositive();
          }
        });
  }

  private static VectorStoreInstallKindCallback callback(VectorStoreType type) {
    return new VectorStoreInstallKindCallback(type, PlatformDbType.POSTGRES);
  }

  /** 소유자 커넥션 트랜잭션 안에서 돌리고 끝나면 되돌린다. */
  private void inOwnerTx(SqlBody body) throws SQLException {
    try (Connection c = ownerDataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        body.run(c);
      } finally {
        c.rollback();
      }
    }
  }

  private static String recorded(Connection c) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT value FROM system_settings WHERE key = ?")) {
      ps.setString(1, VectorStoreInstallKindCallback.KEY);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private static void deleteRecord(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("DELETE FROM system_settings WHERE key = ?")) {
      ps.setString(1, VectorStoreInstallKindCallback.KEY);
      ps.executeUpdate();
    }
  }

  private static void exec(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  @FunctionalInterface
  private interface SqlBody {
    void run(Connection c) throws SQLException;
  }
}
