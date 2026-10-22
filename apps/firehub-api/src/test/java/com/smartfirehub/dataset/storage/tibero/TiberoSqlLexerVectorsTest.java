package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Tibero 어휘 분석기가 공유 벡터 픽스처(tibero-sql-lexical-vectors.json)와 일치하는지 고정한다.
 *
 * <p>firehub-db-executor 도 같은 파일을 읽는다. 한쪽 구현만 바뀌면 어느 한쪽 테스트가 깨진다.
 */
class TiberoSqlLexerVectorsTest {

  private static final JsonNode FIXTURE = load();

  private static JsonNode load() {
    try (InputStream in =
        TiberoSqlLexerVectorsTest.class.getResourceAsStream(
            "/fixtures/tibero-sql-lexical-vectors.json")) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("Tibero 어휘 벡터 픽스처를 읽지 못했다", e);
    }
  }

  @Test
  void vectors() {
    JsonNode cases = FIXTURE.get("lexical");
    assertThat(cases.size()).isGreaterThanOrEqualTo(14);
    for (JsonNode c : cases) {
      String name = c.get("name").asText();
      String sql = c.get("sql").asText();
      assertThat(TiberoSqlLexer.countTopLevelSemicolons(sql))
          .as(name + " / topLevelSemicolons")
          .isEqualTo(c.get("topLevelSemicolons").asInt());
      assertThat(TiberoSqlLexer.firstKeyword(sql))
          .as(name + " / firstKeyword")
          .isEqualTo(c.get("firstKeyword").asText());
      assertThat(TiberoSqlLexer.stripTrailingSemicolon(sql))
          .as(name + " / stripped")
          .isEqualTo(c.get("stripped").asText());
      assertThat(TiberoSqlLexer.hasAlternativeQuoting(sql))
          .as(name + " / alternativeQuoting")
          .isEqualTo(c.get("alternativeQuoting").asBoolean());
    }
  }

  @Test
  void mask_blanksLiteralsAndCommentsButKeepsQuotedIdentifiers() {
    String sql = "SELECT 'a;b', \"c;d\" /* x */ -- y\nFROM DUAL";
    String masked = TiberoSqlLexer.maskLiteralsAndComments(sql);
    assertThat(masked).hasSameSizeAs(sql);
    assertThat(masked).doesNotContain("a;b").doesNotContain("x */").doesNotContain("-- y");
    assertThat(masked).contains("\"c;d\"").contains("FROM DUAL");
  }

  @Test
  void unterminatedLiteral_throws() {
    for (String sql :
        new String[] {"SELECT 'abc", "SELECT \"abc", "SELECT /* abc", "SELECT q'[abc'"}) {
      assertThatThrownBy(() -> TiberoSqlLexer.countTopLevelSemicolons(sql))
          .as(sql)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
