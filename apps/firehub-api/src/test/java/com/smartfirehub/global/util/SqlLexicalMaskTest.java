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
 * {@link SqlLexicalMask} 가 공용 픽스처({@code fixtures/sql-lexical-vectors.json})와 같은 결과를 내는지 본다(#746).
 *
 * <p>executor 의 {@code tests/test_sql_lexical_vectors.py} 가 같은 파일로 Python {@code _mask_sql}/{@code
 * _normalize_sql} 을 검증한다 — 두 구현의 토큰 규칙이 갈리면 어느 한쪽 테스트가 깨진다. DB·스프링이 필요
 * 없는 순수 단위 테스트다. 실제 PostgreSQL 이 정규화된 SQL 을 받아들이는지는
 * {@code PipelineIncrementalIntegrationTest} 가 러너 관통으로 본다.
 */
class SqlLexicalMaskTest {

  private static final JsonNode FIXTURE = loadFixture();

  private static JsonNode loadFixture() {
    try (InputStream in =
        SqlLexicalMaskTest.class.getResourceAsStream("/fixtures/sql-lexical-vectors.json")) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("SQL 어휘 시험 벡터 픽스처를 읽지 못했다", e);
    }
  }

  static Stream<Arguments> maskVectors() {
    List<Arguments> rows = new ArrayList<>();
    FIXTURE.get("mask").forEach(v -> rows.add(Arguments.of(v.get("sql").asText(), v.get("mask").asText())));
    return rows.stream();
  }

  static Stream<Arguments> stripVectors() {
    List<Arguments> rows = new ArrayList<>();
    FIXTURE
        .get("stripTrailing")
        .forEach(v -> rows.add(Arguments.of(v.get("sql").asText(), v.get("expected").asText())));
    return rows.stream();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("maskVectors")
  void 마스크가_픽스처와_같다(String sql, String expected) {
    assertThat(SqlLexicalMask.mask(sql)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("stripVectors")
  void 끝_주석_세미콜론_제거가_픽스처와_같다(String sql, String expected) {
    assertThat(SqlLexicalMask.stripTrailingCommentsAndSemicolons(sql)).isEqualTo(expected);
  }

  /** 마스크 위치로 원문을 자르는 소비처가 있으므로 길이(UTF-16 단위)가 원문과 같아야 한다 — BMP 밖 문자 포함. */
  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("stripVectors")
  void 마스크는_원문과_길이가_같다(String sql, String ignored) {
    assertThat(SqlLexicalMask.mask(sql)).hasSameSizeAs(sql);
  }

  /** 픽스처가 비어 있으면 위 테스트는 공허하게 통과한다 — 벡터가 실제로 읽혔는지 확인한다. */
  @Test
  void 픽스처에_벡터가_있다() {
    assertThat(FIXTURE.get("mask").size()).isGreaterThanOrEqualTo(10);
    assertThat(FIXTURE.get("stripTrailing").size()).isGreaterThanOrEqualTo(20);
  }
}
