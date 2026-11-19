package com.smartfirehub.embedding.config;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code tenant_settings.embedding.config} JSON 문서 형태의 단일 출처.
 *
 * <p>형태: {@code {v:1, provider, model, baseUrl, dimension, hosting, secret:{apiKey:<암호문>}}}. {@code
 * hosting} 은 공급자 호스팅 위치 선언({@code EXTERNAL}|{@code SELF_HOSTED}, 없으면 외부 — S3 §3). 비밀은 {@code
 * secret} 하위에만 두어 {@code AiCredentialDocument} 와 같은 규칙(비밀은 하위 필드, 범용 경로 금지)을 따른다. 암호화·복호화는 이 클래스가
 * 하지 않는다 — {@link EmbeddingConfigService} 한 곳에서만 한다.
 */
final class EmbeddingConfigDocument {

  // Jackson 3(WD-793): 예전 Jackson 2 new ObjectMapper() 와 같은 기본값(속성 정렬 안 함·원시 타입 null 허용 등)으로 만든다.
  private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();
  static final int VERSION = 1;

  private EmbeddingConfigDocument() {}

  /** 파싱 결과. {@code apiKeyCipher} 는 암호문(없으면 빈 문자열). */
  record Parsed(
      EmbeddingProviderType provider,
      String model,
      String baseUrl,
      int dimension,
      String apiKeyCipher,
      String hosting) {}

  static String toJson(
      EmbeddingProviderType provider,
      String model,
      String baseUrl,
      int dimension,
      String apiKeyCipher,
      String hosting) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("v", VERSION);
    root.put("provider", provider.name());
    root.put("model", model);
    root.put("baseUrl", baseUrl);
    root.put("dimension", dimension);
    root.put("hosting", hosting);
    root.putObject("secret").put("apiKey", apiKeyCipher == null ? "" : apiKeyCipher);
    return root.toString();
  }

  /** 문서를 읽는다. 형식이 깨졌거나 provider 를 모르면 IllegalArgumentException. */
  static Parsed parse(String json) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("임베딩 설정 문서를 해석할 수 없습니다", e);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("임베딩 설정 문서가 객체가 아닙니다");
    }
    return new Parsed(
        EmbeddingProviderType.parse(root.path("provider").asString(null)),
        root.path("model").asString(""),
        root.path("baseUrl").asString(""),
        root.path("dimension").asInt(0),
        root.path("secret").path("apiKey").asString(""),
        // 선언 이전에 저장된 문서에는 hosting 이 없다 — 기본 외부(보수적).
        root.path("hosting").asString("EXTERNAL"));
  }
}
