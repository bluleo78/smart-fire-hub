package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Tibero 문장 가드(허용 문장·위험 이름·타 스키마 호출)를 공유 벡터와 개별 단위 테스트로 고정한다. */
class TiberoSqlGuardTest {

  private static final JsonNode FIXTURE = load();

  private static JsonNode load() {
    try (InputStream in =
        TiberoSqlGuardTest.class.getResourceAsStream("/fixtures/tibero-sql-lexical-vectors.json")) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("Tibero 어휘 벡터 픽스처를 읽지 못했다", e);
    }
  }

  /** 두 검사를 모두 통과해야 허용으로 본다. */
  private static void check(String sql, String tenantSchema) {
    TiberoSqlGuard.requireAllowedStatement(sql);
    TiberoSqlGuard.requireNoForeignSchemaCalls(sql, tenantSchema);
  }

  @Test
  void vectors() {
    JsonNode cases = FIXTURE.get("guard");
    assertThat(cases.size()).isGreaterThanOrEqualTo(20);
    for (JsonNode c : cases) {
      String name = c.get("name").asText();
      String sql = c.get("sql").asText();
      String schema = c.get("tenantSchema").asText();
      if (c.get("allowed").asBoolean()) {
        try {
          check(sql, schema);
        } catch (RuntimeException e) {
          throw new AssertionError(name + " 는 허용되어야 한다: " + e.getMessage(), e);
        }
      } else {
        assertThatThrownBy(() -> check(sql, schema))
            .as(name)
            .isInstanceOf(TiberoSqlGuard.Rejected.class);
      }
    }
  }

  @Test
  void rejectionMessage_isKorean_namesFunction() {
    assertThatThrownBy(
            () -> TiberoSqlGuard.requireAllowedStatement("SELECT utl_http.request('x') FROM DUAL"))
        .isInstanceOf(TiberoSqlGuard.Rejected.class)
        .hasMessage("Tibero 에서 허용하지 않는 함수·패키지(UTL_HTTP)가 포함되어 있습니다.");
    assertThatThrownBy(
            () -> TiberoSqlGuard.requireAllowedStatement("SELECT 1 FROM DUAL; SELECT 2 FROM DUAL"))
        .hasMessage("Tibero 실행 SQL 은 한 문장만 허용됩니다.");
    assertThatThrownBy(() -> TiberoSqlGuard.requireAllowedStatement("DROP TABLE t"))
        .hasMessage("Tibero 실행 SQL 은 SELECT·WITH·INSERT·UPDATE·DELETE·MERGE 로 시작해야 합니다.");
  }

  /** HTP·HTF 는 접두가 아니라 정확히 그 이름의 식별자만 거부한다(스펙 §5.4 개정). */
  @Test
  void htpHtf_areRejectedOnlyAsExactWords() {
    TiberoSqlGuard.requireAllowedStatement("SELECT htf_rate, HTPRICE, htfoo FROM t");
    assertThatThrownBy(() -> TiberoSqlGuard.requireAllowedStatement("SELECT HTP.p(1) FROM DUAL"))
        .isInstanceOf(TiberoSqlGuard.Rejected.class)
        .hasMessage("Tibero 에서 허용하지 않는 함수·패키지(HTP)가 포함되어 있습니다.");
    assertThatThrownBy(
            () -> TiberoSqlGuard.requireAllowedStatement("SELECT htf.escape_sc('x') FROM DUAL"))
        .isInstanceOf(TiberoSqlGuard.Rejected.class)
        .hasMessage("Tibero 에서 허용하지 않는 함수·패키지(HTF)가 포함되어 있습니다.");
  }

  /** 인용 소문자 스키마는 테넌트 스키마가 아니다 — 메시지에 원래 이름(소문자)을 싣는다. */
  @Test
  void quotedLowercaseSchemaCall_isForeign() {
    assertThatThrownBy(
            () ->
                TiberoSqlGuard.requireNoForeignSchemaCalls(
                    "SELECT \"genia_t5\" /* c */ . f(1) FROM DUAL", "GENIA_T5"))
        .isInstanceOf(TiberoSqlGuard.Rejected.class)
        .hasMessage("테넌트 스키마가 아닌 스키마(genia_t5)의 함수 호출은 허용되지 않습니다.");
  }

  @Test
  void foreignSchemaCall_messageNamesSchema() {
    assertThatThrownBy(
            () ->
                TiberoSqlGuard.requireNoForeignSchemaCalls(
                    "SELECT other_schema.fn(1) FROM DUAL", "GENIA_T5"))
        .isInstanceOf(TiberoSqlGuard.Rejected.class)
        .hasMessageContaining("OTHER_SCHEMA");
  }
}
