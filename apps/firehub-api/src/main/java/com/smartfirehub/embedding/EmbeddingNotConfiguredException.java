package com.smartfirehub.embedding;

/**
 * 현재 테넌트에 임베딩 설정이 없다(#713 — 테넌트 전용, 플랫폼 폴백 없음).
 *
 * <p>{@link EmbeddingException} 하위라 행 검색 HYBRID 의 기존 degraded 경로(EmbeddingException 을 잡아 키워드만)가
 * 그대로 받는다. 전역 핸들러는 502(공급자 장애)가 아니라 409 로 번역한다 — 서버가 아니라 설정이 문제다.
 */
public class EmbeddingNotConfiguredException extends EmbeddingException {

  public static final String MESSAGE = "임베딩이 설정되지 않았습니다 (설정 > 임베딩)";

  public EmbeddingNotConfiguredException() {
    super(MESSAGE);
  }
}
