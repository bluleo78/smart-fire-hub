package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link ResultColumnNames} 가 공용 픽스처({@code fixtures/result-column-name-vectors.json})와 같은 결과를 내는지
 * 본다(WD-85).
 *
 * <p>executor 의 {@code tests/test_result_column_name_vectors.py} 가 같은 파일로 Python {@code
 * _unique_column_names} 를 검증한다 — 운영(executor 경로)과 직접 실행 경로가 같은 이름을 돌려줘야 저장한 차트가 어느 경로에서든 같은 컬럼을
 * 찾는다.
 */
class ResultColumnNamesTest {

  private static final JsonNode FIXTURE = loadFixture();

  private static JsonNode loadFixture() {
    try (InputStream in =
        ResultColumnNamesTest.class.getResourceAsStream(
            "/fixtures/result-column-name-vectors.json")) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("결과 컬럼 이름 시험 벡터 픽스처를 읽지 못했다", e);
    }
  }

  private static List<String> strings(JsonNode array) {
    List<String> list = new ArrayList<>();
    array.forEach(n -> list.add(n.asText()));
    return list;
  }

  static Stream<Arguments> uniqueVectors() {
    List<Arguments> rows = new ArrayList<>();
    FIXTURE
        .get("unique")
        .forEach(v -> rows.add(Arguments.of(strings(v.get("in")), strings(v.get("out")))));
    return rows.stream();
  }

  @Test
  void fixtureHasVectors() {
    // 픽스처가 비면 아래 파라미터 테스트가 공허하게 통과한다
    assertThat(FIXTURE.get("unique").size()).isGreaterThanOrEqualTo(8);
  }

  @ParameterizedTest
  @MethodSource("uniqueVectors")
  void uniqueMatchesFixture(List<String> in, List<String> out) {
    assertThat(ResultColumnNames.unique(in)).containsExactlyElementsOf(out);
  }
}
