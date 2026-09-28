package com.smartfirehub.embedding.config;

/** 지원 임베딩 provider. VOYAGE 는 팩토리가 예외만 던지던 죽은 선택지라 제거했다(#713). */
public enum EmbeddingProviderType {
  OLLAMA,
  OPENAI;

  static final String MSG_INVALID = "임베딩 provider 는 OLLAMA, OPENAI 중 하나여야 합니다";

  /** 요청·문서의 문자열을 열거형으로. 모르는 값은 400 으로 번역되는 IllegalArgumentException. */
  public static EmbeddingProviderType parse(String raw) {
    if (raw == null || raw.isBlank()) throw new IllegalArgumentException(MSG_INVALID);
    try {
      return valueOf(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(MSG_INVALID);
    }
  }
}
