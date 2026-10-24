package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

/**
 * db-executor 의 적재 값 변환·바인딩({@link LoadValues})이 api 의 {@code TiberoValues.bind} 와 같은 JDBC 호출을 내는지
 * 공유 벡터({@code tibero-bind-vectors.json}, api 테스트 리소스)로 고정한다. api 쪽 {@code
 * TiberoValuesBindVectorsTest} 가 같은 파일을 현행 api 동작으로 검증하므로, 한쪽만 바뀌면 둘 중 하나가 깨진다.
 */
class TiberoBindVectorsParityTest {

  private static final ObjectMapper OM = new ObjectMapper();
  private static final JsonNode FIXTURE = load();

  private static JsonNode load() {
    try {
      return OM.readTree(
          Path.of("../firehub-api/src/test/resources/fixtures/tibero-bind-vectors.json").toFile());
    } catch (Exception e) {
      throw new IllegalStateException("Tibero 바인딩 벡터를 읽지 못했다", e);
    }
  }

  @Test
  void bindings_matchApiBehaviour() throws Exception {
    JsonNode cases = FIXTURE.get("bindings");
    assertThat(cases.size()).isGreaterThanOrEqualTo(10);
    for (JsonNode c : cases) {
      String label = c.get("name").asText();
      String appType = c.get("appType").isNull() ? null : c.get("appType").asText();
      // 요청 본문을 Jackson 이 Object 로 읽는 것과 같게 읽는다(숫자는 Integer/Long/Double).
      Object json = OM.treeToValue(c.get("json"), Object.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      LoadValues.bind(ps, 1, LoadValues.coerce(json, appType));
      Invocation inv = Mockito.mockingDetails(ps).getInvocations().iterator().next();
      JsonNode expected = c.get("expected");
      assertThat(inv.getMethod().getName())
          .as(label + " 메서드")
          .isEqualTo(expected.get("method").asText());
      Object arg = inv.getArgument(1);
      assertThat(String.valueOf(arg)).as(label + " 인자").isEqualTo(expected.get("arg").asText());
      if (expected.has("argClass")) {
        assertThat(arg.getClass().getName())
            .as(label + " 인자 클래스")
            .isEqualTo(expected.get("argClass").asText());
      }
    }
  }

  /** api 가 건너뛰는 변환 불가 입력은 db-executor 에서 요청 전체 실패(InvalidValue)다 — fail-closed(벡터 _doc 참조). */
  @Test
  void expectFailure_failClosed() throws Exception {
    JsonNode cases = FIXTURE.get("expectFailure");
    assertThat(cases.size()).isGreaterThanOrEqualTo(4);
    for (JsonNode c : cases) {
      Object json = OM.treeToValue(c.get("json"), Object.class);
      assertThatThrownBy(() -> LoadValues.coerce(json, c.get("appType").asText()))
          .as(c.get("name").asText())
          .isInstanceOf(LoadValues.InvalidValue.class);
    }
  }

  @Test
  void columnNames_matchTiberoNamesPhysical() {
    for (JsonNode c : FIXTURE.get("columnNames")) {
      assertThat(LoadRunner.physicalColumn(c.get("in").asText())).isEqualTo(c.get("out").asText());
    }
  }

  @Test
  void invalidValue_isReportedNotSilentlyNulled() {
    assertThatThrownBy(() -> LoadValues.coerce("abc", "INTEGER"))
        .isInstanceOf(LoadValues.InvalidValue.class);
    assertThatThrownBy(() -> LoadValues.coerce("2024-13-40", "DATE"))
        .isInstanceOf(LoadValues.InvalidValue.class);
  }
}
