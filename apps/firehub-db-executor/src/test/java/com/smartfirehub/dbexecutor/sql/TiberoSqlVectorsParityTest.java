package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * api 에서 복사한 어휘 분석기·문장 가드가 api 와 같은 고정값 파일({@code tibero-sql-lexical-vectors.json})에 같은 결과를 내는지
 * 대조한다.
 *
 * <p>api 의 {@code TiberoSqlLexerVectorsTest}·{@code TiberoSqlGuardTest} 와 같은 단언이다. 한쪽 복사본만 바뀌면 여기서
 * 깨진다. 파일은 모듈 디렉터리 기준 상대 경로로 api 테스트 리소스를 직접 읽는다(사본을 두지 않는다).
 */
class TiberoSqlVectorsParityTest {

  private static final JsonNode FIXTURE = load();

  private static JsonNode load() {
    try {
      return new ObjectMapper()
          .readTree(
              Path.of("../firehub-api/src/test/resources/fixtures/tibero-sql-lexical-vectors.json")
                  .toFile());
    } catch (Exception e) {
      throw new IllegalStateException("Tibero 어휘 벡터 고정값을 읽지 못했다", e);
    }
  }

  @Test
  void lexicalVectors_sameAsApi() {
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

  /** allowed=true 면 두 검사 모두 통과, false 면 둘 중 하나가 거부해야 한다(api 와 같은 규칙). */
  @Test
  void guardVectors_sameAsApi() {
    JsonNode cases = FIXTURE.get("guard");
    assertThat(cases.size()).isGreaterThanOrEqualTo(20);
    for (JsonNode c : cases) {
      String name = c.get("name").asText();
      String sql = c.get("sql").asText();
      String schema = c.get("tenantSchema").asText();
      if (c.get("allowed").asBoolean()) {
        assertThatCode(() -> check(sql, schema)).as(name).doesNotThrowAnyException();
      } else {
        assertThatThrownBy(() -> check(sql, schema))
            .as(name)
            .isInstanceOf(TiberoSqlGuard.Rejected.class);
      }
    }
  }

  /**
   * 두 복사본이 의도적으로 다른 지점 — db-executor 복사본만 증분 커서 후보 문장 전체 일치를 허용한다(Ruling 11). 짝: api
   * TiberoSqlGuardTest.guardDivergence_apiRejectsCursorStatement.
   */
  @Test
  void guardDivergence_dbExecutorAllowsCursorStatement() {
    JsonNode cases = FIXTURE.get("guardDivergence");
    assertThat(cases.size()).isGreaterThanOrEqualTo(1);
    for (JsonNode c : cases) {
      assertThat(c.get("dbExecutorAllowed").asBoolean()).isTrue();
      String sql = c.get("sql").asText();
      String schema = c.get("tenantSchema").asText();
      assertThatCode(() -> check(sql, schema))
          .as(c.get("name").asText())
          .doesNotThrowAnyException();
    }
  }

  private static void check(String sql, String tenantSchema) {
    TiberoSqlGuard.requireAllowedStatement(sql);
    TiberoSqlGuard.requireNoForeignSchemaCalls(sql, tenantSchema);
  }
}
