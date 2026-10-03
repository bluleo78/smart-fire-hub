package com.smartfirehub.embedding.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code tenant_settings.embedding.config} JSON 문서 형태의 단일 출처.
 *
 * <p>형태: {@code {v:1, provider, model, baseUrl, dimension, secret:{apiKey:<암호문>}}}. 비밀은 {@code
 * secret} 하위에만 두어 {@code AiCredentialDocument} 와 같은 규칙(비밀은 하위 필드, 범용 경로 금지)을 따른다. 암호화·복호화는 이 클래스가
 * 하지 않는다 — {@link EmbeddingConfigService} 한 곳에서만 한다.
 */
final class EmbeddingConfigDocument {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  static final int VERSION = 1;

  private EmbeddingConfigDocument() {}

  /** 파싱 결과. {@code apiKeyCipher} 는 암호문(없으면 빈 문자열). */
  record Parsed(
      EmbeddingProviderType provider,
      String model,
      String baseUrl,
      int dimension,
      String apiKeyCipher) {}

  static String toJson(
      EmbeddingProviderType provider,
      String model,
      String baseUrl,
      int dimension,
      String apiKeyCipher) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("v", VERSION);
    root.put("provider", provider.name());
    root.put("model", model);
    root.put("baseUrl", baseUrl);
    root.put("dimension", dimension);
    root.putObject("secret").put("apiKey", apiKeyCipher == null ? "" : apiKeyCipher);
    return root.toString();
  }

  /** 문서를 읽는다. 형식이 깨졌거나 provider 를 모르면 IllegalArgumentException. */
  static Parsed parse(String json) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("임베딩 설정 문서를 해석할 수 없습니다", e);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("임베딩 설정 문서가 객체가 아닙니다");
    }
    return new Parsed(
        EmbeddingProviderType.parse(root.path("provider").asText(null)),
        root.path("model").asText(""),
        root.path("baseUrl").asText(""),
        root.path("dimension").asInt(0),
        root.path("secret").path("apiKey").asText(""));
  }
}
