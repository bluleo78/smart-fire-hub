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
}
