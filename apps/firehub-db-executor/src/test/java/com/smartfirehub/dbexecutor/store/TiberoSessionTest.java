package com.smartfirehub.dbexecutor.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 세션 초기화 SQL 이 api 와 공유하는 고정값 파일과 같은지 대조한다(모듈 디렉터리 기준 상대 경로). */
class TiberoSessionTest {

  @Test
  void sessionInitSql_matchesSharedVector() throws Exception {
    Path vectors =
        Path.of("../firehub-api/src/test/resources/fixtures/tibero-executor-vectors.json");
    String expected = new ObjectMapper().readTree(vectors.toFile()).get("sessionInitSql").asText();
    assertThat(TiberoSession.SESSION_INIT_SQL).isEqualTo(expected);
  }

  @Test
  void constants() {
    assertThat(TiberoSession.DRIVER_CLASS).isEqualTo("com.tmax.tibero.jdbc.TbDriver");
    assertThat(TiberoSession.jdbcUrl("h", 8629, "tb")).isEqualTo("jdbc:tibero:thin:@h:8629:tb");
  }

  /** 슬롯 풀 세션 고정: NLS 고정 뒤 CURRENT_SCHEMA 를 테넌트 소유 스키마로(WD-78). 다른 스키마 이름은 만들지 않는다. */
  @Test
  void slotSessionInitSql_addsCurrentSchema_andRejectsForeignSchema() {
    String sql = TiberoSession.slotSessionInitSql(5, "GENIA_T5");
    String nls = TiberoSession.SESSION_INIT_SQL;
    assertThat(sql).startsWith(nls.substring(0, nls.length() - "END;".length()));
    assertThat(sql)
        .endsWith("EXECUTE IMMEDIATE 'ALTER SESSION SET CURRENT_SCHEMA = \"GENIA_T5\"'; END;");
    for (String bad :
        new String[] {"GENIA_T6", "GENIA_T5\"'; DROP", "genia_t5", "SYS", null, "GENIA_T5_RO"}) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> TiberoSession.slotSessionInitSql(5, bad))
          .as(String.valueOf(bad))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
