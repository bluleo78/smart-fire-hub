package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections.SqlWork;
import com.smartfirehub.global.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tibero 임시 테이블 교체(WD-77): 스테이징 생성 → 옮기기 → 정리의 순서·인자·실패 보상을 가짜 {@link TiberoBulkMover}·행 저장소로 검증한다.
 * 실제 옮기기 SQL 은 {@code TiberoDmlSqlTest}, 실서버 동작은 db-executor 의 {@code TiberoLiveMoveTest} 가 맡는다.
 */
class TiberoDataTableStoreTempTest {

  private static final String S = "GENIA_T5";
  private static final String STG = "STG_0123456789ABCDEF01234567";
  private static final String STG_LOGICAL = "stg_0123456789abcdef01234567";

  private TiberoDataRowStore rowStore;
  private TiberoBulkMover mover;
  private TiberoDataTableStore store;

  /** 카탈로그({@code ALL_TAB_COLUMNS}) 질의가 차례로 돌려줄 컬럼 맵(질의 순서대로). 옮기기는 스테이징 → 출력 순으로 한 커넥션에서 읽는다. */
  private final ArrayDeque<Map<String, TiberoColumnType>> catalogs = new ArrayDeque<>();

  /** withDdl 이 넘긴 작업을 실제로 돌릴 가짜 커넥션 — 질의마다 {@link #catalogs} 에서 한 맵을 꺼내 결과로 낸다. */
  private Connection catalogConnection() throws SQLException {
    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString()))
        .thenAnswer(
            inv -> {
              PreparedStatement ps = mock(PreparedStatement.class);
              when(ps.executeQuery()).thenAnswer(x -> catalogResult(catalogs.poll()));
              return ps;
            });
    return conn;
  }

  /** 카탈로그 맵을 {@code TiberoCatalog#columnTypes} 가 읽는 열 순서의 ResultSet 으로 바꾼다. */
  private static ResultSet catalogResult(Map<String, TiberoColumnType> catalog)
      throws SQLException {
    List<Map.Entry<String, TiberoColumnType>> rows =
        catalog == null ? List.of() : List.copyOf(catalog.entrySet());
    int[] at = {-1};
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenAnswer(x -> ++at[0] < rows.size());
    when(rs.getString(1)).thenAnswer(x -> rows.get(at[0]).getKey().toUpperCase());
    when(rs.getString(2)).thenAnswer(x -> rows.get(at[0]).getValue().dataType());
    when(rs.getInt(3)).thenAnswer(x -> intOrZero(rows.get(at[0]).getValue().dataLength()));
    when(rs.getInt(4)).thenAnswer(x -> intOrZero(rows.get(at[0]).getValue().precision()));
    when(rs.getInt(5)).thenAnswer(x -> intOrZero(rows.get(at[0]).getValue().scale()));
    when(rs.getString(6)).thenAnswer(x -> rows.get(at[0]).getValue().charUsed());
    when(rs.getInt(7)).thenAnswer(x -> intOrZero(rows.get(at[0]).getValue().charLength()));
    return rs;
  }

  private static int intOrZero(Integer v) {
    return v == null ? 0 : v;
  }

  @BeforeEach
  void setUp() throws Exception {
    TenantContext.set(5L);
    ExternalStoreConnections connections = mock(ExternalStoreConnections.class);
    when(connections.schemaOf(5L)).thenReturn(S);
    Connection conn = catalogConnection();
    when(connections.withDdl(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any()))
        .thenAnswer(inv -> inv.<SqlWork<Object>>getArgument(1).apply(conn));
    rowStore = mock(TiberoDataRowStore.class);
    mover = mock(TiberoBulkMover.class);
    store =
        new TiberoDataTableStore(
            connections,
            mock(TiberoColumnTypeCache.class),
            rowStore,
            mover,
            mock(TiberoTriggerPrivilege.class),
            slotGrantSync);
  }

  /** 슬롯 SELECT 권한 동기화(WD-78) 가짜 — REPLACE 가 이것을 건드리지 않음을 확인한다. */
  private final com.smartfirehub.securitylevel.pythonread.TiberoPythonReadGrantSync slotGrantSync =
      mock(com.smartfirehub.securitylevel.pythonread.TiberoPythonReadGrantSync.class);

  /**
   * REPLACE 는 테이블을 바꾸거나 이름을 바꾸지 않고 같은 테이블에 행만 옮긴다(스펙 §4.1) — 그래서 슬롯 GRANT 는 그대로 남고 다시 맞출 일이 없다.
   * 옮기기· 정리가 GRANT 동기화를 부르지 않음을 고정한다(부르게 되면 맞바꿈 설계가 바뀐 것이므로 이 테스트가 알려 준다).
   */
  @Test
  void finishReplace_keepsTableSoSlotGrantsSurvive_noGrantSync() {
    catalogs.add(staging("code", "qty"));
    catalogs.add(target());

    store.finishReplace("orders", STG, 3);
    store.finishReplace("orders", STG, 0);

    org.mockito.Mockito.verifyNoInteractions(slotGrantSync);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private static TiberoColumnType text() {
    return TiberoColumnType.of("VARCHAR2", 50, null, null);
  }

  /** 출력 테이블 카탈로그: 시스템 컬럼 + 사용자 컬럼 code·qty. */
  private static Map<String, TiberoColumnType> target() {
    Map<String, TiberoColumnType> m = new LinkedHashMap<>();
    for (String c : List.of("id", "import_id", "code", "qty", "created_at", "_updated_at")) {
      m.put(c, text());
    }
    return m;
  }

  /** 스테이징 카탈로그: _SEQ + 지정한 사용자 컬럼. */
  private static Map<String, TiberoColumnType> staging(String... cols) {
    Map<String, TiberoColumnType> m = new LinkedHashMap<>();
    m.put("_seq", text());
    for (String c : cols) {
      m.put(c, text());
    }
    return m;
  }

  @Test
  void createTempTable_copiesOnlyUserColumns_andReturnsPhysicalName() {
    catalogs.add(target());
    when(rowStore.createStagingTable(any(), any())).thenReturn(STG_LOGICAL);

    String temp = store.createTempTable("orders");

    assertThat(temp).isEqualTo(STG);
    verify(rowStore).createStagingTable("orders", List.of("code", "qty"));
  }

  @Test
  void createTempTable_missingTable_fails() {
    catalogs.add(new LinkedHashMap<>());

    assertThatThrownBy(() -> store.createTempTable("orders"))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(rowStore);
  }

  @Test
  void swapTable_movesReplace_thenDropsStaging() {
    catalogs.add(staging("code", "qty"));
    catalogs.add(target());

    store.swapTable("orders", STG);

    InOrder order = inOrder(mover, rowStore);
    order.verify(mover).move(5L, S, "orders", STG, List.of("code", "qty"), true);
    order.verify(rowStore).dropStagingTable(STG_LOGICAL);
  }

  @Test
  void swapTable_moveFails_propagates_leavesDropToCaller() {
    catalogs.add(staging("code", "qty"));
    catalogs.add(target());
    RuntimeException boom = new RuntimeException("boom");
    doThrow(boom).when(mover).move(anyLong(), any(), any(), any(), any(), eq(true));

    assertThatThrownBy(() -> store.swapTable("orders", STG)).isSameAs(boom);

    // 코드리뷰 7: 실패 시 스테이징 정리는 호출자(러너·실행기)의 catch → dropTempTable 한 곳이 맡는다 — 저장소가 또 지우지 않는다.
    verify(rowStore, never()).dropStagingTable(any());
  }

  @Test
  void swapTable_rejectsBadNames_withoutTouchingAnything() {
    for (String bad :
        List.of("orders", "stg_0123456789abcdef01234567", "STG_XYZ", "rtmp_0123456789ab_x", "")) {
      assertThatThrownBy(() -> store.swapTable("orders", bad))
          .as(bad)
          .isInstanceOf(InvalidTableNameException.class);
    }
    verifyNoInteractions(mover, rowStore);
  }

  @Test
  void swapTable_stagingOfAnotherTable_rejected() {
    catalogs.add(staging("code", "other_col"));
    catalogs.add(target());

    assertThatThrownBy(() -> store.swapTable("orders", STG))
        .isInstanceOf(InvalidTableNameException.class);

    verify(mover, never()).move(anyLong(), any(), any(), any(), any(), eq(true));
  }

  @Test
  void finishReplace_zeroRows_dropsOnly_keepsOriginal() {
    store.finishReplace("orders", STG, 0);

    verify(rowStore).dropStagingTable(STG_LOGICAL);
    verifyNoInteractions(mover);
  }

  @Test
  void finishReplace_rows_swaps() {
    catalogs.add(staging("code", "qty"));
    catalogs.add(target());

    store.finishReplace("orders", STG, 3);

    verify(mover).move(5L, S, "orders", STG, List.of("code", "qty"), true);
  }

  @Test
  void finishAppend_rows_movesAppendWithoutDelete_thenDrops() {
    catalogs.add(staging("code", "qty"));
    catalogs.add(target());

    store.finishAppend("orders", STG, 7);

    InOrder order = inOrder(mover, rowStore);
    order.verify(mover).move(5L, S, "orders", STG, List.of("code", "qty"), false);
    order.verify(rowStore).dropStagingTable(STG_LOGICAL);
    verify(mover, never()).move(anyLong(), any(), any(), any(), any(), eq(true));
  }

  @Test
  void finishAppend_zeroRows_dropsOnly() {
    store.finishAppend("orders", STG, 0);

    verify(rowStore).dropStagingTable(STG_LOGICAL);
    verifyNoInteractions(mover);
  }

  @Test
  void finishAppend_moveFails_propagates_leavesDropToCaller() {
    catalogs.add(staging("code", "qty"));
    catalogs.add(target());
    RuntimeException boom = new RuntimeException("boom");
    doThrow(boom).when(mover).move(anyLong(), any(), any(), any(), any(), eq(false));

    assertThatThrownBy(() -> store.finishAppend("orders", STG, 2)).isSameAs(boom);

    verify(rowStore, never()).dropStagingTable(any());
  }

  @Test
  void dropTempTable_validatesName() {
    assertThatThrownBy(() -> store.dropTempTable("orders", "orders"))
        .isInstanceOf(InvalidTableNameException.class);
    verifyNoInteractions(rowStore);

    store.dropTempTable("orders", STG);
    verify(rowStore).dropStagingTable(STG_LOGICAL);
  }

  /** 출력 테이블 컬럼 일부만 가진(더 좁은) 스테이징은 다른 테이블의 것으로 보고 거부한다. */
  @Test
  void swapTable_narrowerStaging_rejected_leavesDropToCaller() {
    catalogs.add(staging("code"));
    catalogs.add(target());

    assertThatThrownBy(() -> store.swapTable("orders", STG))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("임시 테이블이 아닙니다");

    verify(mover, never()).move(anyLong(), any(), any(), any(), any(), eq(true));
    verify(rowStore, never()).dropStagingTable(any());
  }

  /** 스테이징이 카탈로그에 없으면 "찾을 수 없습니다" 로 알린다. */
  @Test
  void swapTable_missingStaging_reportsNotFound() {
    catalogs.add(new LinkedHashMap<>());

    assertThatThrownBy(() -> store.swapTable("orders", STG))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("찾을 수 없습니다");

    verify(mover, never()).move(anyLong(), any(), any(), any(), any(), eq(true));
  }

  /** 옮기기가 커밋된 뒤 스테이징 DROP 이 실패해도 성공으로 끝나고 경고만 남는다(재시도 시 APPEND 중복 방지). */
  @Test
  void moveSucceeds_dropFails_noExceptionAndWarns() {
    catalogs.add(staging("code", "qty"));
    catalogs.add(target());
    doThrow(new RuntimeException("drop boom")).when(rowStore).dropStagingTable(any());
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(TiberoDataTableStore.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
        new ch.qos.logback.core.read.ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      store.finishAppend("orders", STG, 5);
    } finally {
      logger.detachAppender(logs);
    }

    verify(mover).move(5L, S, "orders", STG, List.of("code", "qty"), false);
    assertThat(logs.list)
        .anyMatch(
            e ->
                e.getLevel() == ch.qos.logback.classic.Level.WARN
                    && e.getFormattedMessage().contains(STG));
  }

  /**
   * 러너 TEMP 재생성 보관(WD-256) — 저장소가 {@link TiberoRebuildBackup} 을 DDL 커넥션으로 부르고, 롤백 보상·진행 중 보관 보호·트리거
   * 재보장을 더한다. 카탈로그·DDL 은 메모리 속 가짜 서버({@link FakeTibero})가 해석하므로 상태(테이블·OBJECT_ID·트리거)로 확인한다. 트랜잭션
   * 동기화 콜백은 실제 등록 순서대로 직접 돌린다.
   */
  @Nested
  class Rebuild {

    private static final String T = "ptmp_77_2s";
    private static final String TP = "PTMP_77_2S";
    private static final String FP = "a".repeat(32);
    private final String bp = TiberoNames.physical(TiberoNames.rebuildBackupTableName(S, T));
    private final String trigger = TiberoNames.triggerName(S, T);

    private final FakeTibero f = new FakeTibero(S);
    private final TiberoColumnTypeCache cache = mock(TiberoColumnTypeCache.class);
    private final TiberoTriggerPrivilege privilege = mock(TiberoTriggerPrivilege.class);
    private final ExternalStoreConnections conns = mock(ExternalStoreConnections.class);
    private TiberoDataTableStore rb;

    @BeforeEach
    void fakeServer() throws Exception {
      f.seed(T, 77L);
      when(conns.schemaOf(5L)).thenReturn(S);
      when(conns.withDdl(anyLong(), org.mockito.ArgumentMatchers.<SqlWork<Object>>any()))
          .thenAnswer(
              inv -> {
                // 실제 withDdl 처럼 SQLException 을 런타임 예외로 감싼다.
                try {
                  return inv.<SqlWork<Object>>getArgument(1).apply(f.connection());
                } catch (SQLException e) {
                  throw new org.springframework.jdbc.UncategorizedSQLException("ddl", null, e);
                }
              });
      when(privilege.hasCreateTrigger(any())).thenReturn(true);
      rb = new TiberoDataTableStore(conns, cache, rowStore, mover, privilege, slotGrantSync);
    }

    @AfterEach
    void clearSync() {
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.clearSynchronization();
      }
      for (Object key : List.copyOf(TransactionSynchronizationManager.getResourceMap().keySet())) {
        TransactionSynchronizationManager.unbindResource(key);
      }
    }

    private List<com.smartfirehub.dataset.dto.DatasetColumnRequest> newColumns() {
      return List.of(
          new com.smartfirehub.dataset.dto.DatasetColumnRequest(
              "amount", "amount", "INTEGER", null, true, false, null, false));
    }

    /** 트랜잭션 완료를 흉내 낸다 — 등록 순서대로 afterCommit(커밋일 때)·afterCompletion 을 부른다. */
    private void complete(int status) {
      List<TransactionSynchronization> syncs =
          TransactionSynchronizationManager.getSynchronizations();
      TransactionSynchronizationManager.clearSynchronization();
      if (status == TransactionSynchronization.STATUS_COMMITTED) {
        syncs.forEach(TransactionSynchronization::afterCommit);
      }
      syncs.forEach(s -> s.afterCompletion(status));
    }

    @Test
    void move_callsHelperWithDdlConnectionAndFingerprint_returnsObjectId() {
      long oid = rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);

      assertThat(oid).isEqualTo(77L);
      assertThat(f.tables).containsEntry(bp, 77L).doesNotContainKey(TP);
      assertThat(f.comments).containsEntry(bp, "genia-rebuild:" + FP);
      verify(cache).invalidate(5L, T);
    }

    /** 2인자판(지문 없음)은 주석을 달지 않는다 — 맞춤이 「판정 불가」로 보고 복원 쪽으로 처리한다. */
    @Test
    void move_twoArg_writesNoFingerprint() {
      assertThat(rb.moveToRebuildBackup(T, "rtmp_bak_ignored")).isEqualTo(77L);
      assertThat(f.tables).containsKey(bp);
      assertThat(f.comments).doesNotContainKey(bp);
    }

    /**
     * 보관 직후 같은 트랜잭션에서 새 테이블을 만들고 롤백하면 <b>보상 하나가</b> 새 테이블·시퀀스를 지우고 보관본을 되돌린다. createTable 의 롤백 DROP
     * 은 따로 등록되지 않는다 — 등록됐다면 복원 뒤에 돌아 되살린 테이블을 지웠을 것이다.
     */
    @Test
    void rollback_oneCompensationRestores_createTableDropDoesNotRemoveRestoredTable() {
      TransactionSynchronizationManager.initSynchronization();
      rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);
      rb.createTable(T, newColumns());
      assertThat(f.tables).containsEntry(TP, 1000L).containsEntry(bp, 77L);

      complete(TransactionSynchronization.STATUS_ROLLED_BACK);

      assertThat(f.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);
      assertThat(f.sequences).containsExactly(TiberoNames.sequenceName(S, T));
      assertThat(f.triggers).containsEntry(trigger, TP);
      assertThat(f.log.stream().filter(s -> s.startsWith("DROP TABLE")).count())
          .as("새 테이블 DROP 은 한 번뿐")
          .isEqualTo(1);
      assertThat(TransactionSynchronizationManager.getResourceMap()).as("표시 해제").isEmpty();
      verify(conns).rollbackBound(5L);
    }

    /** 커밋되면 보상은 아무것도 하지 않고(보관본은 러너가 버리거나 되돌린다) 표시도 풀려 다음 트랜잭션의 createTable 롤백 DROP 을 막지 않는다. */
    @Test
    void commit_noCompensation_markerReleased_nextCreateTableStillCompensated() {
      TransactionSynchronizationManager.initSynchronization();
      rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);
      rb.createTable(T, newColumns());
      complete(TransactionSynchronization.STATUS_COMMITTED);

      assertThat(f.tables).containsEntry(TP, 1000L).containsEntry(bp, 77L);
      assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();

      // 다음 트랜잭션: 같은 스레드에서 다른 테이블 생성 → 롤백이면 평소처럼 지운다.
      TransactionSynchronizationManager.initSynchronization();
      rb.createTable("other_t", newColumns());
      complete(TransactionSynchronization.STATUS_ROLLED_BACK);
      assertThat(f.tables).doesNotContainKey("OTHER_T");
    }

    /** 결과 불명이면 아무것도 지우거나 되돌리지 않지만 표시는 푼다(스레드에 남으면 다음 트랜잭션의 롤백 DROP 이 빠진다). */
    @Test
    void unknown_noDdl_markerReleased() {
      TransactionSynchronizationManager.initSynchronization();
      rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);
      rb.createTable(T, newColumns());
      int before = f.log.size();

      complete(TransactionSynchronization.STATUS_UNKNOWN);

      assertThat(f.log).hasSize(before);
      assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    /** 커밋된 재생성의 스텝이 실패하면 되돌린다 — 트리거 재생성 + 검색 타입 캐시 무효화 + 슬롯 GRANT 커밋 후 동기화. */
    @Test
    void restore_recreatesTrigger_invalidatesCache_syncsSlotGrantsAfterCommit() {
      long oid = rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);
      rb.createTable(T, newColumns());
      org.mockito.Mockito.clearInvocations(cache, slotGrantSync);

      rb.restoreRebuildBackup(T, "rtmp_bak_ignored", oid);

      assertThat(f.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);
      assertThat(f.triggers).containsEntry(trigger, TP);
      verify(cache).invalidate(5L, T);
      verify(slotGrantSync).syncTableAfterCommit(T);
    }

    /**
     * 트랜잭션 안의 되돌리기는 확인만 하고 물리 복원을 커밋 뒤로 미룬다(리뷰 C1). 커밋 뒤 복원 DDL 이 실패해도 예외는 밖으로 나가지 않고 보관본은 남는다 —
     * 메타는 이미 옛 열로 커밋됐으므로 다음 맞춤이 같은 지문을 보고 복원해 옛 행을 되찾는다.
     */
    @Test
    void restoreInTx_deferredToAfterCommit_ddlFailureKeepsBackup_nextReconcileRestores() {
      long oid = rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);
      rb.createTable(T, newColumns());
      TransactionSynchronizationManager.initSynchronization();

      rb.restoreRebuildBackup(T, "rtmp_bak_ignored", oid);
      assertThat(f.tables).as("커밋 전에는 물리 변경 없음").containsEntry(TP, 1000L).containsEntry(bp, 77L);

      f.failOn = sql -> sql.startsWith("DROP TABLE");
      complete(TransactionSynchronization.STATUS_COMMITTED);
      f.failOn = sql -> false;
      assertThat(f.tables).as("보관본 그대로").containsEntry(bp, 77L);

      assertThat(rb.reconcileRebuildBackup(T, FP))
          .isEqualTo(com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState.RESTORED);
      assertThat(f.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);
      assertThat(f.triggers).containsEntry(trigger, TP);
    }

    /** 커밋되면 미뤄 둔 복원이 돈다 — 트리거·캐시·슬롯 GRANT 동기화 포함. */
    @Test
    void restoreInTx_commit_restoresAfterCommit() {
      long oid = rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);
      rb.createTable(T, newColumns());
      org.mockito.Mockito.clearInvocations(cache, slotGrantSync);
      TransactionSynchronizationManager.initSynchronization();

      rb.restoreRebuildBackup(T, "rtmp_bak_ignored", oid);
      complete(TransactionSynchronization.STATUS_COMMITTED);

      assertThat(f.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);
      assertThat(f.triggers).containsEntry(trigger, TP);
      verify(cache).invalidate(5L, T);
      verify(slotGrantSync).syncTableAfterCommit(T);
    }

    /** 메타 트랜잭션이 롤백되면 물리 DDL 은 없고 보관본이 남는다(맞춤이 판단). */
    @Test
    void restoreInTx_rollback_noPhysicalDdl_backupKept() {
      long oid = rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);
      rb.createTable(T, newColumns());
      TransactionSynchronizationManager.initSynchronization();
      rb.restoreRebuildBackup(T, "rtmp_bak_ignored", oid);
      int before = f.log.size();

      complete(TransactionSynchronization.STATUS_ROLLED_BACK);

      assertThat(f.log).hasSize(before);
      assertThat(f.tables).containsEntry(TP, 1000L).containsEntry(bp, 77L);
    }

    /** 보관본이 없거나 다른 실행의 것이면 메타를 쓰기 전에(동기화 등록도 없이) 바로 거부한다 — 겹친 실행 동작 유지. */
    @Test
    void restore_missingOrForeignBackup_throwsImmediately_nothingRegistered() {
      long oid = rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);
      TransactionSynchronizationManager.initSynchronization();

      assertThatThrownBy(() -> rb.restoreRebuildBackup(T, "rtmp_bak_ignored", oid + 1))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("다른 실행의 것이라");
      rb.dropRebuildBackup(T, "rtmp_bak_ignored", null);
      assertThatThrownBy(() -> rb.restoreRebuildBackup(T, "rtmp_bak_ignored", oid))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("없어");
      assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    /** 새 테이블을 만들기 전에 롤백돼도(예: 컬럼 저장 실패) 보상이 보관본을 원래 이름으로 되돌린다. */
    @Test
    void rollbackBeforeCreateTable_compensationRestoresBackup() {
      TransactionSynchronizationManager.initSynchronization();
      rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);

      complete(TransactionSynchronization.STATUS_ROLLED_BACK);

      assertThat(f.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);
      assertThat(f.triggers).containsEntry(trigger, TP);
    }

    /** 데이터셋 삭제·재생성 직전 정리(null)는 무조건 지운다. OID 가 다르면 지우지 않는다. */
    @Test
    void drop_nullDropsUnconditionally_mismatchedOidKeeps() {
      long oid = rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);

      assertThat(rb.dropRebuildBackup(T, "rtmp_bak_ignored", oid + 1)).isFalse();
      assertThat(f.tables).containsKey(bp);
      assertThat(rb.dropRebuildBackup(T, "rtmp_bak_ignored", null)).isTrue();
      assertThat(f.tables).doesNotContainKey(bp);
      assertThat(f.sequences).doesNotContain(TiberoNames.sequenceName(S, bp.toLowerCase()));
    }

    /** 크래시가 남긴 보관본(지문 같음 = 재생성 롤백) → 맞춤이 복원하고 트리거를 다시 만든다. */
    @Test
    void reconcile_delegates_restoredThenRecreatesTrigger() throws Exception {
      TiberoRebuildBackup.moveToBackup(f.connection(), S, T, FP); // 다른 프로세스가 보관한 뒤 죽음

      assertThat(rb.reconcileRebuildBackup(T, FP))
          .isEqualTo(com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState.RESTORED);

      assertThat(f.tables).containsOnlyKeys(TP).containsEntry(TP, 77L);
      assertThat(f.triggers).containsEntry(trigger, TP);
      verify(cache).invalidate(5L, T);
    }

    /** 보관본이 없으면 NONE — 트리거가 있으면 DDL 없이, 없으면 다시 만든다(이전 실행의 보관 실패가 트리거를 못 되살린 경우). */
    @Test
    void reconcile_none_reEnsuresTriggerOnlyWhenMissing() {
      int before = f.log.size();
      assertThat(rb.reconcileRebuildBackup(T, FP))
          .isEqualTo(com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState.NONE);
      assertThat(f.log).hasSize(before);

      f.triggers.clear();
      assertThat(rb.reconcileRebuildBackup(T, FP))
          .isEqualTo(com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState.NONE);
      assertThat(f.triggers).containsEntry(trigger, TP);
    }

    /** 트리거 권한이 없는 테넌트에서도 NONE 맞춤은 422 로 실패하지 않는다(오늘 성공하는 재사용 실행을 막지 않게). */
    @Test
    void reconcile_none_missingTriggerWithoutPrivilege_doesNotFail() throws Exception {
      f.triggers.clear();
      when(privilege.hasCreateTrigger(any())).thenReturn(false);

      assertThat(rb.reconcileRebuildBackup(T, FP))
          .isEqualTo(com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState.NONE);
    }

    /**
     * 진행 중 보관 보호 — 이 JVM 이 보관한 뒤(복원·삭제 전) 같은 테이블의 맞춤은 아무 DDL 없이 NONE. 다른 OID 의 복원 실패로는 보호가 풀리지 않고,
     * 이 실행의 복원이 끝나면 다시 판정한다.
     */
    @Test
    void reconcile_inFlightBackup_noneWithoutDdl_untilOwnRestoreEnds() throws Exception {
      long oid = rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);
      int before = f.log.size();

      assertThat(rb.reconcileRebuildBackup(T, FP))
          .isEqualTo(com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState.NONE);
      assertThat(f.log).as("맞춤이 DDL 커넥션을 쓰지 않음").hasSize(before);
      assertThat(f.tables).containsKey(bp);

      assertThatThrownBy(() -> rb.restoreRebuildBackup(T, "rtmp_bak_ignored", oid + 1))
          .isInstanceOf(IllegalStateException.class);
      assertThat(rb.reconcileRebuildBackup(T, FP))
          .as("남의 OID 복원 실패는 보호를 풀지 않음")
          .isEqualTo(com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState.NONE);
      assertThat(f.tables).containsKey(bp);

      rb.restoreRebuildBackup(T, "rtmp_bak_ignored", oid);
      TiberoRebuildBackup.moveToBackup(f.connection(), S, T, FP); // 이후 다른 프로세스가 남긴 흔적
      assertThat(rb.reconcileRebuildBackup(T, FP))
          .isEqualTo(com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState.RESTORED);
    }

    /** 롤백 보상이 끝나면 진행 중 보호도 풀린다. */
    @Test
    void reconcile_afterRollbackCompensation_judgesAgain() throws Exception {
      TransactionSynchronizationManager.initSynchronization();
      rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);
      complete(TransactionSynchronization.STATUS_ROLLED_BACK);
      TiberoRebuildBackup.moveToBackup(f.connection(), S, T, FP);

      assertThat(rb.reconcileRebuildBackup(T, FP))
          .isEqualTo(com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState.RESTORED);
    }

    /** 데이터셋 삭제·재생성 직전의 무조건 정리(null)도 진행 중 보호를 푼다 — 겹친 재생성은 나중 실행이 이긴다(PG 와 같다). */
    @Test
    void dropNull_releasesInFlight() throws Exception {
      rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP);
      rb.dropRebuildBackup(T, "rtmp_bak_ignored", null);
      rb.createTable(T, newColumns());
      TiberoRebuildBackup.moveToBackup(f.connection(), S, T, FP);

      assertThat(rb.reconcileRebuildBackup(T, FP))
          .isEqualTo(com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState.RESTORED);
    }

    /** 보관이 중간에 실패하면 원래 예외가 올라가고, 도우미의 트리거 되살리기가 실패했어도 저장소가 트리거를 다시 보장한다. 진행 중 집합에도 넣지 않는다. */
    @Test
    void moveFails_originalExceptionPropagates_triggerReEnsured_notInFlight() {
      int[] triggerCreates = {0};
      f.failOn =
          sql ->
              (sql.startsWith("ALTER TABLE") && sql.contains(" RENAME TO "))
                  || (sql.startsWith("CREATE OR REPLACE TRIGGER") && triggerCreates[0]++ == 0);

      assertThatThrownBy(() -> rb.moveToRebuildBackup(T, "rtmp_bak_ignored", FP))
          .hasRootCauseMessage("주입된 실패");

      f.failOn = sql -> false;
      assertThat(f.tables).containsOnlyKeys(TP);
      assertThat(f.triggers).containsEntry(trigger, TP);
      int before = f.log.size();
      assertThat(rb.reconcileRebuildBackup(T, FP))
          .isEqualTo(com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState.NONE);
      assertThat(f.log).hasSize(before);
    }
  }
}
