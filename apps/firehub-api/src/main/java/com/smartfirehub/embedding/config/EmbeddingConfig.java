package com.smartfirehub.embedding.config;

/**
 * 해석된 테넌트 임베딩 설정. {@code apiKey} 는 복호화된 평문이라 로그·응답에 싣지 않는다({@link #toString}).
 *
 * @param dimension 저장 시 probe 로 측정한 차원. 아직 측정 전(prepare 직후)이면 0.
 */
public record EmbeddingConfig(
    EmbeddingProviderType provider, String model, String baseUrl, String apiKey, int dimension) {

  /** 기본 record toString 은 apiKey 평문을 찍는다 — 로그 유출을 막는다. */
  @Override
  public String toString() {
    return "EmbeddingConfig[provider="
        + provider
        + ", model="
        + model
        + ", baseUrl="
        + baseUrl
        + ", dimension="
        + dimension
        + ", apiKey="
        + (apiKey == null || apiKey.isBlank() ? "" : "****")
        + "]";
  }
}
