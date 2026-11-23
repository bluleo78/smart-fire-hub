package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * JsonResponseParser 의 데이터 경로 정규화 시험(WD-940).
 *
 * <p>끈(코드 없이 구성한) API 호출은 데이터 경로를 비워 둘 수 있다. 빈 경로는 루트 「$」로 읽어야 하며, 예전에는 JsonPath 가 빈 경로를 거부해 실행이
 * 실패했다.
 */
class JsonResponseParserTest {

  private final JsonResponseParser parser = new JsonResponseParser();

  /** 정수 필드 하나만 매핑하는 설정 — 모든 시험이 같은 매핑을 쓴다. */
  private final List<ApiCallConfig.FieldMapping> mappings =
      List.of(new ApiCallConfig.FieldMapping("a", "a", "INTEGER", null, null, null));

  private final Map<String, String> columnTypeMap = Map.of("a", "INTEGER");

  /** 빈 데이터 경로(null, 빈 문자열, 공백)는 루트 배열의 각 항목을 한 행으로 읽는다. */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void 빈_데이터_경로는_루트_배열을_행으로_읽는다(String dataPath) {
    String json = "[{\"a\":1},{\"a\":2}]";

    List<Map<String, Object>> rows = parser.parseAndMap(json, dataPath, mappings, columnTypeMap, 0);

    // 같은 입력을 명시적 루트 「$」로 읽은 결과와 똑같아야 한다.
    List<Map<String, Object>> expected = parser.parseAndMap(json, "$", mappings, columnTypeMap, 0);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("a", 1L);
    assertThat(rows.get(1)).containsEntry("a", 2L);
    assertThat(rows).isEqualTo(expected);
  }

  /** 빈 데이터 경로에서 루트가 객체면 그 객체 하나가 한 행이 된다. */
  @Test
  void 빈_데이터_경로와_루트_객체는_한_행() {
    String json = "{\"a\":3}";

    List<Map<String, Object>> rows = parser.parseAndMap(json, "", mappings, columnTypeMap, 0);

    List<Map<String, Object>> expected = parser.parseAndMap(json, "$", mappings, columnTypeMap, 0);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("a", 3L);
    assertThat(rows).isEqualTo(expected);
  }

  /** 빈 데이터 경로에서 루트가 빈 배열이면 0행이다(실패가 아니다). */
  @Test
  void 빈_데이터_경로와_빈_배열은_0행() {
    List<Map<String, Object>> rows = parser.parseAndMap("[]", "", mappings, columnTypeMap, 0);

    assertThat(rows).isEmpty();
  }

  /** WD-1002: 문법이 틀린 JSON 경로는 InvalidPathException 이 아니라 ApiCallException 으로 감싼다. */
  @Test
  void readPath_invalidSyntax_throwsApiCallException() {
    assertThatThrownBy(() -> parser.readPath("{\"total\":3}", "$.[", Object.class))
        .isInstanceOf(ApiCallException.class)
        .hasMessage("JSON 경로 형식이 올바르지 않습니다: $.[");
  }

  /** WD-1002: 평가 중 오류(숫자에 집계 함수)도 ApiCallException 으로 감싸 호출처가 부분 페이지 멈춤으로 넘어가게 한다. */
  @Test
  void readPath_evaluationError_throwsApiCallException() {
    assertThatThrownBy(() -> parser.readPath("{\"total\":3}", "$.total.max()", Object.class))
        .isInstanceOf(ApiCallException.class)
        .hasMessage("JSON 경로를 읽을 수 없습니다: $.total.max()");
  }
}
