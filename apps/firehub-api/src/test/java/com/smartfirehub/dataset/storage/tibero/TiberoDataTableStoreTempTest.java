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
import com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException;
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
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
   * 러너 TEMP 재생성 보관(WD-238)은 Tibero 에서 지원하지 않는다 — 아무 DDL·DML 도 내지 않고 사용자가 할 일을 알려 주는 한국어 422 로 거부한다(기존 TEMP
   * 와 행은 그대로).
   */
  @Test
  void moveToRebuildBackup_rejectsWithKoreanGuidance_beforeTouchingAnything() {
    assertThatThrownBy(() -> store.moveToRebuildBackup("orders", "rb_backup"))
        .isInstanceOf(DataStoreFeatureUnsupportedException.class)
        .hasMessage(TiberoDataTableStore.TEMP_REBUILD_UNSUPPORTED_MESSAGE);
    verifyNoInteractions(rowStore, mover);
  }

  /** Tibero 는 보관본을 만들지 않으므로 정리는 「지운 것 없음」이다 — 예외를 던지면 Tibero 임시 데이터셋 삭제가 통째로 롤백된다. */
  @Test
  void dropRebuildBackup_hasNothingToDrop_soDatasetDeletionProceeds() {
    assertThat(store.dropRebuildBackup("orders", "rb_backup", null)).isFalse();
    assertThat(store.dropRebuildBackup("orders", "rb_backup", 42L)).isFalse();
    verifyNoInteractions(rowStore, mover);
  }
}
