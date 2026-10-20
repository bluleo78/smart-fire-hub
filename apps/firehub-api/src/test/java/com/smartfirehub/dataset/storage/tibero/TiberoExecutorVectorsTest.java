package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * executor(Python)와 공유하는 Tibero 벡터 픽스처(WD-75)가 api 구현과 일치하는지 고정한다.
 *
 * <p>executor 테스트가 같은 {@code fixtures/tibero-executor-vectors.json} 을 읽어 세션 NLS 문장·계정 이름·시퀀스 이름 미러를
 * 검증한다. 한쪽만 바뀌면 여기서 먼저 깨진다.
 */
class TiberoExecutorVectorsTest {

  private static final JsonNode FIXTURE = load();

  private static JsonNode load() {
    try (InputStream in =
        TiberoExecutorVectorsTest.class.getResourceAsStream(
            "/fixtures/tibero-executor-vectors.json")) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("Tibero executor 벡터 픽스처를 읽지 못했다", e);
    }
  }

  @Test
  void sessionInitSql_일치() {
    assertThat(FIXTURE.get("sessionInitSql").asText()).isEqualTo(TiberoDialect.SESSION_INIT_SQL);
  }

  @Test
  void ownerUsername_일치() {
    JsonNode cases = FIXTURE.get("ownerUsername");
    assertThat(cases.size()).isPositive();
    for (JsonNode c : cases) {
      assertThat(DataStoreAccountRole.OWNER.usernameFor(c.get("tenantId").asLong()))
          .isEqualTo(c.get("out").asText());
    }
  }

  @Test
  void sequenceName_일치() {
    JsonNode cases = FIXTURE.get("sequenceName");
    assertThat(cases.size()).isPositive();
    for (JsonNode c : cases) {
      assertThat(TiberoNames.sequenceName(c.get("schema").asText(), c.get("table").asText()))
          .isEqualTo(c.get("out").asText());
    }
  }
}
