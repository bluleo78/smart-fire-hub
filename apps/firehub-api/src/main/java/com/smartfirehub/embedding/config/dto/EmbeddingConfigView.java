package com.smartfirehub.embedding.config.dto;

/** GET /settings/embedding 응답. 미설정이면 configured=false 이고 나머지는 null/빈 값. 키는 마스킹만. */
public record EmbeddingConfigView(
    boolean configured, String provider, String model, String baseUrl, Integer dimension, String apiKeyMasked) {

  public static EmbeddingConfigView notConfigured() {
    return new EmbeddingConfigView(false, null, null, null, null, "");
  }
}
