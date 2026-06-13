package com.smartfirehub.embedding;

/** 임베딩 생성 실패 (서비스 다운, 차원 불일치 등). 조용한 폴백 금지 — 명시적 전파. */
public class EmbeddingException extends RuntimeException {
  public EmbeddingException(String message) {
    super(message);
  }

  public EmbeddingException(String message, Throwable cause) {
    super(message, cause);
  }
}
