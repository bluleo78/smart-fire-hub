package com.smartfirehub.embedding.config.dto;

/**
 * GET /settings/embedding 응답. 미설정이면 configured=false 이고 나머지는 null/빈 값. 키는 마스킹만. {@code hosting} 은
 * 공급자 호스팅 위치 선언("EXTERNAL"|"SELF_HOSTED") — 미설정이면 "EXTERNAL".
 */
public record EmbeddingConfigView(
    boolean configured,
    String provider,
    String model,
    String baseUrl,
    Integer dimension,
    String apiKeyMasked,
    String hosting) {

  public static EmbeddingConfigView notConfigured() {
    return new EmbeddingConfigView(false, null, null, null, null, "", "EXTERNAL");
  }
}
