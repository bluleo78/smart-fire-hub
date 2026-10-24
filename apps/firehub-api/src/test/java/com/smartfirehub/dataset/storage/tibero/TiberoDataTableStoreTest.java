package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.ColumnStatsResponse;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections.SqlWork;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** JDBC 객체를 가짜로 만들어 실행 SQL 의 순서·커넥션 종류·보상 동작을 검증한다. 실서버는 쓰지 않는다. */
class TiberoDataTableStoreTest {

  private static final String S = "GENIA_T5";

  private ExternalStoreConnections connections;
  private Statement statement;

  /** 실행된 DDL(withDdl) 문장 순서. */
  private final List<String> ddl = new ArrayList<>();

  /** 실행된 DML 연결(withDml) 호출 횟수. */
  private int dmlCalls;

  /** 이 조건이 참인 문장은 실행 시 SQLException 을 던진다. */
  private Predicate<String> failWhen = sql -> false;

  private int failCode = 0;
  private String failMessage = "boom";

  /** 카탈로그 질의(ALL_CONSTRAINTS)가 "제약 있음" 을 돌려줄지. */
  private boolean constraintExists;

  /** 카탈로그 질의(ALL_TAB_COLUMNS)가 돌려줄 행: {COLUMN_NAME, DATA_TYPE, DATA_LENGTH, PRECISION, SCALE}. */
  private final List<Object[]> columnRows = new ArrayList<>();

  /** 카탈로그 질의(ALL_IND_COLUMNS)가 돌려줄 옛 PK 인덱스 컬럼(물리 이름, 키 순서). */
  private final List<String> indexColumnRows = new ArrayList<>();

  /** 이 저장소 클래스의 로그를 모은다(ERROR 로그 검증용). */
  private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs;

  /** 실행된 카탈로그 질의(prepareStatement) 순서. */
  private final List<String> catalogQueries = new ArrayList<>();

  private TiberoColumnTypeCache columnTypeCache;
  private TiberoDataTableStore store;

  @BeforeEach
  void setUp() throws Exception {
    TenantContext.set(5L);
    connections = mock(ExternalStoreConnections.class);
    when(connections.schemaOf(5L)).thenReturn(S);

    Connection ddlConn = mock(Connection.class);
    statement = mock(Statement.class);
    when(ddlConn.createStatement()).thenReturn(statement);
    when(ddlConn.prepareStatement(anyString()))
        .thenAnswer(inv -> catalogStatement(inv.getArgument(0)));
    when(statement.execute(anyString()))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              ddl.add(sql);
              if (failWhen.test(sql)) {
                throw new SQLException(failMessage, "42000", failCode);
              }
              return false;
            });

    when(connections.withDdl(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any()))
        .thenAnswer(inv -> runWork(inv.getArgument(1), ddlConn));
    columnTypeCache = mock(TiberoColumnTypeCache.class);
    store =
        new TiberoDataTableStore(
            connections,
            columnTypeCache,
            mock(TiberoDataRowStore.class),
            mock(TiberoBulkMover.class),
            mock(TiberoTriggerPrivilege.class),
            mock(com.smartfirehub.securitylevel.pythonread.TiberoPythonReadGrantSync.class));
    logs = new ch.qos.logback.core.read.ListAppender<>();
    logs.start();
    storeLogger().addAppender(logs);
  }

  private static ch.qos.logback.classic.Logger storeLogger() {
    return (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger(TiberoDataTableStore.class);
  }

  /** ERROR 수준으로 남은 로그 메시지(인자를 채운 문장). */
  private List<String> errorLogs() {
    return logs.list.stream()
        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();
  }

  /** 카탈로그 질의용 가짜 PreparedStatement — 제약 존재 여부와 컬럼 타입 행을 돌려준다. */
  private PreparedStatement catalogStatement(String sql) throws SQLException {
    catalogQueries.add(sql);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(ps.executeQuery()).thenReturn(rs);
    if (sql.contains("ALL_CONSTRAINTS")) {
      when(rs.next()).thenReturn(constraintExists);
      return ps;
    }
    if (sql.contains("ALL_IND_COLUMNS")) {
      int[] at = {-1};
      when(rs.next()).thenAnswer(inv -> ++at[0] < indexColumnRows.size());
      when(rs.getString(1)).thenAnswer(inv -> indexColumnRows.get(at[0]));
      return ps;
    }
    int[] cur = {-1};
    boolean[] lastNull = {false};
    when(rs.next()).thenAnswer(inv -> ++cur[0] < columnRows.size());
    when(rs.wasNull()).thenAnswer(inv -> lastNull[0]);
    when(rs.getString(org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(
            inv -> {
              int col = inv.getArgument(0);
              Object[] row = columnRows.get(cur[0]);
              return col <= row.length ? (String) row[col - 1] : null;
            });
    when(rs.getInt(org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(
            inv -> {
              int col = inv.getArgument(0);
              Object[] row = columnRows.get(cur[0]);
              Object v = col <= row.length ? row[col - 1] : null;
              lastNull[0] = v == null;
              return v == null ? 0 : ((Number) v).intValue();
            });
    return ps;
  }

  /** 실제 구현처럼 SQLException 을 Spring 예외로 감싸 실행한다. */
  private static Object runWork(SqlWork<Object> work, Connection conn) {
    try {
      return work.apply(conn);
    } catch (SQLException e) {
      throw new UncategorizedSQLException("Tibero", null, e);
    }
  }

  @AfterEach
  void tearDown() {
    storeLogger().detachAppender(logs);
    TenantContext.clear();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private static DatasetColumnRequest req(String name, String type) {
    return new DatasetColumnRequest(name, name, type, null, true, false, null, false);
  }

  private void complete(int status) {
    List<TransactionSynchronization> syncs =
        new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
    for (TransactionSynchronization s : syncs) {
      if (status == TransactionSynchronization.STATUS_COMMITTED) {
        s.afterCommit();
      }
      s.afterCompletion(status);
    }
  }

  @Test
  void backend_isTibero() {
    assertThat(store.backend()).isEqualTo(DataStoreBackend.TIBERO);
  }

  @Test
  void createTable_runsStatementsOnDdlConnection_andRegistersRollbackDrop() {
    TransactionSynchronizationManager.initSynchronization();
    List<DatasetColumnRequest> cols = List.of(req("name", "TEXT"));
    store.createTable("t", cols);

    // 기존 테이블을 미리 지우지 않는다(A4) — 생성 문장만 순서대로 나온다.
    assertThat(ddl).isEqualTo(TiberoDdlSql.createTable(S, "t", cols));
    assertThat(ddl).noneMatch(s -> s.startsWith("DROP"));
    verifyNoDmlConnection();

    ddl.clear();
    complete(TransactionSynchronization.STATUS_COMMITTED);
    assertThat(ddl).isEmpty();
    // 보상은 캡처한 값만 쓰므로 테넌트 컨텍스트가 사라진 뒤에도 실행된다.
    TenantContext.clear();
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(ddl)
        .containsExactly(
            "DROP TABLE \"GENIA_T5\".\"T\"",
            "DROP SEQUENCE \"GENIA_T5\".\"" + TiberoNames.sequenceName(S, "t") + "\"");
  }

  private void verifyNoDmlConnection() {
    verify(connections, org.mockito.Mockito.never())
        .withDml(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any());
  }

  @Test
  void createTable_withoutTransaction_registersNothing() {
    // 동기화가 꺼져 있을 때 등록을 시도하면 IllegalStateException 이므로, 예외 없이 끝나면 등록하지 않은 것이다.
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    store.createTable("t", List.of(req("name", "TEXT")));
    store.addColumn("t", req("memo", "TEXT"));
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    assertThat(ddl).isNotEmpty();
  }

  @Test
  void createTable_failureMidway_dropsWhatWasCreated() {
    failWhen = sql -> sql.startsWith("CREATE INDEX");
    ddl.clear();

    assertThatThrownBy(() -> store.createTable("t", List.of(req("name", "TEXT"))))
        .isInstanceOf(UncategorizedSQLException.class);

    // 마지막 두 문장이 정리 DROP
    assertThat(ddl.subList(ddl.size() - 2, ddl.size()))
        .containsExactly(
            "DROP TABLE \"GENIA_T5\".\"T\"",
            "DROP SEQUENCE \"GENIA_T5\".\"" + TiberoNames.sequenceName(S, "t") + "\"");
  }

  /** 409 DATA_STORE_TABLE_EXISTS 인지 확인한다. */
  private static void assertTableExists409(Throwable e) {
    assertThat(e).isInstanceOf(CodedApiException.class);
    CodedApiException c = (CodedApiException) e;
    assertThat(c.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(c.code()).isEqualTo("DATA_STORE_TABLE_EXISTS");
    assertThat(c.getMessage()).isEqualTo("저장소에 같은 이름의 테이블이 이미 있습니다: t");
  }

  @Test
  void createTable_existingSequence_409_dropsNothing_registersNoCompensation() {
    // 고아(또는 동시에 만들어진) 테이블·시퀀스가 있으면 시퀀스 생성부터 955 로 실패한다.
    TransactionSynchronizationManager.initSynchronization();
    failWhen = sql -> sql.startsWith("CREATE SEQUENCE");
    failCode = 955;

    Throwable e = catchThrowable(() -> store.createTable("t", List.of(req("name", "TEXT"))));

    assertTableExists409(e);
    assertThat(ddl).hasSize(1).noneMatch(s -> s.startsWith("DROP"));
    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
  }

  @Test
  void createTable_existingTable_409_dropsOnlyOwnSequence_neverTheTable() {
    TransactionSynchronizationManager.initSynchronization();
    failWhen = sql -> sql.startsWith("CREATE TABLE");
    failMessage = "ORA-00955: name is already used by an existing object";

    Throwable e = catchThrowable(() -> store.createTable("t", List.of(req("name", "TEXT"))));

    assertTableExists409(e);
    // 이번 호출이 만든 시퀀스만 지운다 — 남의 테이블은 절대 DROP 하지 않는다.
    assertThat(ddl).noneMatch(s -> s.startsWith("DROP TABLE"));
    assertThat(ddl.get(ddl.size() - 1))
        .isEqualTo("DROP SEQUENCE \"GENIA_T5\".\"" + TiberoNames.sequenceName(S, "t") + "\"");
    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
  }

  @Test
  void cloneTable_existingTarget_409_noDrop() {
    failWhen = sql -> sql.startsWith("CREATE SEQUENCE");
    failCode = 955;
    Throwable e =
        catchThrowable(
            () ->
                store.cloneTable(
                    "src",
                    "t",
                    List.of("a"),
                    List.of(
                        new DatasetColumnResponse(
                            1L, "a", "A", "TEXT", null, true, false, null, 0, false))));
    assertTableExists409(e);
    assertThat(ddl).noneMatch(s -> s.startsWith("DROP"));
  }

  @Test
  void createTable_invalidName_runsNothing() {
    assertThatThrownBy(() -> store.createTable("select", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ddl).isEmpty();
  }

  @Test
  void dropTable_inTransaction_deferredToAfterCommit() {
    TransactionSynchronizationManager.initSynchronization();
    store.dropTable("t");
    assertThat(ddl).isEmpty();

    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(ddl).isEmpty();

    complete(TransactionSynchronization.STATUS_COMMITTED);
    assertThat(ddl)
        .containsExactly(
            "DROP TABLE \"GENIA_T5\".\"T\"",
            "DROP SEQUENCE \"GENIA_T5\".\"" + TiberoNames.sequenceName(S, "t") + "\"");
  }

  @Test
  void dropTable_afterCommitFailure_doesNotThrow() {
    TransactionSynchronizationManager.initSynchronization();
    store.dropTable("t");
    failWhen = sql -> true;
    // 이미 커밋된 뒤의 실패는 예외로 전파하지 않는다(ERROR 로그만).
    complete(TransactionSynchronization.STATUS_COMMITTED);
    assertThat(ddl).isNotEmpty();
  }

  @Test
  void dropTable_noTransaction_immediate() {
    store.dropTable("t");
    assertThat(ddl).hasSize(2);
  }

  @Test
  void dropTable_missingTable_ignored() {
    failWhen = sql -> sql.startsWith("DROP TABLE");
    failCode = 942;
    store.dropTable("t");
    assertThat(ddl).hasSize(2);
  }

  @Test
  void dropTable_missingByMessageOnly_ignored() {
    failWhen = sql -> sql.startsWith("DROP SEQUENCE");
    failMessage = "ORA-02289: sequence does not exist";
    store.dropTable("t");
    assertThat(ddl).hasSize(2);
  }

  @Test
  void dropTable_otherError_propagates() {
    failWhen = sql -> sql.startsWith("DROP TABLE");
    failCode = 1031;
    assertThatThrownBy(() -> store.dropTable("t")).isInstanceOf(UncategorizedSQLException.class);
  }

  @Test
  void addColumn_rollbackDropsColumn() {
    TransactionSynchronizationManager.initSynchronization();
    store.addColumn("t", req("memo", "TEXT"));
    assertThat(ddl).containsExactly("ALTER TABLE \"GENIA_T5\".\"T\" ADD (\"MEMO\" VARCHAR2(4000))");

    ddl.clear();
    complete(TransactionSynchronization.STATUS_COMMITTED);
    assertThat(ddl).isEmpty();
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(ddl).containsExactly("ALTER TABLE \"GENIA_T5\".\"T\" DROP COLUMN \"MEMO\"");
  }

  @Test
  void dropColumn_inTransaction_deferredToAfterCommit_invalidatesCacheWhenRun() {
    TransactionSynchronizationManager.initSynchronization();
    store.dropColumn("t", "memo");
    // 호출 시점에는 아무것도 실행하지 않고 캐시도 그대로 둔다(물리 컬럼이 아직 있다).
    assertThat(ddl).isEmpty();
    verify(columnTypeCache, org.mockito.Mockito.never()).invalidate(anyLong(), anyString());

    // 롤백이면 끝까지 아무것도 지우지 않는다 — 메타데이터와 데이터가 함께 남는다.
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(ddl).isEmpty();
    verify(columnTypeCache, org.mockito.Mockito.never()).invalidate(anyLong(), anyString());

    complete(TransactionSynchronization.STATUS_COMMITTED);
    assertThat(ddl).containsExactly("ALTER TABLE \"GENIA_T5\".\"T\" DROP COLUMN \"MEMO\"");
    verify(columnTypeCache).invalidate(5L, "t");
  }

  @Test
  void dropColumn_afterCommitFailure_loggedWithNames_doesNotThrow() {
    TransactionSynchronizationManager.initSynchronization();
    store.dropColumn("t", "memo");
    failWhen = sql -> true;
    failCode = 54;
    // 이미 커밋된 뒤의 실패는 예외로 전파하지 않는다(ERROR 로그만).
    complete(TransactionSynchronization.STATUS_COMMITTED);
    assertThat(ddl).hasSize(1);
    assertThat(errorLogs())
        .singleElement()
        .satisfies(
            m ->
                assertThat(m)
                    .contains("테이블 t 의 컬럼 memo")
                    .doesNotContain("ALTER TABLE")
                    .doesNotContain("DROP COLUMN"));
    // 실패해도 캐시는 무효화한다(다음 검색이 카탈로그를 다시 읽는다).
    verify(columnTypeCache).invalidate(5L, "t");
  }

  @Test
  void dropColumn_noTransaction_immediate() {
    store.dropColumn("t", "memo");
    assertThat(ddl).containsExactly("ALTER TABLE \"GENIA_T5\".\"T\" DROP COLUMN \"MEMO\"");
    verify(columnTypeCache).invalidate(5L, "t");
  }

  @Test
  void statusUnknown_compensationsDropNothing_logErrorNamingObjects() {
    TransactionSynchronizationManager.initSynchronization();
    store.createTable("t", List.of(req("name", "TEXT")));
    store.addColumn("t", req("memo", "TEXT"));
    store.dropColumn("t", "old");
    store.dropTable("u");
    ddl.clear();

    complete(TransactionSynchronization.STATUS_UNKNOWN);

    assertThat(ddl).isEmpty();
    verify(connections, org.mockito.Mockito.never()).rollbackBound(anyLong());
    assertThat(errorLogs())
        .hasSize(4)
        .allSatisfy(m -> assertThat(m).contains("STATUS_UNKNOWN"))
        .anySatisfy(m -> assertThat(m).contains("테이블 t(+전용 시퀀스)"))
        .anySatisfy(m -> assertThat(m).contains("테이블 t 의 컬럼 memo"))
        .anySatisfy(m -> assertThat(m).contains("테이블 t 의 컬럼 old"))
        .anySatisfy(m -> assertThat(m).contains("테이블 u(+전용 시퀀스)"));
  }

  @Test
  void createTable_systemColumnName_rejectedBeforeAnyDdl() {
    for (String name : List.of("id", "IMPORT_ID", "created_at", "_updated_at")) {
      assertThatThrownBy(
              () -> store.createTable("t", List.of(req("name", "TEXT"), req(name, "TEXT"))))
          .isInstanceOf(com.smartfirehub.dataset.exception.InvalidTableNameException.class)
          .hasMessage(
              "컬럼명 '" + name + "'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at)");
    }
    assertThat(ddl).isEmpty();
  }

  @Test
  void cloneTable_systemColumnName_rejectedBeforeAnyDdl() {
    assertThatThrownBy(
            () ->
                store.cloneTable(
                    "src",
                    "copy",
                    List.of("created_at"),
                    List.of(
                        new DatasetColumnResponse(
                            1L,
                            "created_at",
                            "C",
                            "TIMESTAMP",
                            null,
                            true,
                            false,
                            null,
                            0,
                            false))))
        .isInstanceOf(com.smartfirehub.dataset.exception.InvalidTableNameException.class)
        .hasMessageContaining("시스템 예약어");
    assertThat(ddl).isEmpty();
  }

  @Test
  void addColumn_systemColumnName_rejected() {
    assertThatThrownBy(() -> store.addColumn("t", req("id", "TEXT")))
        .isInstanceOf(com.smartfirehub.dataset.exception.InvalidTableNameException.class);
  }

  @Test
  void cloneTable_runsCreateThenInsert_andRollbackDrops() {
    TransactionSynchronizationManager.initSynchronization();
    store.cloneTable(
        "src",
        "dst",
        List.of("a"),
        List.of(
            new DatasetColumnResponse(1L, "a", "A", "TEXT", null, true, false, null, 0, false)));
    assertThat(ddl.get(ddl.size() - 1)).startsWith("INSERT INTO \"GENIA_T5\".\"DST\"");

    ddl.clear();
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(ddl)
        .containsExactly(
            "DROP TABLE \"GENIA_T5\".\"DST\"",
            "DROP SEQUENCE \"GENIA_T5\".\"" + TiberoNames.sequenceName(S, "dst") + "\"");
  }

  @Test
  void otherDdl_goThroughDdlConnection() {
    store.setColumnIndex("t", "a", true, "TEXT");
    store.setColumnIndex("t", "a", false, "TEXT");
    store.recreatePrimaryKeyIndex("t", List.of("a"));
    store.recreatePrimaryKeyIndex("t", List.of());
    store.renameColumn("t", "a", "b");
    store.renameIndex("t", "a", "b");
    store.setColumnNullable("t", "a", false);
    store.alterColumnType("t", "a", "INTEGER", null, "TEXT");
    store.dropColumn("t", "a");

    assertThat(ddl)
        .extracting(s -> s.split(" \"")[0])
        .containsExactly(
            "CREATE INDEX",
            "DROP INDEX",
            "DROP INDEX",
            "CREATE UNIQUE INDEX",
            "DROP INDEX",
            // 비 BOOLEAN 컬럼 이름 변경은 제약 DDL 없이 컬럼만(A1)
            "ALTER TABLE",
            "ALTER INDEX",
            "ALTER TABLE",
            "ALTER TABLE",
            "ALTER TABLE");
  }

  @Test
  void dropIndex_missingIndex_ignored_noFurtherSql() {
    failWhen = sql -> true;
    failCode = 1418;
    store.setColumnIndex("t", "a", false, null);
    assertThat(ddl).hasSize(1);
  }

  @Test
  void renameIndex_missingIndex_ignored_noFurtherSql() {
    failWhen = sql -> true;
    failCode = 1418;
    store.renameIndex("t", "a", "b");
    assertThat(ddl).hasSize(1);
  }

  @Test
  void renameIndex_reservedNewName_rejectedBeforeAnyDdl() {
    assertThatThrownBy(() -> store.renameIndex("t", "a", "level"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ddl).isEmpty();
  }

  @Test
  void renameColumn_nonBoolean_runsNoConstraintDdl() {
    constraintExists = false;
    store.renameColumn("t", "a", "b");
    assertThat(ddl).containsExactly(TiberoDdlSql.renameColumn(S, "t", "a", "b"));
    // 존재 확인은 스키마·제약 이름(대문자 물리 이름)으로 한다.
    assertThat(catalogQueries).containsExactly(TiberoCatalog.constraintExistsQuery());
  }

  @Test
  void renameColumn_boolean_renamesConstraintThenColumn() {
    constraintExists = true;
    store.renameColumn("t", "a", "b");
    assertThat(ddl)
        .containsExactly(
            TiberoDdlSql.renameCheckConstraint(S, "t", "a", "b"),
            TiberoDdlSql.renameColumn(S, "t", "a", "b"));
  }

  @Test
  void renameColumn_columnRenameFails_constraintRenamedBack() {
    constraintExists = true;
    failWhen = sql -> sql.contains(" RENAME COLUMN ");
    failCode = 1430;
    assertThatThrownBy(() -> store.renameColumn("t", "a", "b"))
        .isInstanceOf(UncategorizedSQLException.class);
    assertThat(ddl)
        .containsExactly(
            TiberoDdlSql.renameCheckConstraint(S, "t", "a", "b"),
            TiberoDdlSql.renameColumn(S, "t", "a", "b"),
            TiberoDdlSql.renameCheckConstraint(S, "t", "b", "a"));
  }

  @Test
  void renameColumn_constraintVanished_23292Ignored_columnStillRenamed() {
    // 확인과 RENAME 사이에 제약이 사라졌으면(경합) ORA-23292 를 무시하고 컬럼만 바꾼다(되돌릴 제약도 없다).
    constraintExists = true;
    failWhen = sql -> sql.contains("RENAME CONSTRAINT");
    failCode = 23292;
    store.renameColumn("t", "a", "b");
    assertThat(ddl).hasSize(2);
    assertThat(ddl.get(1)).isEqualTo(TiberoDdlSql.renameColumn(S, "t", "a", "b"));
  }

  @Test
  void renameColumn_reservedNewName_rejectedBeforeAnyDdl() {
    assertThatThrownBy(() -> store.renameColumn("t", "a", "level"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ddl).isEmpty();
  }

  @Test
  void alterColumnType_booleanTransitions() {
    store.alterColumnType("t", "f", "INTEGER", null, "BOOLEAN");
    assertThat(ddl).hasSize(2);
    assertThat(ddl.get(0)).contains("DROP CONSTRAINT");

    // 제약이 이미 없어도(2443) 계속 진행한다
    ddl.clear();
    failWhen = sql -> sql.contains("DROP CONSTRAINT");
    failCode = 2443;
    store.alterColumnType("t", "f", "INTEGER", null, "BOOLEAN");
    assertThat(ddl).hasSize(2);

    ddl.clear();
    failWhen = sql -> false;
    store.alterColumnType("t", "f", "BOOLEAN", null, "INTEGER");
    assertThat(ddl).hasSize(2);
    assertThat(ddl.get(1)).contains("ADD CONSTRAINT");

    ddl.clear();
    store.alterColumnType("t", "f", "BOOLEAN", null, "BOOLEAN");
    assertThat(ddl).hasSize(1);
  }

  @Test
  void cloneThenCallerIndexes_eachIndexCreatedOnce() {
    store.cloneTable(
        "src",
        "dst",
        List.of("a", "b"),
        List.of(
            new DatasetColumnResponse(1L, "a", "A", "TEXT", null, false, false, null, 0, true),
            new DatasetColumnResponse(2L, "b", "B", "INTEGER", null, true, true, null, 1, false)));
    // DatasetService.cloneDataset 이 하는 일: 인덱스 컬럼별 setColumnIndex, 이어서 PK 재생성
    store.setColumnIndex("dst", "b", true, "INTEGER");
    store.recreatePrimaryKeyIndex("dst", List.of("a"));

    List<String> created =
        ddl.stream()
            .filter(s -> s.startsWith("CREATE INDEX") || s.startsWith("CREATE UNIQUE"))
            .toList();
    assertThat(created).doesNotHaveDuplicates();
    for (String name :
        List.of(TiberoNames.indexName(S, "dst", "b"), TiberoNames.primaryKeyIndexName(S, "dst"))) {
      assertThat(created.stream().filter(s -> s.contains("\"" + name + "\"")).count())
          .as(name)
          .isEqualTo(1);
    }
  }

  @Test
  void addColumn_indexFailureAfterAdd_stillDropsColumnOnRollback() {
    TransactionSynchronizationManager.initSynchronization();
    failWhen = sql -> sql.startsWith("CREATE INDEX");
    DatasetColumnRequest indexed =
        new DatasetColumnRequest("memo", "memo", "TEXT", null, true, true, null, false);
    assertThatThrownBy(() -> store.addColumn("t", indexed))
        .isInstanceOf(UncategorizedSQLException.class);

    failWhen = sql -> false;
    ddl.clear();
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(ddl).containsExactly("ALTER TABLE \"GENIA_T5\".\"T\" DROP COLUMN \"MEMO\"");
  }

  @Test
  void rollbackCompensation_failuresAreSwallowed() {
    TransactionSynchronizationManager.initSynchronization();
    store.createTable("t", List.of(req("name", "TEXT")));
    store.addColumn("t", req("memo", "TEXT"));
    failWhen = sql -> true;
    failCode = 1031;
    ddl.clear();
    // 예외가 새면 이 호출이 실패한다. 두 보상 모두 시도는 해야 한다.
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(ddl).isNotEmpty();
  }

  @Test
  void rollbackCompensation_releasesBoundConnectionBeforeDrop() throws Exception {
    // createTable → (행 쓰기) → 롤백: 묶인 DML 커넥션의 잠금을 먼저 풀어야 DROP 이 ORA-00054 로 막히지 않는다(A5).
    TransactionSynchronizationManager.initSynchronization();
    store.createTable("t", List.of(req("name", "TEXT")));
    store.addColumn("t", req("memo", "TEXT"));
    org.mockito.Mockito.clearInvocations(connections, statement);
    ddl.clear();

    complete(TransactionSynchronization.STATUS_ROLLED_BACK);

    InOrder order = inOrder(connections, statement);
    // 테이블 삭제 보상(Compensation, ROLLBACK)
    order.verify(connections).rollbackBound(5L);
    order.verify(statement).execute("DROP TABLE \"GENIA_T5\".\"T\"");
    // 컬럼 삭제 보상(Compensation, ROLLBACK)
    order.verify(connections).rollbackBound(5L);
    order.verify(statement).execute("ALTER TABLE \"GENIA_T5\".\"T\" DROP COLUMN \"MEMO\"");
  }

  @Test
  void rollbackDrop_tableDropFails_sequenceDropStillAttempted() {
    // 보상은 단계별로 실패를 잡는다 — 테이블 DROP 이 막혀도 시퀀스 DROP 은 시도한다.
    TransactionSynchronizationManager.initSynchronization();
    store.createTable("t", List.of(req("name", "TEXT")));
    failWhen = sql -> sql.startsWith("DROP TABLE");
    failCode = 54;
    ddl.clear();
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(ddl)
        .containsExactly(
            "DROP TABLE \"GENIA_T5\".\"T\"",
            "DROP SEQUENCE \"GENIA_T5\".\"" + TiberoNames.sequenceName(S, "t") + "\"");
  }

  @Test
  void rollbackCompensation_connectionFailure_swallowed() {
    // 보상 DDL 커넥션을 얻지 못해도(풀·접속 실패) 트랜잭션 완료 콜백 밖으로 예외가 새지 않는다.
    TransactionSynchronizationManager.initSynchronization();
    store.addColumn("t", req("memo", "TEXT"));
    // when(...) 로 다시 스텁하면 기존 답변이 null 작업으로 실행되므로 doThrow 를 쓴다.
    org.mockito.Mockito.doThrow(new IllegalStateException("pool down"))
        .when(connections)
        .withDdl(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any());
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    verify(connections).rollbackBound(5L);
  }

  @Test
  void columnStructureDdl_invalidatesSearchTypeCache() {
    // 행 저장소의 검색 타입 캐시는 컬럼 구조를 바꾼 테이블만 지운다.
    store.addColumn("t", req("memo", "TEXT"));
    store.dropColumn("t", "memo");
    store.alterColumnType("t", "memo", "INTEGER", null, "TEXT");
    store.renameColumn("t", "memo", "note");
    store.dropTable("t");
    verify(columnTypeCache, times(5)).invalidate(5L, "t");

    store.cloneTable(
        "src",
        "copy",
        List.of("a"),
        List.of(
            new DatasetColumnResponse(1L, "a", "A", "TEXT", null, true, false, null, 0, false)));
    verify(columnTypeCache).invalidate(5L, "copy");
    verify(columnTypeCache, org.mockito.Mockito.never()).invalidate(5L, "src");

    // 인덱스·NULL 변경은 컬럼 타입을 바꾸지 않으므로 무효화하지 않는다.
    org.mockito.Mockito.clearInvocations(columnTypeCache);
    store.setColumnNullable("t", "memo", true);
    store.setColumnIndex("t", "memo", true, "TEXT");
    verify(columnTypeCache, org.mockito.Mockito.never()).invalidate(anyLong(), anyString());
  }

  @Test
  void commit_doesNotTouchBoundConnection() {
    TransactionSynchronizationManager.initSynchronization();
    store.createTable("t", List.of(req("name", "TEXT")));
    complete(TransactionSynchronization.STATUS_COMMITTED);
    verify(connections, org.mockito.Mockito.never()).rollbackBound(anyLong());
  }

  @Test
  void recreatePrimaryKeyIndex_tooLongKey_rejectedBeforeAnyDdl() {
    // 호출자는 이름만 준다 — 카탈로그의 실제 폭(VARCHAR2(4000) × 2 = 8000 바이트)으로 검사하고, 기존 PK 인덱스를 지우기 전에 거부한다.
    columnRows.add(new Object[] {"A", "VARCHAR2", 4000, null, null, "B", 4000});
    columnRows.add(new Object[] {"B", "VARCHAR2", 4000, null, null, "B", 4000});
    assertThatThrownBy(() -> store.recreatePrimaryKeyIndex("t", List.of("a", "b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("인덱스 키 길이 제한");
    assertThat(ddl).isEmpty();
  }

  @Test
  void recreatePrimaryKeyIndex_shortKey_dropsThenCreates() {
    columnRows.add(new Object[] {"A", "VARCHAR2", 400, null, null, "C", 100});
    columnRows.add(new Object[] {"B", "NUMBER", 22, 19, 0, null, null});
    store.recreatePrimaryKeyIndex("t", List.of("a", "b"));
    assertThat(ddl)
        .containsExactly(
            TiberoDdlSql.dropPrimaryKeyIndex(S, "t"),
            TiberoDdlSql.createPrimaryKeyIndex(S, "t", List.of("a", "b")));
  }

  @Test
  void recreatePrimaryKeyIndex_readsOldIndexBeforeDrop_oneConnection() {
    columnRows.add(new Object[] {"C", "NUMBER", 22, 19, 0, null, null});
    indexColumnRows.addAll(List.of("A", "B"));
    store.recreatePrimaryKeyIndex("t", List.of("c"));

    assertThat(catalogQueries)
        .anySatisfy(
            q ->
                assertThat(q)
                    .isEqualTo(
                        "SELECT COLUMN_NAME FROM ALL_IND_COLUMNS WHERE INDEX_OWNER = ? AND"
                            + " INDEX_NAME = ? ORDER BY COLUMN_POSITION"));
    assertThat(ddl)
        .containsExactly(
            TiberoDdlSql.dropPrimaryKeyIndex(S, "t"),
            TiberoDdlSql.createPrimaryKeyIndex(S, "t", List.of("c")));
    // 검사·옛 정의 읽기·삭제·생성을 한 DDL 커넥션에서 한다.
    verify(connections, times(1))
        .withDdl(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any());
  }

  @Test
  void recreatePrimaryKeyIndex_createFails_restoresOldIndex_thenRethrows() {
    columnRows.add(new Object[] {"C", "NUMBER", 22, 19, 0, null, null});
    indexColumnRows.addAll(List.of("A", "B"));
    String newIndex = TiberoDdlSql.createPrimaryKeyIndex(S, "t", List.of("c"));
    failWhen = newIndex::equals;
    failCode = 1452; // 중복 키가 있어 유니크 인덱스를 만들 수 없음

    assertThatThrownBy(() -> store.recreatePrimaryKeyIndex("t", List.of("c")))
        .isInstanceOf(UncategorizedSQLException.class);

    assertThat(ddl)
        .containsExactly(
            TiberoDdlSql.dropPrimaryKeyIndex(S, "t"),
            newIndex,
            // 옛 인덱스를 카탈로그에서 읽은 컬럼 그대로 되살린다.
            "CREATE UNIQUE INDEX \"GENIA_T5\".\""
                + TiberoNames.primaryKeyIndexName(S, "t")
                + "\" ON \"GENIA_T5\".\"T\" (\"A\", \"B\")");
    assertThat(errorLogs()).isEmpty();
  }

  @Test
  void recreatePrimaryKeyIndex_restoreAlsoFails_errorLogged_originalRethrownWithSuppressed() {
    indexColumnRows.add("A");
    failWhen = sql -> sql.startsWith("CREATE UNIQUE INDEX");
    failCode = 1452;

    Throwable thrown = catchThrowable(() -> store.recreatePrimaryKeyIndex("t", List.of("c")));

    assertThat(thrown).isInstanceOf(UncategorizedSQLException.class);
    SQLException cause = (SQLException) thrown.getCause();
    assertThat(cause.getSuppressed()).hasSize(1);
    assertThat(ddl).hasSize(3);
    assertThat(errorLogs())
        .singleElement()
        .satisfies(m -> assertThat(m).contains("PK 유일성 강제가 꺼짐").contains("t"));
  }

  @Test
  void recreatePrimaryKeyIndex_noOldIndex_createFails_nothingToRestore() {
    failWhen = sql -> sql.startsWith("CREATE UNIQUE INDEX");
    failCode = 1452;
    assertThatThrownBy(() -> store.recreatePrimaryKeyIndex("t", List.of("c")))
        .isInstanceOf(UncategorizedSQLException.class);
    assertThat(ddl).hasSize(2);
  }

  @Test
  void statText_mirrorsPgText() {
    assertThat(TiberoDataTableStore.statText("2026-01-02 03:04:05.120000", "TIMESTAMP"))
        .isEqualTo("2026-01-02 03:04:05.12");
    assertThat(TiberoDataTableStore.statText("2026-01-02 03:04:05.000000", "TIMESTAMP"))
        .isEqualTo("2026-01-02 03:04:05");
    assertThat(TiberoDataTableStore.statText("0", "BOOLEAN")).isEqualTo("false");
    assertThat(TiberoDataTableStore.statText("1", "BOOLEAN")).isEqualTo("true");
    assertThat(TiberoDataTableStore.statText("2026-01-02", "DATE")).isEqualTo("2026-01-02");
    assertThat(TiberoDataTableStore.statText(null, "BOOLEAN")).isNull();
  }

  @Test
  void getColumnStats_booleanAndDate_mapping() throws Exception {
    Connection dmlConn = mock(Connection.class);
    Statement st = mock(Statement.class);
    when(dmlConn.createStatement()).thenReturn(st);
    List<String> queries = new ArrayList<>();

    ResultSet stats = mock(ResultSet.class);
    when(stats.next()).thenReturn(true);
    when(stats.getLong("TOTAL")).thenReturn(4L);
    when(stats.getLong("NULL_COUNT")).thenReturn(0L);
    when(stats.getLong("DISTINCT_COUNT")).thenReturn(2L);
    when(stats.getString("MIN_VAL")).thenReturn("0", "2026-01-01");
    when(stats.getString("MAX_VAL")).thenReturn("1", "2026-02-01");
    ResultSet top = mock(ResultSet.class);
    when(top.next()).thenReturn(true, false, true, false);
    when(top.getString("VAL")).thenReturn("1", "2026-01-01");
    when(top.getLong("CNT")).thenReturn(3L, 4L);
    when(st.executeQuery(anyString()))
        .thenAnswer(
            inv -> {
              String q = inv.getArgument(0);
              queries.add(q);
              return q.contains("AS TOTAL") ? stats : top;
            });
    when(connections.withDml(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any()))
        .thenAnswer(inv -> runWork(inv.getArgument(1), dmlConn));

    List<ColumnStatsResponse> r =
        store.getColumnStats(
            "t",
            List.of(
                new DatasetColumnResponse(
                    1L, "f", "F", "BOOLEAN", null, true, false, null, 0, false),
                new DatasetColumnResponse(
                    2L, "d", "D", "DATE", null, true, false, null, 1, false)));

    assertThat(r.get(0).minValue()).isEqualTo("false");
    assertThat(r.get(0).maxValue()).isEqualTo("true");
    assertThat(r.get(0).topValues())
        .containsExactly(new ColumnStatsResponse.ValueCount("true", 3L));
    assertThat(r.get(0).avgValue()).isNull();
    assertThat(r.get(1).minValue()).isEqualTo("2026-01-01");
    assertThat(r.get(1).maxValue()).isEqualTo("2026-02-01");
    assertThat(queries.stream().anyMatch(q -> q.contains("'YYYY-MM-DD'"))).isTrue();
  }

  @Test
  void geometry_setColumnIndex_unsupported() {
    assertThatThrownBy(() -> store.setColumnIndex("t", "g", true, "GEOMETRY"))
        .isInstanceOf(DataStoreFeatureUnsupportedException.class);
  }

  @Test
  void concurrently_unsupported422() {
    assertThatThrownBy(() -> store.createPrimaryKeyIndexConcurrently("t", List.of("a")))
        .isInstanceOfSatisfying(
            DataStoreFeatureUnsupportedException.class,
            e -> assertThat(e.getMessage()).contains("온라인 PK 인덱스 생성"));
    assertThat(ddl).isEmpty();
  }

  @Test
  void getColumnStats_mapsResults() throws Exception {
    Connection dmlConn = mock(Connection.class);
    Statement st = mock(Statement.class);
    when(dmlConn.createStatement()).thenReturn(st);

    ResultSet numStats = mock(ResultSet.class);
    when(numStats.next()).thenReturn(true);
    when(numStats.getLong("TOTAL")).thenReturn(10L);
    when(numStats.getLong("NULL_COUNT")).thenReturn(2L);
    when(numStats.getLong("DISTINCT_COUNT")).thenReturn(5L);
    when(numStats.getString("MIN_VAL")).thenReturn("1");
    when(numStats.getString("MAX_VAL")).thenReturn("9");
    when(numStats.getObject("AVG_VAL")).thenReturn(new java.math.BigDecimal("4.5"));

    ResultSet top = mock(ResultSet.class);
    when(top.next()).thenReturn(true, true, false);
    when(top.getString("VAL")).thenReturn("3", "4");
    when(top.getLong("CNT")).thenReturn(4L, 1L);

    when(st.executeQuery(anyString()))
        .thenAnswer(inv -> ((String) inv.getArgument(0)).contains("AS TOTAL") ? numStats : top);

    when(connections.withDml(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any()))
        .thenAnswer(inv -> runWork(inv.getArgument(1), dmlConn));

    List<ColumnStatsResponse> stats =
        store.getColumnStats(
            "t",
            List.of(
                new DatasetColumnResponse(
                    1L, "qty", "Q", "INTEGER", null, true, false, null, 0, false),
                new DatasetColumnResponse(
                    2L, "g", "G", "GEOMETRY", null, true, false, null, 1, false)));

    assertThat(stats).hasSize(1);
    ColumnStatsResponse r = stats.get(0);
    assertThat(r.columnName()).isEqualTo("qty");
    assertThat(r.dataType()).isEqualTo("INTEGER");
    assertThat(r.totalCount()).isEqualTo(10L);
    assertThat(r.nullCount()).isEqualTo(2L);
    assertThat(r.nullPercent()).isEqualTo(20.0);
    assertThat(r.distinctCount()).isEqualTo(5L);
    assertThat(r.minValue()).isEqualTo("1");
    assertThat(r.maxValue()).isEqualTo("9");
    assertThat(r.avgValue()).isEqualTo(4.5);
    assertThat(r.topValues())
        .containsExactly(
            new ColumnStatsResponse.ValueCount("3", 4L),
            new ColumnStatsResponse.ValueCount("4", 1L));
    assertThat(r.sampled()).isFalse();
    verify(st, org.mockito.Mockito.atLeastOnce()).setQueryTimeout(30);
    // 읽기 전용이므로 DDL 연결은 쓰지 않는다.
    assertThat(ddl).isEmpty();
  }
}
