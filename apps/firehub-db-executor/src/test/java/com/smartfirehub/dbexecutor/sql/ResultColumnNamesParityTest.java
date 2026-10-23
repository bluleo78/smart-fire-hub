package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * db-executor 의 {@link ResultColumnNames} 가 api {@code ResultColumnNames.unique}·Python 실행기 {@code
 * _unique_column_names} 와 같은 공용 고정값({@code result-column-name-vectors.json})에 같은 결과를 내는지 대조한다(WD-85
 * 규칙, WD-76). 파일은 api 테스트 리소스를 모듈 기준 상대 경로로 직접 읽는다(사본을 두지 않는다 — 한쪽만 바뀌면 여기서 깨진다).
 */
class ResultColumnNamesParityTest {

  private static JsonNode load() {
    try {
      return new ObjectMapper()
          .readTree(
              Path.of("../firehub-api/src/test/resources/fixtures/result-column-name-vectors.json")
                  .toFile());
    } catch (Exception e) {
      throw new IllegalStateException("결과 컬럼 이름 벡터 고정값을 읽지 못했다", e);
    }
  }

  @Test
  void uniqueVectors_sameAsApi() {
    JsonNode cases = load().get("unique");
    assertThat(cases.size()).isGreaterThanOrEqualTo(10);
    for (JsonNode c : cases) {
      List<String> in = new ArrayList<>();
      c.get("in").forEach(v -> in.add(v.asText()));
      List<String> out = new ArrayList<>();
      c.get("out").forEach(v -> out.add(v.asText()));
      assertThat(ResultColumnNames.unique(in)).as(in.toString()).containsExactlyElementsOf(out);
    }
  }

  /** /execute/sql(파이프라인) 경로의 readRows 는 이름을 바꾸지 않는다 — 파이프라인 출력이 이 수정 전과 같다. */
  @Test
  void sqlPathReadRows_keepsNamesAsBefore() throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(rs.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(2);
    when(md.getColumnLabel(1)).thenReturn("REGION");
    when(md.getColumnLabel(2)).thenReturn("REGION");
    when(md.getColumnType(1)).thenReturn(Types.VARCHAR);
    when(md.getColumnType(2)).thenReturn(Types.VARCHAR);
    when(rs.next()).thenReturn(false);

    assertThat(SqlRunner.readRows(rs).columns()).containsExactly("region", "region");
  }
}
