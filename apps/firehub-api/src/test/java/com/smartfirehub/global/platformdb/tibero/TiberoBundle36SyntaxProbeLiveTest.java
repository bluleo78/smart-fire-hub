package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.support.tibero.TiberoTestEvidence;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 묶음 ③(보안 등급·접근 허용 등)·⑥(파이프라인·트리거) MyBatis Tibero 판이 기댈 문법을 공유 개발 Tibero 에서 먼저 확인하는 스파이크(WD-711).
 *
 * <ul>
 *   <li>(a) {@code UNIQUE (tenant_id, rank) DEFERRABLE} 에서 {@code SET CONSTRAINT[S] … DEFERRED} 뒤 두
 *       행 rank 교환이 커밋되는지 — 표기 변형·같은 트랜잭션(autocommit off) 필요 여부·음수 임시값 2단계를 차례로 기록한다.
 *   <li>(b) {@code CASE WHEN type='API' THEN JSON_VALUE(config,'$.tokenHash') END} 유일 함수 색인에 같은 식
 *       WHERE 조회가 행을 찾는지.
 *   <li>(c) 그 CASE 식 유일 색인 대상 {@code MERGE … ON (<같은 식>)} 이 중복 없이 한 행만 남기는지(접근 허용 CASE 색인 모양 포함).
 *   <li>(d) {@code MERGE … WHEN NOT MATCHED} 만으로 다건 「충돌 무시」 삽입이 되는지.
 * </ul>
 *
 * <p>탐침 테이블은 정책이 없는 방언 확인용이라 소유자 계정으로 이름 접미사를 붙여 만들고 끝에서 지운다. 결과는 증거 파일에 {@code B36PROBE} 줄로 남는다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class TiberoBundle36SyntaxProbeLiveTest {

  /** 이번 실행의 탐침 객체 이름 접미사(다른 실행과 겹치지 않게). */
  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

  private final String rankTable = "fhp_b36_rank_" + suffix;
  private final String trigTable = "fhp_b36_trig_" + suffix;
  private final String grantTable = "fhp_b36_grant_" + suffix;
  private final String ignoreTable = "fhp_b36_ign_" + suffix;

  private final List<String> createdTables = new ArrayList<>();

  private TiberoPlatformSchema poc;
  private JdbcTemplate owner;
  private DataSource ownerDs;

  @BeforeAll
  void setUp() {
    poc = TiberoPlatformSchema.shared();
    owner = poc.jdbc();
    ownerDs = owner.getDataSource();
  }

  /** 이번 실행이 만든 탐침 테이블만 지운다. */
  @AfterAll
  void tearDown() {
    for (String t : createdTables) {
      try {
        owner.execute("DROP TABLE " + t);
      } catch (RuntimeException e) {
        TiberoTestEvidence.write(
            "B36PROBE", "drop " + t + " 실패 " + TiberoTestEvidence.rootMessage(e));
      }
    }
  }

  private void createTable(String name, String ddl) {
    owner.execute(ddl);
    createdTables.add(name);
  }

  // ---------------------------------------------------------------- (a) DEFERRABLE

  /** 순위 테이블 — 보안 등급과 같은 {@code UNIQUE (tenant_id, rank) DEFERRABLE}. 행 두 개(1, 2)로 되돌려 놓는다. */
  private void resetRankTable() {
    if (!createdTables.contains(rankTable)) {
      createTable(
          rankTable,
          "CREATE TABLE "
              + rankTable
              + " (id NUMBER(19) NOT NULL, tenant_id NUMBER(19) NOT NULL, rank NUMBER(10) NOT NULL,"
              + " name VARCHAR2(50 CHAR) NOT NULL, CONSTRAINT pk_"
              + rankTable
              + " PRIMARY KEY (id), CONSTRAINT uq_"
              + rankTable
              + " UNIQUE (tenant_id, rank) DEFERRABLE)");
    }
    owner.update("DELETE FROM " + rankTable);
    owner.update("INSERT INTO " + rankTable + " (id, tenant_id, rank, name) VALUES (1, 9, 1, 'A')");
    owner.update("INSERT INTO " + rankTable + " (id, tenant_id, rank, name) VALUES (2, 9, 2, 'B')");
  }

  private String ranks() {
    return owner
        .queryForList("SELECT id || ':' || rank FROM " + rankTable + " ORDER BY id", String.class)
        .toString();
  }

  /**
   * 시도 하나 — autocommit 을 끄고 {@code pre} 문장들을 실행한 뒤 두 행 rank 를 교환(한 문장 CASE 또는 두 문장)하고 커밋한다. 결과(성공 시
   * 최종 rank, 실패 시 오류)를 돌려준다.
   */
  private String trySwap(
      String label, boolean autocommitOff, List<String> pre, boolean oneStatement) {
    resetRankTable();
    String result;
    try (Connection c = ownerDs.getConnection()) {
      c.setAutoCommit(!autocommitOff);
      try (Statement st = c.createStatement()) {
        for (String p : pre) st.execute(p);
        if (oneStatement) {
          st.executeUpdate(
              "UPDATE "
                  + rankTable
                  + " SET rank = CASE id WHEN 1 THEN 2 ELSE 1 END WHERE tenant_id = 9");
        } else {
          st.executeUpdate("UPDATE " + rankTable + " SET rank = 2 WHERE id = 1");
          st.executeUpdate("UPDATE " + rankTable + " SET rank = 1 WHERE id = 2");
        }
        if (autocommitOff) c.commit();
        result = "OK ranks=" + ranks();
      } catch (SQLException e) {
        try {
          if (autocommitOff) c.rollback();
        } catch (SQLException ignore) {
          // 롤백 실패는 기록만 하지 않는다 — 다음 시도가 테이블을 되돌린다.
        }
        result = "ERROR code=" + e.getErrorCode() + " " + TiberoTestEvidence.rootMessage(e);
      }
    } catch (SQLException e) {
      result = "CONN ERROR " + TiberoTestEvidence.rootMessage(e);
    }
    TiberoTestEvidence.write("B36PROBE", "(a) " + label + " → " + result);
    return result;
  }

  @Test
  @DisplayName("(a) DEFERRABLE 유일 제약 — SET CONSTRAINT 표기·트랜잭션 범위·음수 임시값")
  void deferrableUniqueSwap() {
    String cn = "uq_" + rankTable;

    // 기준선 — 지연 없이 교환하면 막혀야 한다(막히지 않으면 제약이 즉시 검사가 아니라는 뜻).
    String plainTwo = trySwap("A0 지연 없음, 두 문장 교환", true, List.of(), false);
    String plainOne = trySwap("A0b 지연 없음, 한 문장 CASE 교환", true, List.of(), true);

    // 같은 트랜잭션(autocommit off)에서 SET CONSTRAINT 단수.
    String a1 =
        trySwap(
            "A1 SET CONSTRAINT 단수 + autocommit off + 두 문장",
            true,
            List.of("SET CONSTRAINT " + cn + " DEFERRED"),
            false);
    String a2 =
        trySwap(
            "A2 SET CONSTRAINTS 복수 + autocommit off + 두 문장",
            true,
            List.of("SET CONSTRAINTS " + cn + " DEFERRED"),
            false);
    String a3 =
        trySwap(
            "A3 SET CONSTRAINTS ALL + autocommit off + 두 문장",
            true,
            List.of("SET CONSTRAINTS ALL DEFERRED"),
            false);
    // autocommit on 이면 SET 이 자기 문장 트랜잭션에서 끝나 이어지는 UPDATE 에 안 걸려야 한다.
    String a4 =
        trySwap(
            "A4 SET CONSTRAINT 단수 + autocommit ON + 두 문장",
            false,
            List.of("SET CONSTRAINT " + cn + " DEFERRED"),
            false);
    String a5 =
        trySwap(
            "A5 SET CONSTRAINTS 복수 + autocommit ON + 두 문장",
            false,
            List.of("SET CONSTRAINTS " + cn + " DEFERRED"),
            false);

    // 커밋 뒤 지연 상태가 다음 트랜잭션으로 새는지 — 같은 커넥션에서 SET 후 커밋, 다음 트랜잭션에서 교환.
    String leak = "?";
    resetRankTable();
    try (Connection c = ownerDs.getConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute("SET CONSTRAINT " + cn + " DEFERRED");
        c.commit();
        st.executeUpdate("UPDATE " + rankTable + " SET rank = 2 WHERE id = 1");
        st.executeUpdate("UPDATE " + rankTable + " SET rank = 1 WHERE id = 2");
        c.commit();
        leak = "지연이 커밋 뒤에도 유지됨 ranks=" + ranks();
      } catch (SQLException e) {
        c.rollback();
        leak = "지연은 커밋 시 풀림 code=" + e.getErrorCode() + " " + TiberoTestEvidence.rootMessage(e);
      }
    } catch (SQLException e) {
      leak = "CONN ERROR " + TiberoTestEvidence.rootMessage(e);
    }
    TiberoTestEvidence.write("B36PROBE", "(a) A6 SET 후 커밋, 다음 트랜잭션 교환 → " + leak);

    // 한 문장 CASE 교환 + 지연.
    String a7 =
        trySwap(
            "A7 SET CONSTRAINT + 한 문장 CASE 교환",
            true,
            List.of("SET CONSTRAINT " + cn + " DEFERRED"),
            true);

    // 지연 없는 대안 — 음수 임시값 2단계(rank 열에 CHECK 가 없으니 가능).
    resetRankTable();
    String neg;
    try (Connection c = ownerDs.getConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.executeUpdate("UPDATE " + rankTable + " SET rank = -rank WHERE tenant_id = 9");
        st.executeUpdate(
            "UPDATE "
                + rankTable
                + " SET rank = CASE id WHEN 1 THEN 2 ELSE 1 END WHERE tenant_id = 9");
        c.commit();
        neg = "OK ranks=" + ranks();
      } catch (SQLException e) {
        c.rollback();
        neg = "ERROR code=" + e.getErrorCode() + " " + TiberoTestEvidence.rootMessage(e);
      }
    } catch (SQLException e) {
      neg = "CONN ERROR " + TiberoTestEvidence.rootMessage(e);
    }
    TiberoTestEvidence.write("B36PROBE", "(a) A8 음수 임시값 2단계(-rank 후 교환) → " + neg);

    // SET CONSTRAINT 계열이 문법 오류라 Oracle 식 세션 지정과 INITIALLY DEFERRED 선언을 추가로 시험한다.
    String a10 =
        trySwap(
            "A10 ALTER SESSION SET CONSTRAINTS = DEFERRED + autocommit off + 두 문장",
            true,
            List.of("ALTER SESSION SET CONSTRAINTS = DEFERRED"),
            false);
    String a11 =
        trySwap(
            "A11 ALTER SESSION SET CONSTRAINTS = DEFERRED + autocommit off + 한 문장 CASE",
            true,
            List.of("ALTER SESSION SET CONSTRAINTS = DEFERRED"),
            true);
    // 세션 지정이 커넥션에 남는지(풀 재사용 위험) 확인 — 같은 커넥션에서 되돌리지 않고 다시 교환.
    String a12 = "?";
    resetRankTable();
    try (Connection c = ownerDs.getConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute("ALTER SESSION SET CONSTRAINTS = DEFERRED");
        st.executeUpdate("UPDATE " + rankTable + " SET rank = 2 WHERE id = 1");
        st.executeUpdate("UPDATE " + rankTable + " SET rank = 1 WHERE id = 2");
        c.commit();
        st.executeUpdate("UPDATE " + rankTable + " SET rank = 1 WHERE id = 1");
        st.executeUpdate("UPDATE " + rankTable + " SET rank = 2 WHERE id = 2");
        c.commit();
        a12 = "OK 세션 지정이 커밋 뒤에도 유지 ranks=" + ranks();
        st.execute("ALTER SESSION SET CONSTRAINTS = IMMEDIATE");
      } catch (SQLException e) {
        c.rollback();
        a12 = "ERROR code=" + e.getErrorCode() + " " + TiberoTestEvidence.rootMessage(e);
      }
    } catch (SQLException e) {
      a12 = "CONN ERROR " + TiberoTestEvidence.rootMessage(e);
    }
    TiberoTestEvidence.write("B36PROBE", "(a) A12 ALTER SESSION 지연 후 커밋을 넘겨 두 번 교환 → " + a12);
    // 지연 중 중복을 남기고 커밋하면 커밋 시점에 거절되는지.
    String a13;
    resetRankTable();
    try (Connection c = ownerDs.getConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute("ALTER SESSION SET CONSTRAINTS = DEFERRED");
        st.executeUpdate("UPDATE " + rankTable + " SET rank = 2 WHERE id = 1");
        c.commit();
        a13 = "OK(예상 밖) 중복 rank 로 커밋됨 ranks=" + ranks();
      } catch (SQLException e) {
        c.rollback();
        a13 = "커밋 시점 거절 code=" + e.getErrorCode() + " " + TiberoTestEvidence.rootMessage(e);
      } finally {
        try (Statement st2 = c.createStatement()) {
          st2.execute("ALTER SESSION SET CONSTRAINTS = IMMEDIATE");
        } catch (SQLException ignore) {
          // 연결을 닫으면 세션이 사라지므로 복구 실패는 무시한다.
        }
      }
    } catch (SQLException e) {
      a13 = "CONN ERROR " + TiberoTestEvidence.rootMessage(e);
    }
    TiberoTestEvidence.write("B36PROBE", "(a) A13 ALTER SESSION 지연 중 중복 커밋 → " + a13);
    TiberoTestEvidence.write("B36PROBE", "(a) 요약2 a10=" + a10 + " | a11=" + a11);

    // 연결 후 제약 상태도 기록 — 의도하지 않은 커밋 지연 검사 오류 표기(커밋 시점 위반) 확인.
    String viol;
    resetRankTable();
    try (Connection c = ownerDs.getConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute("SET CONSTRAINT " + cn + " DEFERRED");
        st.executeUpdate("UPDATE " + rankTable + " SET rank = 2 WHERE id = 1");
        c.commit();
        viol = "OK(예상 밖) — 중복 rank 로 커밋됨";
      } catch (SQLException e) {
        c.rollback();
        viol = "커밋 시점 위반 거절 code=" + e.getErrorCode() + " " + TiberoTestEvidence.rootMessage(e);
      }
    } catch (SQLException e) {
      viol = "CONN ERROR " + TiberoTestEvidence.rootMessage(e);
    }
    TiberoTestEvidence.write("B36PROBE", "(a) A9 지연 중 중복을 남기고 커밋 → " + viol);

    TiberoTestEvidence.write(
        "B36PROBE",
        "(a) 요약 plainTwo="
            + plainTwo
            + " | plainOne="
            + plainOne
            + " | a1="
            + a1
            + " | a2="
            + a2
            + " | a3="
            + a3
            + " | a4="
            + a4
            + " | a5="
            + a5
            + " | a7="
            + a7
            + " | neg="
            + neg);
    // 최소 단정: 지연 없는 두 문장 교환은 막혀야 하고, 음수 임시값 또는 SET CONSTRAINT 중 하나는 교환에 성공해야 한다(아니면 대안이 없다).
    assertThat(plainTwo).startsWith("ERROR");
    assertThat(List.of(a1, a2, a3, a7, a10, a11, plainOne, neg)).anyMatch(r -> r.startsWith("OK"));
  }

  // ---------------------------------------------------------------- (b)(c) 함수 색인

  /** 트리거 모양 테이블 — pipeline_trigger 의 두 CASE 유일 색인과 같다. */
  private void ensureTrigTable() {
    if (createdTables.contains(trigTable)) return;
    createTable(
        trigTable,
        "CREATE TABLE "
            + trigTable
            + " (id NUMBER(19) GENERATED BY DEFAULT AS IDENTITY NOT NULL, trigger_type VARCHAR2(30 CHAR) NOT NULL,"
            + " name VARCHAR2(100 CHAR) NOT NULL, config JSON DEFAULT '{}' NOT NULL, hits NUMBER(10) DEFAULT 0 NOT NULL,"
            + " CONSTRAINT pk_"
            + trigTable
            + " PRIMARY KEY (id))");
    owner.execute(
        "CREATE UNIQUE INDEX uq_tok_"
            + suffix
            + " ON "
            + trigTable
            + " (CASE WHEN trigger_type = 'API' THEN JSON_VALUE(config, '$.tokenHash') END)");
    owner.execute(
        "CREATE UNIQUE INDEX uq_web_"
            + suffix
            + " ON "
            + trigTable
            + " (CASE WHEN trigger_type = 'WEBHOOK' THEN JSON_VALUE(config, '$.webhookId') END)");
  }

  @Test
  @DisplayName("(b) CASE+JSON_VALUE 유일 함수 색인과 같은 식 WHERE 조회")
  void functionalIndexLookup() {
    ensureTrigTable();
    owner.update("DELETE FROM " + trigTable);
    owner.update(
        "INSERT INTO " + trigTable + " (trigger_type, name, config) VALUES ('API', 'a1', ?)",
        "{\"tokenHash\":\"h1\"}");
    owner.update(
        "INSERT INTO " + trigTable + " (trigger_type, name, config) VALUES ('API', 'a2', ?)",
        "{\"tokenHash\":\"h2\"}");
    owner.update(
        "INSERT INTO " + trigTable + " (trigger_type, name, config) VALUES ('WEBHOOK', 'w1', ?)",
        "{\"webhookId\":\"h1\"}");
    owner.update(
        "INSERT INTO " + trigTable + " (trigger_type, name, config) VALUES ('SCHEDULE', 's1', ?)",
        "{\"cron\":\"x\"}");
    // 다른 유형이 같은 값을 가져도 색인에 걸리지 않아야 한다(위 w1 의 h1 이 이미 증거).

    List<String> byCase =
        owner.queryForList(
            "SELECT name FROM "
                + trigTable
                + " WHERE CASE WHEN trigger_type = 'API' THEN JSON_VALUE(config, '$.tokenHash') END = ? ORDER BY id",
            String.class,
            "h1");
    TiberoTestEvidence.write("B36PROBE", "(b) 같은 CASE 식 WHERE 바인드 h1 → " + byCase);
    List<String> byCaseMissing =
        owner.queryForList(
            "SELECT name FROM "
                + trigTable
                + " WHERE CASE WHEN trigger_type = 'API' THEN JSON_VALUE(config, '$.tokenHash') END = ?",
            String.class,
            "nope");
    TiberoTestEvidence.write("B36PROBE", "(b) 없는 값 → " + byCaseMissing);
    List<String> byPlain =
        owner.queryForList(
            "SELECT name FROM "
                + trigTable
                + " WHERE trigger_type = 'API' AND JSON_VALUE(config, '$.tokenHash') = ? ORDER BY id",
            String.class,
            "h1");
    TiberoTestEvidence.write("B36PROBE", "(b) 평범한 AND 술어 → " + byPlain);
    List<String> webhook =
        owner.queryForList(
            "SELECT name FROM "
                + trigTable
                + " WHERE CASE WHEN trigger_type = 'WEBHOOK' THEN JSON_VALUE(config, '$.webhookId') END = ?",
            String.class,
            "h1");
    TiberoTestEvidence.write("B36PROBE", "(b) 웹훅 CASE 식 → " + webhook);
    // 색인 사용 여부(실행 계획)는 서버 권한·형식이 다를 수 있어 기록만 시도한다.
    try {
      owner.execute(
          "EXPLAIN PLAN FOR SELECT name FROM "
              + trigTable
              + " WHERE CASE WHEN trigger_type = 'API' THEN JSON_VALUE(config, '$.tokenHash') END = 'h1'");
      TiberoTestEvidence.write("B36PROBE", "(b) EXPLAIN PLAN 실행됨");
    } catch (RuntimeException e) {
      TiberoTestEvidence.write(
          "B36PROBE", "(b) EXPLAIN PLAN 불가 " + TiberoTestEvidence.rootMessage(e));
    }
    // 유일 색인 위반(API 토큰 해시 중복)·유형별 격리.
    String dup;
    try {
      owner.update(
          "INSERT INTO " + trigTable + " (trigger_type, name, config) VALUES ('API', 'a3', ?)",
          "{\"tokenHash\":\"h1\"}");
      dup = "OK(예상 밖)";
    } catch (RuntimeException e) {
      dup = "거절 " + TiberoTestEvidence.rootMessage(e);
    }
    TiberoTestEvidence.write("B36PROBE", "(b) API 토큰 해시 중복 삽입 → " + dup);
    String dupNull;
    try {
      owner.update(
          "INSERT INTO " + trigTable + " (trigger_type, name, config) VALUES ('SCHEDULE', 's2', ?)",
          "{\"cron\":\"y\"}");
      owner.update(
          "INSERT INTO " + trigTable + " (trigger_type, name, config) VALUES ('SCHEDULE', 's3', ?)",
          "{\"cron\":\"z\"}");
      dupNull = "OK(NULL 색인 키는 중복 허용)";
    } catch (RuntimeException e) {
      dupNull = "거절 " + TiberoTestEvidence.rootMessage(e);
    }
    TiberoTestEvidence.write("B36PROBE", "(b) 색인 키가 NULL 인 행 여러 개 → " + dupNull);

    assertThat(byCase).containsExactly("a1");
    assertThat(byCaseMissing).isEmpty();
    assertThat(webhook).containsExactly("w1");
    assertThat(dup).startsWith("거절");
  }

  /** MERGE 한 번(바인드 순서: 토큰 해시, 새 이름). 영향 행 수를 돌려준다. */
  private static final String MERGE_TRIG_TEMPLATE =
      "MERGE INTO %s t USING (SELECT ? AS tok FROM dual) s"
          + " ON (CASE WHEN t.trigger_type = 'API' THEN JSON_VALUE(t.config, '$.tokenHash') END = s.tok)"
          + " WHEN MATCHED THEN UPDATE SET t.hits = t.hits + 1"
          + " WHEN NOT MATCHED THEN INSERT (trigger_type, name, config) VALUES ('API', ?, ?)";

  @Test
  @DisplayName("(c) CASE 식 유일 색인 대상 MERGE — 중복 없이 한 행")
  void mergeOnCaseExpression() throws Exception {
    ensureTrigTable();
    owner.update("DELETE FROM " + trigTable);
    String sql = String.format(MERGE_TRIG_TEMPLATE, trigTable);
    int first = owner.update(sql, "m1", "mrg", "{\"tokenHash\":\"m1\"}");
    int second = owner.update(sql, "m1", "mrg", "{\"tokenHash\":\"m1\"}");
    Integer rows =
        owner.queryForObject(
            "SELECT COUNT(*) FROM "
                + trigTable
                + " WHERE CASE WHEN trigger_type = 'API' THEN JSON_VALUE(config, '$.tokenHash') END = 'm1'",
            Integer.class);
    Integer hits =
        owner.queryForObject(
            "SELECT hits FROM " + trigTable + " WHERE name = 'mrg'", Integer.class);
    TiberoTestEvidence.write(
        "B36PROBE",
        "(c) MERGE(JSON CASE) 첫 호출 영향="
            + first
            + " 두 번째 영향="
            + second
            + " 행수="
            + rows
            + " hits="
            + hits);

    // 접근 허용 모양 — 두 CASE 열 색인(dataset_id, role_id).
    if (!createdTables.contains(grantTable)) {
      createTable(
          grantTable,
          "CREATE TABLE "
              + grantTable
              + " (id NUMBER(19) GENERATED BY DEFAULT AS IDENTITY NOT NULL, dataset_id NUMBER(19) NOT NULL,"
              + " user_id NUMBER(19), role_id NUMBER(19), CONSTRAINT pk_"
              + grantTable
              + " PRIMARY KEY (id),"
              + " CONSTRAINT ck_"
              + grantTable
              + " CHECK ((user_id IS NULL AND role_id IS NOT NULL) OR (user_id IS NOT NULL AND role_id IS NULL)))");
      owner.execute(
          "CREATE UNIQUE INDEX uq_gr_"
              + suffix
              + " ON "
              + grantTable
              + " (CASE WHEN dataset_id IS NOT NULL AND role_id IS NOT NULL THEN dataset_id END,"
              + " CASE WHEN dataset_id IS NOT NULL AND role_id IS NOT NULL THEN role_id END)");
      owner.execute(
          "CREATE UNIQUE INDEX uq_gu_"
              + suffix
              + " ON "
              + grantTable
              + " (CASE WHEN dataset_id IS NOT NULL AND user_id IS NOT NULL THEN dataset_id END,"
              + " CASE WHEN dataset_id IS NOT NULL AND user_id IS NOT NULL THEN user_id END)");
    }
    owner.update("DELETE FROM " + grantTable);
    String grantMerge =
        "MERGE INTO "
            + grantTable
            + " t USING (SELECT ? AS ds, ? AS rid FROM dual) s"
            + " ON (CASE WHEN t.dataset_id IS NOT NULL AND t.role_id IS NOT NULL THEN t.dataset_id END = s.ds"
            + " AND CASE WHEN t.dataset_id IS NOT NULL AND t.role_id IS NOT NULL THEN t.role_id END = s.rid)"
            + " WHEN NOT MATCHED THEN INSERT (dataset_id, role_id) VALUES (s.ds, s.rid)";
    int g1 = owner.update(grantMerge, 5, 7);
    int g2 = owner.update(grantMerge, 5, 7);
    Integer grantRows = owner.queryForObject("SELECT COUNT(*) FROM " + grantTable, Integer.class);
    TiberoTestEvidence.write(
        "B36PROBE", "(c) MERGE(접근 허용 CASE, 역할) 첫=" + g1 + " 둘째=" + g2 + " 행수=" + grantRows);
    // 사용자 허용 모양(같은 색인 둘 다 있는 테이블에서 user 열 MERGE).
    String userMerge =
        "MERGE INTO "
            + grantTable
            + " t USING (SELECT ? AS ds, ? AS usr FROM dual) s"
            + " ON (CASE WHEN t.dataset_id IS NOT NULL AND t.user_id IS NOT NULL THEN t.dataset_id END = s.ds"
            + " AND CASE WHEN t.dataset_id IS NOT NULL AND t.user_id IS NOT NULL THEN t.user_id END = s.usr)"
            + " WHEN NOT MATCHED THEN INSERT (dataset_id, user_id) VALUES (s.ds, s.usr)";
    int u1 = owner.update(userMerge, 5, 7);
    int u2 = owner.update(userMerge, 5, 7);
    Integer allRows = owner.queryForObject("SELECT COUNT(*) FROM " + grantTable, Integer.class);
    TiberoTestEvidence.write(
        "B36PROBE", "(c) MERGE(접근 허용 CASE, 사용자) 첫=" + u1 + " 둘째=" + u2 + " 총행수=" + allRows);

    // 두 세션 동시 MERGE — 중복 키 오류가 나는지(재시도 규칙 근거).
    owner.update("DELETE FROM " + grantTable);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<String>> fs = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      fs.add(
          pool.submit(
              () -> {
                try (Connection c = ownerDs.getConnection()) {
                  c.setAutoCommit(false);
                  try (PreparedStatement ps = c.prepareStatement(grantMerge)) {
                    ps.setLong(1, 11);
                    ps.setLong(2, 22);
                    go.await();
                    int n = ps.executeUpdate();
                    Thread.sleep(300);
                    c.commit();
                    return "ok n=" + n;
                  } catch (SQLException e) {
                    c.rollback();
                    return "ERROR code="
                        + e.getErrorCode()
                        + " "
                        + TiberoTestEvidence.rootMessage(e);
                  }
                }
              }));
    }
    go.countDown();
    List<String> outs = new ArrayList<>();
    for (Future<String> f : fs) outs.add(f.get(60, TimeUnit.SECONDS));
    pool.shutdownNow();
    Integer concRows = owner.queryForObject("SELECT COUNT(*) FROM " + grantTable, Integer.class);
    TiberoTestEvidence.write("B36PROBE", "(c) 두 세션 동시 MERGE(같은 키) → " + outs + " 최종행수=" + concRows);

    assertThat(rows).isEqualTo(1);
    assertThat(hits).isEqualTo(1);
    assertThat(grantRows).isEqualTo(1);
    assertThat(allRows).isEqualTo(2);
    assertThat(concRows).isEqualTo(1);
  }

  // ---------------------------------------------------------------- (d) 충돌 무시 다건 삽입

  @Test
  @DisplayName("(d) MERGE WHEN NOT MATCHED 만으로 다건 충돌 무시 삽입")
  void mergeInsertIgnoreMany() {
    if (!createdTables.contains(ignoreTable)) {
      createTable(
          ignoreTable,
          "CREATE TABLE "
              + ignoreTable
              + " (dataset_id NUMBER(19) NOT NULL, role_id NUMBER(19) NOT NULL, note VARCHAR2(20 CHAR),"
              + " CONSTRAINT pk_"
              + ignoreTable
              + " PRIMARY KEY (dataset_id, role_id))");
    }
    owner.update("DELETE FROM " + ignoreTable);
    owner.update(
        "INSERT INTO " + ignoreTable + " (dataset_id, role_id, note) VALUES (1, 1, 'old')");
    // 한 문장 다건: 원본 두 줄은 이미 있고(1,1) 한 줄은 새것(1,2), 한 줄은 새것(2,1).
    String many =
        "MERGE INTO "
            + ignoreTable
            + " t USING ("
            + "SELECT 1 AS d, 1 AS r FROM dual UNION ALL SELECT 1, 2 FROM dual UNION ALL SELECT 2, 1 FROM dual) s"
            + " ON (t.dataset_id = s.d AND t.role_id = s.r)"
            + " WHEN NOT MATCHED THEN INSERT (dataset_id, role_id, note) VALUES (s.d, s.r, 'new')";
    int n = owner.update(many);
    List<String> rows =
        owner.queryForList(
            "SELECT dataset_id || ',' || role_id || ',' || note FROM "
                + ignoreTable
                + " ORDER BY dataset_id, role_id",
            String.class);
    TiberoTestEvidence.write(
        "B36PROBE", "(d) 한 문장 다건 MERGE NOT MATCHED 만 → 영향=" + n + " 행=" + rows);

    // 원본에 같은 새 키가 두 번 들어 있으면(소스 내 중복) 어떻게 되는지.
    owner.update("DELETE FROM " + ignoreTable);
    String dupSrc =
        "MERGE INTO "
            + ignoreTable
            + " t USING ("
            + "SELECT 3 AS d, 3 AS r FROM dual UNION ALL SELECT 3, 3 FROM dual) s"
            + " ON (t.dataset_id = s.d AND t.role_id = s.r)"
            + " WHEN NOT MATCHED THEN INSERT (dataset_id, role_id, note) VALUES (s.d, s.r, 'x')";
    String dupResult;
    try {
      int m = owner.update(dupSrc);
      Integer cnt = owner.queryForObject("SELECT COUNT(*) FROM " + ignoreTable, Integer.class);
      dupResult = "OK 영향=" + m + " 행수=" + cnt;
    } catch (RuntimeException e) {
      dupResult = "ERROR " + TiberoTestEvidence.rootMessage(e);
    }
    TiberoTestEvidence.write("B36PROBE", "(d) 소스 내 같은 새 키 두 줄 → " + dupResult);

    // 배치(JDBC addBatch)로 MERGE 를 건별로 — 일부 이미 있음.
    owner.update("DELETE FROM " + ignoreTable);
    owner.update(
        "INSERT INTO " + ignoreTable + " (dataset_id, role_id, note) VALUES (1, 1, 'old')");
    String one =
        "MERGE INTO "
            + ignoreTable
            + " t USING (SELECT ? AS d, ? AS r FROM dual) s"
            + " ON (t.dataset_id = s.d AND t.role_id = s.r)"
            + " WHEN NOT MATCHED THEN INSERT (dataset_id, role_id, note) VALUES (s.d, s.r, 'b')";
    int[][] batch = {{1, 1}, {1, 2}, {2, 1}};
    int[] counts =
        owner
            .batchUpdate(
                one,
                java.util.Arrays.asList(batch),
                batch.length,
                (ps, row) -> {
                  ps.setLong(1, row[0]);
                  ps.setLong(2, row[1]);
                })[0];
    Integer batchRows = owner.queryForObject("SELECT COUNT(*) FROM " + ignoreTable, Integer.class);
    TiberoTestEvidence.write(
        "B36PROBE",
        "(d) 건별 MERGE 배치 → 영향=" + java.util.Arrays.toString(counts) + " 행수=" + batchRows);

    assertThat(rows).containsExactly("1,1,old", "1,2,new", "2,1,new");
    assertThat(n).isEqualTo(2);
    assertThat(batchRows).isEqualTo(3);
  }
}
