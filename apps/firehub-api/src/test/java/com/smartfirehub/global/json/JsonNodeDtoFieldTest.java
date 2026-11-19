package com.smartfirehub.global.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.graphreview.dto.PendingEntityRequest;
import com.smartfirehub.graphreview.dto.ReviewItemResponse;
import com.smartfirehub.notification.inbound.SlackInboundController.InboundRequest;
import com.smartfirehub.support.AppBootJsonMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP DTO 의 JsonNode 필드가 Jackson 2 때와 같은 값을 받고 내보내는지 확인한다(WD-793).
 *
 * <p>WD-793 전환 중에는 임시 다리(Jackson2JsonNodeBridge)가 이 동작을 맞췄고, DTO 필드가 Jackson 3 JsonNode 로 바뀐 뒤에는
 * Jackson 3 기본 JsonNode (역)직렬화가 같은 동작을 준다. 핵심: 속성이 아예 없으면 자바 null(Slack inbound 에서 NPE → 500 경로
 * 유지), 명시적 JSON null 이면 NullNode, 값이 있으면 같은 트리.
 */
class JsonNodeDtoFieldTest {

  private static JsonMapper mapper;

  /**
   * 운영 HTTP 변환기와 같은 매퍼를 얻는다 — 실제 application.yml 의 spring.jackson.* 와 JacksonCompatConfig 를 건 Boot
   * 매퍼({@link AppBootJsonMapper}).
   */
  @BeforeAll
  static void setUp() {
    mapper = AppBootJsonMapper.create();
  }

  @Test
  void inboundEvent_absent_isJavaNull() throws Exception {
    InboundRequest req = mapper.readValue("{\"teamId\":\"T1\"}", InboundRequest.class);
    assertThat(req.event()).isNull();
  }

  @Test
  void inboundEvent_explicitNull_isNullNode() throws Exception {
    InboundRequest req =
        mapper.readValue("{\"teamId\":\"T1\",\"event\":null}", InboundRequest.class);
    assertThat(req.event()).isNotNull();
    assertThat(req.event().isNull()).isTrue();
  }

  @Test
  void inboundEvent_object_isTree() throws Exception {
    InboundRequest req =
        mapper.readValue(
            "{\"teamId\":\"T1\",\"event\":{\"type\":\"message\",\"n\":3}}", InboundRequest.class);
    assertThat(req.event().path("type").asString()).isEqualTo("message");
    assertThat(req.event().path("n").asInt()).isEqualTo(3);
  }

  @Test
  void pendingEntityProperties_absentIsNull_explicitNullIsNullNode() throws Exception {
    PendingEntityRequest absent =
        mapper.readValue("{\"entityType\":\"A\",\"name\":\"b\"}", PendingEntityRequest.class);
    assertThat(absent.properties()).isNull();
    PendingEntityRequest explicit =
        mapper.readValue(
            "{\"entityType\":\"A\",\"name\":\"b\",\"properties\":null}",
            PendingEntityRequest.class);
    assertThat(explicit.properties().isNull()).isTrue();
  }

  @Test
  void reviewItemPayload_serializesAsRawJson() throws Exception {
    JsonNode node = mapper.readTree("{\"a\":[1,2]}");
    ReviewItemResponse res =
        new ReviewItemResponse(
            1L, "ENTITY", "pending", 2L, null, null, null, node, null, null, null);
    assertThat(mapper.writeValueAsString(res)).contains("\"payload\":{\"a\":[1,2]}");
  }
}
