package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  /** 기존 테이블의 ID 기본값과 트리거 이벤트를 각각 모사한다. */
  private boolean hasIdDefault = true;

  /** 테이블 시퀀스가 있는가({@code ALL_OBJECTS} 의 SEQUENCE 조회 응답). 그 밖의 객체 조회는 언제나 있음으로 답한다. */
  private boolean sequenceExists = true;

  private String triggerEvent = "UPDATE";

  /** 경고 로그 수집 — 테스트 출력에 스택 트레이스가 새지 않게 하고 경고 내용을 확인한다. */
  private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs;

  /** 트리거 DDL 만 던지게 하는 스위치(테이블 DDL 은 정상). */
  private boolean triggerDdlFails;

  /**
   * ID 기본값 DDL({@code ALTER TABLE … MODIFY ("ID" DEFAULT …)})만 던지게 하는 스위치 — 다른 세션의 행 잠금(resource
   * busy)을 흉내 낸다.
   */
  private boolean idDefaultDdlFails;

  /** 준비된(prepareStatement) 모든 질의 문장 — 카탈로그 조회 수·종류를 단정한다(리뷰: 흔한 경로의 조회 수). */
  private final List<String> preparedQueries = new ArrayList<>();

  /** 트리거별 {@code TRIGGERING_EVENT}(없으면 {@link #triggerEvent}) — 옛 모양·새 모양 테이블이 섞인 경우를 흉내 낸다. */
  private final Map<String, String> eventByTrigger = new HashMap<>();

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
              if (idDefaultDdlFails && sql.contains("MODIFY (\"ID\" DEFAULT")) {
                throw new SQLException("resource busy", "61000", -14010);
              }
              ddl.add(sql);
              return false;
            });
    when(conn.prepareStatement(anyString()))
        .thenAnswer(
            inv -> {
              String query = inv.getArgument(0);
              preparedQueries.add(query);
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
                        if (query.contains("FROM ALL_OBJECTS")) {
                          boolean exists = !"SEQUENCE".equals(bound.get(2)) || sequenceExists;
                          ResultSet rs = mock(ResultSet.class);
                          when(rs.next()).thenReturn(exists, false);
                          when(rs.getLong(1)).thenReturn(1L);
                          return rs;
                        }
                        if (query.contains("DATA_DEFAULT")) {
                          ResultSet rs = mock(ResultSet.class);
                          when(rs.next()).thenReturn(true, false);
                          when(rs.getString(1))
                              .thenAnswer(
                                  y ->
                                      hasIdDefault
                                          ? "\"GENIA_T5\".\""
                                              + TiberoNames.sequenceName(S, bound.get(0))
                                              + "\".NEXTVAL"
                                          : null);
                          return rs;
                        }
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
    when(rs.getString(3))
        .thenAnswer(x -> eventByTrigger.getOrDefault(rows.get(at[0])[0], triggerEvent));
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

  /** 옛 INSERT+UPDATE 트리거를 올릴 때 ID 기본값을 먼저 설정한 뒤 트리거를 바꾼다. 다시 호출하면 DDL 이 없다. */
  @Test
  void ensureRowTrigger_legacyShape_upgradesInSafeOrder() throws Exception {
    triggerExists = true;
    triggerEvent = "INSERT, UPDATE";
    hasIdDefault = false;
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);

    store.ensureRowTrigger("t");

    assertThat(ddl)
        .containsExactly(TiberoDdlSql.setIdDefault(S, "t"), TiberoDdlSql.createRowTrigger(S, "t"));
  }

  /**
   * 옛 트리거가 켜져 있는데 CREATE TRIGGER 권한이 없으면 422 로 막지 않고 옛 트리거를 그대로 쓴다(리뷰 M-2 Ruling). ID 기본값은 테이블 소유자
   * 권한만으로 둘 수 있어 먼저 설정하고, 교체하지 못했다는 경고를 남긴다. 실서버 표기({@code INSERT OR UPDATE})를 쓴다.
   */
  @Test
  void ensureRowTrigger_legacyEnabledWithoutPrivilege_keepsLegacyTrigger() throws Exception {
    triggerExists = true;
    triggerEvent = "INSERT OR UPDATE";
    hasIdDefault = false;
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(false);

    store.ensureRowTrigger("t");

    assertThat(ddl).containsExactly(TiberoDdlSql.setIdDefault(S, "t"));
    assertThat(warnings()).anySatisfy(w -> assertThat(w).contains("옛 행 트리거"));
  }

  /** 옛 트리거로 버티는 테이블과 트리거가 아예 없는 테이블이 섞이면, 없는 쪽 때문에 기존처럼 422 다(DDL 은 기본값 설정뿐). */
  @Test
  void ensureRowTriggers_legacyPlusMissingWithoutPrivilege_422() throws Exception {
    enabledTriggers.add(TiberoNames.triggerName(S, "orders"));
    triggerEvent = "INSERT OR UPDATE";
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(false);

    assertThatThrownBy(() -> store.ensureRowTriggers(List.of("ORDERS", "SRC_ORDERS")))
        .isInstanceOf(DataStoreFeatureUnsupportedException.class);
    assertThat(triggerDdl()).isEmpty();
  }

  /** {@code TRIGGERING_EVENT} 표기가 공백·대소문자로 조금 달라도 UPDATE 전용이면 켜진 트리거로 보고 DDL 을 반복하지 않는다(리뷰 I-2). */
  @Test
  void ensureRowTrigger_updateEventWithSpacesOrCase_isEnabled_noDdl() throws Exception {
    triggerExists = true;
    triggerEvent = " update ";

    store.ensureRowTrigger("t");

    assertThat(ddl).isEmpty();
    verifyNoInteractions(privilege);
  }

  /**
   * 시퀀스가 없는 테이블은 ID 기본값을 묶지 않는다 — 없는 시퀀스로의 MODIFY 는 실서버에서 ALTER 시점에 -8034 로 거부되어 트리거 보장까지 막는다 (리뷰
   * M-1). 트리거는 그대로 만든다.
   */
  @Test
  void ensureRowTrigger_noSequence_skipsIdDefault_stillCreatesTrigger() throws Exception {
    hasIdDefault = false;
    sequenceExists = false;
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);

    store.ensureRowTrigger("t");

    assertThat(ddl).containsExactly(TiberoDdlSql.createRowTrigger(S, "t"));
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
    verifyNoInteractions(privilege);
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

  // ---------- 옛 모양 올리기: ID 기본값 실패 내성·조회 수(최종 리뷰) ----------

  /**
   * 흔한 경로(새 모양 UPDATE 전용 트리거가 켜진 테이블)는 WD-233 이전처럼 {@code USER_TRIGGERS} 한 번만 묻는다 — ID 기본값
   * 확인({@code USER_TAB_COLUMNS})·시퀀스 확인({@code ALL_OBJECTS})을 하지 않는다. 새 모양 테이블의 ID 기본값은 생성 DDL 과
   * 보관·복원 재바인딩이 책임진다.
   */
  @Test
  void ensureRowTriggers_allUpdateOnly_onlyOneTriggerQuery_noCatalogLookup() throws Exception {
    triggerExists = true;

    store.ensureRowTriggers(List.of("orders", "src_orders"));

    assertThat(preparedQueries).containsExactly(TiberoCatalog.triggerStatusQuery(2));
    assertThat(ddl).isEmpty();
    verifyNoInteractions(privilege);
  }

  /** 새 모양 테이블과 옛 모양 테이블이 섞이면 ID 기본값 확인은 옛 모양 테이블에만 한다. */
  @Test
  void ensureRowTriggers_mixed_idDefaultLookupOnlyForLegacyTable() throws Exception {
    triggerExists = true;
    eventByTrigger.put(TiberoNames.triggerName(S, "src_orders"), "INSERT OR UPDATE");
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);

    store.ensureRowTriggers(List.of("ORDERS", "SRC_ORDERS"));

    assertThat(preparedQueries.stream().filter(q -> q.contains("DATA_DEFAULT")).count())
        .isEqualTo(1);
    assertThat(ddl).containsExactly(TiberoDdlSql.createRowTrigger(S, "SRC_ORDERS"));
  }

  /**
   * 옛 트리거가 켜진 테이블에서 ID 기본값 DDL 이 다른 세션의 행 잠금 등으로 실패하면 쓰기를 막지 않는다 — ID 는 옛 트리거가 채운다. 경고만 남기고, 기본값이
   * 설정되지 않았으므로 옛 트리거를 UPDATE 전용으로 바꾸지 않는다(순서 보장). 다음 호출에서 기본값이 서면 그때 바꾼다.
   */
  @Test
  void ensureRowTrigger_legacy_idDefaultDdlFails_keepsLegacyTrigger_retriesNextCall()
      throws Exception {
    triggerExists = true;
    triggerEvent = "INSERT OR UPDATE";
    hasIdDefault = false;
    idDefaultDdlFails = true;
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);

    store.ensureRowTrigger("t");

    assertThat(triggerDdl()).isEmpty();
    assertThat(warnings()).anySatisfy(w -> assertThat(w).contains("ID 기본값").contains("t"));

    idDefaultDdlFails = false;
    store.ensureRowTrigger("t");

    assertThat(ddl)
        .containsExactly(TiberoDdlSql.setIdDefault(S, "t"), TiberoDdlSql.createRowTrigger(S, "t"));
  }

  /** 옛 트리거인데 테이블 시퀀스가 없어 ID 기본값을 둘 수 없으면 옛 트리거를 바꾸지 않는다(기본값이 실제로 선 뒤에만 교체). */
  @Test
  void ensureRowTrigger_legacy_noSequence_keepsLegacyTrigger() throws Exception {
    triggerExists = true;
    triggerEvent = "INSERT OR UPDATE";
    hasIdDefault = false;
    sequenceExists = false;
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);

    store.ensureRowTrigger("t");

    assertThat(ddl).isEmpty();
  }

  /** 트리거도 기본값도 없는 테이블은 기존대로 — ID 기본값 DDL 실패가 그대로 올라간다(트리거를 만들지 않는다). */
  @Test
  void ensureRowTrigger_missing_idDefaultDdlFails_propagates() throws Exception {
    hasIdDefault = false;
    idDefaultDdlFails = true;
    when(privilege.hasCreateTrigger(any(Connection.class))).thenReturn(true);

    assertThatThrownBy(() -> store.ensureRowTrigger("t")).isInstanceOf(RuntimeException.class);
    assertThat(triggerDdl()).isEmpty();
  }

  /**
   * 트리거 모양 분류(WD-233 simplify D) — 표기 차이(공백·대소문자·{@code UPDATE OF} 꼬리)에 흔들리지 않고, UPDATE 를 포함하지 않는
   * 표기(INSERT 전용·알 수 없음)와 없음(null)은 모두 MISSING 이다.
   */
  @Test
  void classify_triggerShapeFromTriggeringEvent() {
    assertThat(TiberoCatalog.classify("UPDATE")).isEqualTo(TiberoCatalog.TriggerShape.CURRENT);
    assertThat(TiberoCatalog.classify(" update of \"_UPDATED_AT\" "))
        .isEqualTo(TiberoCatalog.TriggerShape.CURRENT);
    assertThat(TiberoCatalog.classify("INSERT OR UPDATE"))
        .isEqualTo(TiberoCatalog.TriggerShape.LEGACY);
    assertThat(TiberoCatalog.classify("insert or update"))
        .isEqualTo(TiberoCatalog.TriggerShape.LEGACY);
    assertThat(TiberoCatalog.classify("INSERT")).isEqualTo(TiberoCatalog.TriggerShape.MISSING);
    assertThat(TiberoCatalog.classify("DELETE")).isEqualTo(TiberoCatalog.TriggerShape.MISSING);
    assertThat(TiberoCatalog.classify(null)).isEqualTo(TiberoCatalog.TriggerShape.MISSING);
  }
}
