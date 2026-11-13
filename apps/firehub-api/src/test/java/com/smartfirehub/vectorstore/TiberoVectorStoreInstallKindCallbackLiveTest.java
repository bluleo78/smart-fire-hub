package com.smartfirehub.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.platformdb.PlatformDbType;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 벡터 저장소 설치 종류 기록·대조({@link VectorStoreInstallKindCallback}, ADR 0002 결정 10)의 Tibero SQL 을 실서버로
 * 확인한다.
 *
 * <p>확인하는 것은 Tibero 전용 존재 확인({@code FROM DUAL WHERE EXISTS})과 {@code system_settings} 의 {@code
 * key}·{@code value} 열 INSERT, 그리고 PK 충돌 뒤 세이브포인트 되돌리기다. 하네스의 Flyway 는 앱의 커스터마이저를 거치지 않아 기록 행이 없는
 * 상태에서 시작한다. 시나리오는 소유자 커넥션(정책 우회 — Flyway 와 같은 계정) 트랜잭션 안에서 돌고 끝나면 되돌린다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoVectorStoreInstallKindCallbackLiveTest {

  private TiberoPlatformSchema poc;
  private TiberoAccessFixtures fx;
  private long datasetId;
  private long tenantId;

  /** 다른 테넌트의 카탈로그 행 하나를 소유자로 심는다 — "데이터가 든 설치본". */
  @BeforeAll
  void seed() {
    poc = TiberoPlatformSchema.shared();
    fx = new TiberoAccessFixtures(poc);
    tenantId = fx.createTenant();
    datasetId = fx.createDataset(tenantId, "vsik-" + System.nanoTime());
  }

  @AfterAll
  void cleanup() {
    if (fx != null) {
      poc.jdbc().update("DELETE FROM dataset_embedding WHERE dataset_id = ?", datasetId);
      fx.cleanup();
    }
  }

  @Test
  void 행이_없으면_설정을_기록하고_다음부터_대조한다() throws SQLException {
    inOwnerTx(
        c -> {
          exec(
              c,
              "DELETE FROM system_settings WHERE key = '"
                  + VectorStoreInstallKindCallback.KEY
                  + "'");
          exec(c, "DELETE FROM document_chunk");
          exec(c, "DELETE FROM dataset_embedding");
          callback(VectorStoreType.QDRANT).check(c);
          assertThat(recorded(c)).isEqualTo("qdrant");
          assertThatThrownBy(() -> callback(VectorStoreType.PLATFORM).check(c))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("설치 후 바꿀 수 없습니다");
        });
  }

  @Test
  void 카탈로그_행이_있으면_platform_으로_확정한다() throws SQLException {
    inOwnerTx(
        c -> {
          exec(
              c,
              "DELETE FROM system_settings WHERE key = '"
                  + VectorStoreInstallKindCallback.KEY
                  + "'");
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO dataset_embedding (dataset_id, source_text, tenant_id) VALUES (?, ?, ?)")) {
            ps.setLong(1, datasetId);
            ps.setString(2, "설치 종류");
            ps.setLong(3, tenantId);
            ps.executeUpdate();
          }
          assertThatThrownBy(() -> callback(VectorStoreType.QDRANT).check(c))
              .isInstanceOf(IllegalStateException.class);
          assertThat(recorded(c)).isEqualTo("platform");
          callback(VectorStoreType.PLATFORM).check(c);
        });
  }

  @Test
  void 동시_기동에서_먼저_기록한_값을_따르고_트랜잭션이_계속된다() throws SQLException {
    inOwnerTx(
        c -> {
          exec(
              c,
              "DELETE FROM system_settings WHERE key = '"
                  + VectorStoreInstallKindCallback.KEY
                  + "'");
          exec(
              c,
              "INSERT INTO system_settings (key, value) VALUES ('"
                  + VectorStoreInstallKindCallback.KEY
                  + "', 'platform')");
          assertThat(VectorStoreInstallKindCallback.recordOrReadWinner(c, VectorStoreType.QDRANT))
              .isEqualTo("platform");
          assertThat(recorded(c)).isEqualTo("platform");
        });
  }

  private static VectorStoreInstallKindCallback callback(VectorStoreType type) {
    return new VectorStoreInstallKindCallback(type, PlatformDbType.TIBERO);
  }

  /** 소유자 커넥션 트랜잭션 안에서 돌리고 끝나면 되돌린다. */
  private void inOwnerTx(SqlBody body) throws SQLException {
    DataSource owner = poc.jdbc().getDataSource();
    try (Connection c = owner.getConnection()) {
      c.setAutoCommit(false);
      try {
        body.run(c);
      } finally {
        c.rollback();
        c.setAutoCommit(true);
      }
    }
  }

  private static String recorded(Connection c) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT s.value FROM system_settings s WHERE s.key = ?")) {
      ps.setString(1, VectorStoreInstallKindCallback.KEY);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
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
