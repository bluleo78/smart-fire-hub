package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.web.dto.ProbeColumn;
import com.smartfirehub.dbexecutor.web.dto.ProbeResponse;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import org.junit.jupiter.api.Test;

/** /execute/probe: 재검증 후 {@code WHERE 1 = 0} 래핑으로 메타데이터만 읽는다(행은 읽지 않음). */
class ProbeRunnerTest {

  private final FakeJdbc db = new FakeJdbc();
  private final StoreDescriptorClient descriptors = mock(StoreDescriptorClient.class);
  private final ProbeRunner runner =
      new ProbeRunner(
          db.pools,
          new ExecutionTimeouts(
              new ExecutionTimeouts.Sql(50, 55),
              new ExecutionTimeouts.Probe(30),
              new ExecutionTimeouts.Query(30)),
          descriptors);

  ProbeRunnerTest() throws SQLException {}

  /** 컨트롤러처럼 지금부터 전체 기한(30초)을 잡아 탐지한다. */
  private ProbeResponse probe(String query) {
    try (Deadline deadline = new Deadline(30)) {
      return runner.probe(FakeJdbc.DESCRIPTOR, query, deadline);
    }
  }

  @Test
  void wrapsQueryAndReadsMetadataOnly() throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(rs.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(3);
    when(md.getColumnLabel(1)).thenReturn("AMOUNT");
    when(md.getColumnType(1)).thenReturn(Types.NUMERIC);
    when(md.getColumnTypeName(1)).thenReturn("NUMBER");
    when(md.getPrecision(1)).thenReturn(18);
    when(md.getScale(1)).thenReturn(6);
    when(md.getColumnLabel(2)).thenReturn("Name");
    when(md.getColumnType(2)).thenReturn(Types.VARCHAR);
    when(md.getColumnTypeName(2)).thenReturn("VARCHAR");
    when(md.getPrecision(2)).thenReturn(100);
    when(md.getScale(2)).thenReturn(0);
    when(md.getColumnLabel(3)).thenReturn("amount");
    when(md.getColumnType(3)).thenReturn(Types.NUMERIC);
    when(md.getColumnTypeName(3)).thenReturn("NUMBER");
    db.resultSet = rs;

    ProbeResponse res = probe("SELECT amount, \"Name\", 1 AS \"amount\" FROM t -- c\n;");

    assertThat(res.success()).isTrue();
    assertThat(db.events.get(0))
        .isEqualTo(
            "SELECT * FROM (\nSELECT amount, \"Name\", 1 AS \"amount\" FROM t -- c\n) WHERE 1 = 0");
    assertThat(db.queryTimeouts).containsExactly(30);
    // F2: 이름을 바꾸지 않는다 — 따옴표 없는 별칭(AMOUNT)과 따옴표 소문자 별칭(amount)을 api 가 가를 수 있어야 한다.
    assertThat(res.columns())
        .containsExactly(
            new ProbeColumn("AMOUNT", Types.NUMERIC, "NUMBER", 18, 6),
            new ProbeColumn("Name", Types.VARCHAR, "VARCHAR", 100, 0),
            new ProbeColumn("amount", Types.NUMERIC, "NUMBER", 0, 0));
    verify(rs, never()).next();
  }

  /** F7: 대여(로그인) 실패면 기술자 캐시를 지우고 실패로 끝낸다. */
  @Test
  void connectFailure_invalidatesDescriptor() {
    db.connectFailure = new SQLException("login denied");
    ProbeResponse res = probe("SELECT 1 FROM DUAL");
    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("테넌트 저장소에 연결하지 못했습니다.");
    verify(descriptors).invalidate(5L);
  }

  @Test
  void rejectsNonSelectAndDangerousBeforeConnecting() {
    for (String sql :
        new String[] {
          "DELETE FROM \"GENIA_T5\".\"X\"",
          "SELECT DBMS_XMLGEN.getxml('x') FROM DUAL",
          "SELECT 1 FROM DUAL; SELECT 2 FROM DUAL",
          "BEGIN NULL; END;",
        }) {
      ProbeResponse res = probe(sql);
      assertThat(res.success()).as(sql).isFalse();
      assertThat(res.error()).as(sql).isNotBlank();
    }
    assertThat(db.factoryCalls.get()).isZero();
  }

  @Test
  void dbError_isReturnedWithSignedCode() {
    db.handler =
        sql -> {
          throw new SQLException(
              "TBR-8033: Specified schema object was not found.", "42000", -8033);
        };
    ProbeResponse res = probe("SELECT x FROM nope");
    assertThat(res.success()).isFalse();
    assertThat(res.dbError().code()).isEqualTo(-8033);
    assertThat(res.columns()).isNull();
  }
}
