package com.smartfirehub.global.platformdb.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.global.exception.SerializationException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PlatformJson 규칙 — null 은 null, 깨진 입력·직렬화 실패는 SerializationException(빈 값 대체는 호출자 몫, WD-708). */
class PlatformJsonTest {

  private final ObjectMapper om = new ObjectMapper();

  @Test
  @DisplayName("toMap — 객체 JSON 은 맵, null 은 null, 깨진 JSON·배열은 라벨을 담은 SerializationException")
  void toMap() {
    assertThat(PlatformJson.toMap(om, "{\"a\":1,\"b\":{\"c\":\"d\"}}", "cfg"))
        .isEqualTo(Map.of("a", 1, "b", Map.of("c", "d")));
    assertThat(PlatformJson.toMap(om, null, "cfg")).isNull();
    assertThatThrownBy(() -> PlatformJson.toMap(om, "{broken", "cfg"))
        .isInstanceOf(SerializationException.class)
        .hasMessage("Failed to parse cfg JSON");
    assertThatThrownBy(() -> PlatformJson.toMap(om, "[1]", "cfg"))
        .isInstanceOf(SerializationException.class);
  }

  @Test
  @DisplayName("toObjectNode — 객체는 고칠 수 있는 노드, null·객체 아님은 null, 깨진 JSON 은 SerializationException")
  void toObjectNode() {
    ObjectNode node = PlatformJson.toObjectNode(om, "{\"datasetIds\":[1,2]}", "config");
    assertThat(node).isNotNull();
    node.put("x", 1);
    assertThat(node.get("datasetIds").size()).isEqualTo(2);
    assertThat(PlatformJson.toObjectNode(om, null, "config")).isNull();
    assertThat(PlatformJson.toObjectNode(om, "[1]", "config")).isNull();
    assertThat(PlatformJson.toObjectNode(om, "5", "config")).isNull();
    assertThatThrownBy(() -> PlatformJson.toObjectNode(om, "{", "config"))
        .isInstanceOf(SerializationException.class)
        .hasMessage("Failed to parse config JSON");
  }

  @Test
  @DisplayName("write — 맵·노드를 JSON 텍스트로, null 은 null, 직렬화 실패는 라벨을 담은 SerializationException")
  void write() {
    assertThat(PlatformJson.write(om, Map.of("k", "v"), "detail")).isEqualTo("{\"k\":\"v\"}");
    assertThat(PlatformJson.write(om, om.createObjectNode().put("n", 1), "detail"))
        .isEqualTo("{\"n\":1}");
    assertThat(PlatformJson.write(om, null, "detail")).isNull();
    // 게터 없는 빈 빈(bean)은 Jackson 기본 설정에서 직렬화 실패다.
    assertThatThrownBy(() -> PlatformJson.write(om, new Object(), "detail"))
        .isInstanceOf(SerializationException.class)
        .hasMessage("Failed to serialize detail");
  }
}
