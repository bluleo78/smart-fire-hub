package com.smartfirehub.vectorstore;

import com.smartfirehub.global.platformdb.PlatformDbType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 설치본의 벡터 저장소 종류를 플랫폼 DB 에 한 번 기록하고, 이후 기동마다 설정과 대조한다(ADR 0002 결정 10 — 설치 후 바꾸기 금지).
 *
 * <p>규칙은 다음과 같다.
 *
 * <ul>
 *   <li>기록({@code system_settings} 의 {@value #KEY})이 있으면 설정값과 같아야 한다. 다르면 기동을 멈춘다.
 *   <li>기록이 없고 {@code dataset_embedding}·{@code document_chunk} 에 행이 하나라도 있으면 {@code platform} 으로
 *       확정해 기록한다. 업그레이드하면서 qdrant 설정을 같이 켜도 데이터가 든 설치본은 통과하지 못하게 하려는 것이다.
 *   <li>기록도 행도 없으면 지금 설정값을 기록한다.
 * </ul>
 *
 * <p>왜 Flyway {@code AFTER_MIGRATE} 콜백인가. 이유는 세 가지다.
 *
 * <ul>
 *   <li>마이그레이션이 끝난 직후, 웹 서버가 요청을 받기 전에 돈다. 실패하면 기동이 멈추고 트래픽을 한 번도 받지 않는다. {@code ApplicationRunner}
 *       는 웹 서버가 뜬 뒤라 잠깐이라도 요청을 받는다.
 *   <li>Flyway 커넥션은 소유자 계정이라 테넌트 정책(RLS)을 받지 않는다. PG 는 FORCE 를 쓰지 않아 소유자가 우회하고, Tibero 정책 함수는 소유자
 *       세션에 술어를 붙이지 않는다. 런타임 계정으로 세면 테넌트 문맥이 없어 0행이 되고, 데이터가 든 설치본을 빈 설치본으로 잘못 판정해 결정 10 을 무력화한다.
 *   <li>Spring 빈이 아닌 커넥션 하나로 충분하다.
 * </ul>
 *
 * <p>여러 파드가 동시에 처음 뜰 때도 안전하다. 기록은 "없으면 넣고 다시 읽기"다. 다른 파드가 먼저 넣어 PK 충돌이 나면 그 파드가 넣은 값을 읽어 대조한다.
 */
public class VectorStoreInstallKindCallback extends BaseCallback {

  private static final Logger log = LoggerFactory.getLogger(VectorStoreInstallKindCallback.class);

  /** 설치 종류를 기록하는 {@code system_settings} 키. */
  public static final String KEY = "vector_store.install_type";

  private final VectorStoreType configured;
  private final PlatformDbType platformDb;

  /**
   * @param configured 이번 기동의 벡터 저장소 설정값
   * @param platformDb 플랫폼 DB 종류(존재 확인 SQL 이 DB 마다 조금 다르다)
   */
  public VectorStoreInstallKindCallback(VectorStoreType configured, PlatformDbType platformDb) {
    this.configured = configured;
    this.platformDb = platformDb;
  }

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.AFTER_MIGRATE;
  }

  @Override
  public boolean canHandleInTransaction(Event event, Context context) {
    return true;
  }

  @Override
  public String getCallbackName() {
    return "vectorStoreInstallKind";
  }

  @Override
  public void handle(Event event, Context context) {
    try {
      check(context.getConnection());
    } catch (SQLException e) {
      throw new IllegalStateException("벡터 저장소 설치 종류를 확인하지 못했습니다", e);
    }
  }

  /**
   * 기록을 읽거나 처음 기록하고 설정과 대조한다. 테스트가 직접 부르도록 package-private 이다.
   *
   * @throws IllegalStateException 기록과 설정이 다를 때(기동 중단)
   */
  void check(Connection connection) throws SQLException {
    String recorded = readRecorded(connection);
    if (recorded == null) {
      VectorStoreType decided =
          hasEmbeddingRows(connection) ? VectorStoreType.PLATFORM : configured;
      recorded = recordOrReadWinner(connection, decided);
      log.info("벡터 저장소 설치 종류를 기록했습니다: {}", recorded);
    }
    if (!configured.name().equalsIgnoreCase(recorded)) {
      throw new IllegalStateException(
          VectorStoreType.PROPERTY
              + " 가 설치 때 기록된 값("
              + recorded.toLowerCase(java.util.Locale.ROOT)
              + ")과 다릅니다(설정 "
              + configured.name().toLowerCase(java.util.Locale.ROOT)
              + "). 벡터 저장소 종류는 설치 후 바꿀 수 없습니다(ADR 0002 결정 10)");
    }
  }

  /** 기록된 값. 없으면 null. */
  private static String readRecorded(Connection connection) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT s.value FROM system_settings s WHERE s.key = ?")) {
      ps.setString(1, KEY);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  /** 카탈로그나 청크 행이 하나라도 있는가. 소유자 커넥션이라 모든 테넌트의 행을 본다. 전체 수를 세지 않고 존재만 확인한다(큰 설치본의 기동 시간을 늘리지 않게). */
  private boolean hasEmbeddingRows(Connection connection) throws SQLException {
    String sql =
        platformDb == PlatformDbType.TIBERO
            ? "SELECT COUNT(*) FROM DUAL WHERE EXISTS (SELECT 1 FROM dataset_embedding)"
                + " OR EXISTS (SELECT 1 FROM document_chunk)"
            : "SELECT CASE WHEN EXISTS (SELECT 1 FROM dataset_embedding)"
                + " OR EXISTS (SELECT 1 FROM document_chunk) THEN 1 ELSE 0 END";
    try (PreparedStatement ps = connection.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      return rs.next() && rs.getInt(1) > 0;
    }
  }

  /**
   * {@code decided} 를 기록한다. 다른 파드가 먼저 기록해 PK 충돌이 나면 그 값을 돌려준다. 트랜잭션 안이면 세이브포인트로 실패한 문장만 되돌린다(PG 는
   * 실패한 문장 뒤 트랜잭션 전체가 막힌다).
   */
  static String recordOrReadWinner(Connection connection, VectorStoreType decided)
      throws SQLException {
    boolean inTx = !connection.getAutoCommit();
    Savepoint sp = inTx ? connection.setSavepoint() : null;
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO system_settings (key, value, description) VALUES (?, ?, ?)")) {
      ps.setString(1, KEY);
      ps.setString(2, decided.name().toLowerCase(java.util.Locale.ROOT));
      ps.setString(3, "벡터 저장소 설치 종류(ADR 0002 결정 10) — 설치 후 바꿀 수 없다");
      ps.executeUpdate();
      // 세이브포인트는 풀지 않는다 — Tibero JDBC 는 releaseSavepoint 를 지원하지 않고(JDBC-90201, 실측), 트랜잭션이 끝나면 함께
      // 사라진다.
      return decided.name().toLowerCase(java.util.Locale.ROOT);
    } catch (SQLException e) {
      if (sp != null) connection.rollback(sp);
      String winner = readRecorded(connection);
      if (winner == null) {
        throw e; // 충돌이 아닌 실패 — 그대로 올린다
      }
      return winner;
    }
  }
}
