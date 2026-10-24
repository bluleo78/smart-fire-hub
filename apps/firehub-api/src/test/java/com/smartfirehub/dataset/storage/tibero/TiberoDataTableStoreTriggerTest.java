package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections.SqlWork;
import com.smartfirehub.global.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * Tibero 행 트리거(WD-77) 단위 테스트: 새 테이블에 트리거가 달리는지, 권한이 없을 때 테이블 생성은 성공하고 {@code ensureRowTrigger} 만
 * 422 로 거절하는지, 스테이징 테이블은 createTable 경로를 지나지 않아 트리거가 없다({@code
 * TiberoDmlSqlTest#createStaging_tableSeqIndexAndSequence} 가 문장 구성을 고정). 실서버 동작은 {@code
 * TiberoLiveTriggerTest} 가 본다.
 */
class TiberoDataTableStoreTriggerTest {

  private static final String S = "GENIA_T5";

  /** 실행된 DDL 문장(순서대로). */
  private final List<String> ddl = new ArrayList<>();

  /** USER_TRIGGERS 질의가 물은 트리거를 모두 "있음" 으로 돌려줄지(상태는 {@link #triggerStatus}). */
  private boolean triggerExists;

  /** {@link #triggerExists} 와 별개로 켜진(ENABLED) 채 있는 트리거 이름들 — 테이블마다 다른 상태를 흉내 낸다. */
  private final java.util.Set<String> enabledTriggers = new java.util.HashSet<>();

  /** USER_TRIGGERS 질의마다 바인드된 트리거 이름 목록. */
  private final List<List<String>> triggerQueries = new ArrayList<>();

  /** withDdl 이 연 접속 수. */
  private int ddlConnections;

  /** 있을 때 돌려줄 STATUS(ENABLED 가 아니면 없는 것과 같다). */
  private String triggerStatus = "ENABLED";

  /** 경고 로그 수집 — 테스트 출력에 스택 트레이스가 새지 않게 하고 경고 내용을 확인한다. */
  private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs;

  /** 트리거 DDL 만 던지게 하는 스위치(테이블 DDL 은 정상). */
  private boolean triggerDdlFails;

  private TiberoTriggerPrivilege privilege;
  private TiberoDataTableStore store;

  /** 슬롯 SELECT 권한 동기화(WD-78) 가짜. */
  private final com.smartfirehub.securitylevel.pythonread.TiberoPythonReadGrantSync slotGrantSync =
      mock(com.smartfirehub.securitylevel.pythonread.TiberoPythonReadGrantSync.class);

  @BeforeEach
  void setUp() throws Exception {
    TenantContext.set(5L);
    ExternalStoreConnections connections = mock(ExternalStoreConnections.class);
    when(connections.schemaOf(5L)).thenReturn(S);
    Connection conn = mock(Connection.class);
    Statement statement = mock(Statement.class);
    when(conn.createStatement()).thenReturn(statement);
    when(statement.execute(anyString()))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (triggerDdlFails && sql.startsWith("CREATE OR REPLACE TRIGGER")) {
                throw new SQLException("trigger ddl failed", "42000", -17004);
              }
              ddl.add(sql);
              return false;
            });
    when(conn.prepareStatement(anyString()))
        .thenAnswer(
            inv -> {
              PreparedStatement ps = mock(PreparedStatement.class);
              List<String> bound = new ArrayList<>();
              org.mockito.Mockito.doAnswer(
                      x -> {
                        bound.add(x.getArgument(1));
                        return null;
                      })
                  .when(ps)
                  .setString(org.mockito.ArgumentMatchers.anyInt(), anyString());
              when(ps.executeQuery())
                  .thenAnswer(
                      x -> {
                        triggerQueries.add(List.copyOf(bound));
                        return triggerRows(bound);
                      });
              return ps;
            });
    when(connections.withDdl(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any()))
        .thenAnswer(
            inv -> {
              ddlConnections++;
              try {
                return inv.<SqlWork<Object>>getArgument(1).apply(conn);
              } catch (SQLException e) {
                throw new UncategorizedSQLException("Tibero", null, e);
              }
            });
    privilege = mock(TiberoTriggerPrivilege.class);
    store =
        new TiberoDataTableStore(
            connections,
            mock(TiberoColumnTypeCache.class),
            mock(TiberoDataRowStore.class),
            mock(TiberoBulkMover.class),
            privilege,
            slotGrantSync);
    logs = new ch.qos.logback.core.read.ListAppender<>();
    logs.start();
    // 경고(스택 트레이스 포함)가 테스트 콘솔로 새지 않게 이 로거만 상위 전파를 끄고, 수집기로만 받는다.
    storeLogger().setAdditive(false);
    storeLogger().addAppender(logs);
  }

  /** 물은 트리거 중 있는 것만 (TRIGGER_NAME, STATUS) 행으로 낸다. */
  private ResultSet triggerRows(List<String> asked) throws SQLException {
    List<String[]> rows = new ArrayList<>();
    for (String name : asked) {
      if (triggerExists) {
        rows.add(new String[] {name, triggerStatus});
      } else if (enabledTriggers.contains(name)) {
        rows.add(new String[] {name, "ENABLED"});
      }
    }
    int[] at = {-1};
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenAnswer(x -> ++at[0] < rows.size());
    when(rs.getString(1)).thenAnswer(x -> rows.get(at[0])[0]);
    when(rs.getString(2)).thenAnswer(x -> rows.get(at[0])[1]);
    return rs;
  }

  private static ch.qos.logback.classic.Logger storeLogger() {
    return (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger(TiberoDataTableStore.class);
  }

  /** WARN 수준으로 남은 로그 메시지(인자를 채운 문장). */
  private List<String> warnings() {
    return logs.list.stream()
        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();
  }

  @AfterEach
  void tearDown() {
    storeLogger().detachAppender(logs);
    storeLogger().setAdditive(true);
    TenantContext.clear();
  }

  private static DatasetColumnRequest req(String name) {
    return new DatasetColumnRequest(name, name, "TEXT", null, true, false, null, false);
  }

  private List<String> triggerDdl() {
    return ddl.stream().filter(s -> s.startsWith("CREATE OR REPLACE TRIGGER")).toList();
  }

  @Test
  void createTable_withPrivilege_createsRowTriggerAfterTable() throws Exception {
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);

    store.createTable("t", List.of(req("name")));

    assertThat(triggerDdl()).containsExactly(TiberoDdlSql.createRowTrigger(S, "t"));
    // 트리거는 테이블·시퀀스가 만들어진 뒤(맨 끝)에 달린다.
    assertThat(ddl.get(ddl.size() - 1)).isEqualTo(TiberoDdlSql.createRowTrigger(S, "t"));
  }

  /** 새 테이블·클론은 커밋 후 슬롯 SELECT 동기화를 그 테이블 이름으로 예약한다(WD-78). 트리거 권한 유무와 무관하다. */
  @Test
  void createTable_andCloneTable_scheduleSlotGrantSyncAfterCommit() throws Exception {
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(false);

    store.createTable("t", List.of(req("name")));
    store.cloneTable("t", "t2", List.of(), List.of());

    org.mockito.Mockito.verify(slotGrantSync).syncTableAfterCommit("t");
    org.mockito.Mockito.verify(slotGrantSync).syncTableAfterCommit("t2");
    org.mockito.Mockito.verifyNoMoreInteractions(slotGrantSync);
  }

  /** 생성이 409 로 거부되면 만들어진 것이 없으므로 동기화를 예약하지 않는다. */
  @Test
  void createTable_failure_doesNotScheduleSlotGrantSync() throws Exception {
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);
    triggerDdlFails = false;
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> store.createTable("t", List.of(req("_updated_at"))))
        .isInstanceOf(RuntimeException.class);
    org.mockito.Mockito.verifyNoInteractions(slotGrantSync);
  }

  @Test
  void createTable_withoutPrivilege_succeedsWithoutTrigger() throws Exception {
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(false);
    List<DatasetColumnRequest> cols = List.of(req("name"));

    store.createTable("t", cols);

    // 데이터셋 생성은 막지 않는다 — 테이블 문장은 그대로 실행되고 트리거만 빠진다.
    assertThat(ddl).isEqualTo(TiberoDdlSql.createTable(S, "t", cols));
    assertThat(triggerDdl()).isEmpty();
    assertThat(warnings()).singleElement().asString().contains("CREATE TRIGGER 권한이 없어");
  }

  @Test
  void createTable_privilegeCheckFails_stillSucceedsWithoutTrigger() throws Exception {
    when(privilege.hasCreateTrigger(any(Connection.class)))
        .thenThrow(new IllegalStateException("접속 불가"));

    store.createTable("t", List.of(req("name")));

    assertThat(triggerDdl()).isEmpty();
    // 권한 확인이 깨졌다는 사실은 경고로 남는다(조용히 삼키지 않는다).
    assertThat(warnings()).singleElement().asString().contains("행 트리거 생성 실패").contains("t");
  }

  /** 권한은 있는데 트리거 DDL 자체가 던져도 테이블 생성은 성공한다(경고만 남는다). */
  @Test
  void createTable_triggerDdlThrows_stillSucceedsWithWarning() throws Exception {
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);
    triggerDdlFails = true;
    List<DatasetColumnRequest> cols = List.of(req("name"));

    store.createTable("t", cols);

    // 테이블·시퀀스 문장은 그대로 실행됐고 트리거만 빠졌다.
    assertThat(ddl).isEqualTo(TiberoDdlSql.createTable(S, "t", cols));
    assertThat(warnings()).singleElement().asString().contains("행 트리거 생성 실패");
  }

  @Test
  void cloneTable_withPrivilege_createsRowTriggerForTarget() throws Exception {
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);

    store.cloneTable(
        "src",
        "dst",
        List.of("name"),
        List.of(
            new DatasetColumnResponse(
                1L, "name", "name", "TEXT", null, true, false, null, 0, false)));

    assertThat(triggerDdl()).containsExactly(TiberoDdlSql.createRowTrigger(S, "dst"));
  }

  @Test
  void ensureRowTrigger_alreadyExists_doesNothing() throws Exception {
    triggerExists = true;

    store.ensureRowTrigger("t");

    assertThat(ddl).isEmpty();
  }

  /** DISABLED 트리거는 없는 것과 같다 — 다시 만들어(CREATE OR REPLACE) 켠다. */
  @Test
  void ensureRowTrigger_existsButDisabled_recreatesIt() throws Exception {
    triggerExists = true;
    triggerStatus = "DISABLED";
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);

    store.ensureRowTrigger("t");

    assertThat(ddl).containsExactly(TiberoDdlSql.createRowTrigger(S, "t"));
  }

  @Test
  void ensureRowTrigger_disabledWithoutPrivilege_422() throws Exception {
    triggerExists = true;
    triggerStatus = "DISABLED";
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(false);

    assertThatThrownBy(() -> store.ensureRowTrigger("t"))
        .isInstanceOf(DataStoreFeatureUnsupportedException.class);
    assertThat(ddl).isEmpty();
  }

  @Test
  void ensureRowTrigger_missingWithPrivilege_createsIt() throws Exception {
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);

    store.ensureRowTrigger("t");

    assertThat(ddl).containsExactly(TiberoDdlSql.createRowTrigger(S, "t"));
  }

  @Test
  void ensureRowTrigger_missingWithoutPrivilege_422WithGrantStatement() throws Exception {
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(false);

    assertThatThrownBy(() -> store.ensureRowTrigger("t"))
        .isInstanceOfSatisfying(
            DataStoreFeatureUnsupportedException.class,
            e -> {
              assertThat(e.status().value()).isEqualTo(422);
              assertThat(e.getMessage())
                  .isEqualTo(
                      "이 워크스페이스의 Tibero 계정에 트리거 생성 권한이 없어 이 단계를 실행할 수 없습니다. "
                          + "운영자에게 다음 문장 실행을 요청해 주세요: GRANT CREATE TRIGGER TO \"GENIA_T5\"");
            });
    assertThat(ddl).isEmpty();
  }

  // ---------- 여러 테이블 보장(한 접속) ----------

  /** 상태 확인은 IN 목록 질의 한 번, 없는 트리거만 같은 접속에서 만든다. */
  @Test
  void ensureRowTriggers_oneConnectionOneQuery_createsOnlyMissing() throws Exception {
    enabledTriggers.add(TiberoNames.triggerName(S, "orders"));
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);

    store.ensureRowTriggers(List.of("ORDERS", "SRC_ORDERS"));

    assertThat(ddlConnections).isEqualTo(1);
    assertThat(triggerQueries)
        .containsExactly(
            List.of(
                TiberoNames.triggerName(S, "orders"), TiberoNames.triggerName(S, "src_orders")));
    assertThat(ddl).containsExactly(TiberoDdlSql.createRowTrigger(S, "SRC_ORDERS"));
  }

  /** 모두 켜져 있으면 권한도 묻지 않고 아무것도 만들지 않는다. */
  @Test
  void ensureRowTriggers_allEnabled_noPrivilegeCheck() throws Exception {
    triggerExists = true;

    store.ensureRowTriggers(List.of("orders", "src_orders"));

    assertThat(ddl).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(privilege);
  }

  /** 출력은 있고 입력 하나만 없는데 권한이 없으면 아무것도 만들지 않고 운영자 안내 422 로 거부한다(증분 fail-closed). */
  @Test
  void ensureRowTriggers_oneMissingWithoutPrivilege_422_noDdl() throws Exception {
    enabledTriggers.add(TiberoNames.triggerName(S, "orders"));
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(false);

    assertThatThrownBy(() -> store.ensureRowTriggers(List.of("ORDERS", "SRC_ORDERS")))
        .isInstanceOf(DataStoreFeatureUnsupportedException.class)
        .hasMessageContaining("GRANT CREATE TRIGGER TO \"GENIA_T5\"");
    assertThat(ddl).isEmpty();
  }

  /** 대소문자만 다른 같은 테이블은 한 번만 묻고 한 번만 만든다. 빈 목록은 접속하지 않는다. */
  @Test
  void ensureRowTriggers_duplicatesCollapsed_emptyNoConnection() throws Exception {
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);

    store.ensureRowTriggers(List.of());
    assertThat(ddlConnections).isZero();

    store.ensureRowTriggers(List.of("orders", "ORDERS"));
    assertThat(triggerQueries).containsExactly(List.of(TiberoNames.triggerName(S, "orders")));
    assertThat(ddl).containsExactly(TiberoDdlSql.createRowTrigger(S, "orders"));
  }
}
