package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 슬롯 SELECT 권한 조회의 테이블 목록 판(WD-679) 단위 검증 — 재확인이 바꾼 테이블 여럿을 IN 목록으로 다시 읽을 때 질의 모양·바인드 순서·IN 1000개
 * 제한 나누기·빈 목록 처리를 고정한다. 결과 행은 비어 있는 가짜 연결이라 질의만 본다(실서버 동작은 {@code TiberoLiveSlotGrantTest}).
 */
class TiberoCatalogSlotGrantsTest {

  /** 준비된 질의 SQL(순서대로). */
  private final List<String> sqls = new ArrayList<>();

  /** 질의별 바인드(1 부터 채운 순서대로). */
  private final List<List<String>> binds = new ArrayList<>();

  private Connection conn;

  @BeforeEach
  void setUp() throws SQLException {
    conn = mock(Connection.class);
    when(conn.prepareStatement(anyString()))
        .thenAnswer(
            inv -> {
              sqls.add(inv.getArgument(0));
              List<String> b = new ArrayList<>();
              binds.add(b);
              PreparedStatement ps = mock(PreparedStatement.class);
              org.mockito.Mockito.doAnswer(
                      a -> {
                        int idx = a.getArgument(0);
                        assertThat(idx).as("바인드는 1 부터 차례로").isEqualTo(b.size() + 1);
                        b.add(a.getArgument(1));
                        return null;
                      })
                  .when(ps)
                  .setString(anyInt(), anyString());
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              when(ps.executeQuery()).thenReturn(rs);
              return ps;
            });
  }

  /** null 이면 테이블 조건 없이 한 번 읽는다. */
  @Test
  void nullTables_readsAllOnce_withoutTableCondition() throws SQLException {
    TiberoCatalog.slotSelectGrantsAmong(conn, 7L, null);

    assertThat(sqls).hasSize(1);
    assertThat(sqls.get(0)).doesNotContain("TABLE_NAME IN").doesNotContain("TABLE_NAME =");
    assertThat(binds.get(0)).containsExactly("GENIA\\_T7\\_S%");
  }

  /** 빈 목록이면 질의하지 않는다 — 빈 IN 목록({@code IN ()})은 문법 오류다. */
  @Test
  void emptyTables_noQuery() throws SQLException {
    assertThat(TiberoCatalog.slotSelectGrantsAmong(conn, 7L, List.of())).isEmpty();
    assertThat(TiberoCatalog.slotSelectGrantsAmong(conn, List.of("GENIA_T7_S1"), List.of()))
        .isEmpty();
    assertThat(TiberoCatalog.slotSelectGrantsAmong(conn, List.of(), null)).isEmpty();
    assertThat(sqls).isEmpty();
  }

  /** 여러 테이블은 앞쪽 바인드(계정) 뒤에 IN 목록으로 묶는다. 이름은 받은 그대로(대소문자 변환 없음) 바인드한다. */
  @Test
  void tables_boundAfterGrantees_inOrder_asGiven() throws SQLException {
    TiberoCatalog.slotSelectGrantsAmong(
        conn, List.of("GENIA_T7_S1", "GENIA_T7_S2"), List.of("T_A", "bin$x==$0"));

    assertThat(sqls.get(0)).contains("GRANTEE IN (?, ?)").endsWith(" AND TABLE_NAME IN (?, ?)");
    assertThat(binds.get(0)).containsExactly("GENIA_T7_S1", "GENIA_T7_S2", "T_A", "bin$x==$0");
  }

  /** 테이블 하나도 IN 하나짜리 목록으로 읽는다({@code = ?} 와 같은 뜻). */
  @Test
  void singleTable_readsAsOneElementIn() throws SQLException {
    TiberoCatalog.slotSelectGrantsAmong(conn, 7L, List.of("T_A"));

    assertThat(sqls.get(0)).endsWith(" AND TABLE_NAME IN (?)");
    assertThat(binds.get(0)).containsExactly("GENIA\\_T7\\_S%", "T_A");
  }

  /** IN 목록은 {@link TiberoCatalog#IN_LIST_CHUNK} 개씩 나눠 질의한다 — Tibero(Oracle 계열) IN 1000개 제한. */
  @Test
  void longTableList_splitIntoInChunks() throws SQLException {
    List<String> tables = new ArrayList<>();
    for (int i = 0; i <= TiberoCatalog.IN_LIST_CHUNK; i++) {
      tables.add("T_" + i);
    }

    TiberoCatalog.slotSelectGrantsAmong(conn, 7L, tables);

    assertThat(sqls).hasSize(2);
    assertThat(binds.get(0)).hasSize(TiberoCatalog.IN_LIST_CHUNK + 1); // 패턴 + 1000
    assertThat(binds.get(1)).containsExactly("GENIA\\_T7\\_S%", "T_" + TiberoCatalog.IN_LIST_CHUNK);
    assertThat(sqls.get(1)).endsWith(" AND TABLE_NAME IN (?)");
  }
}
