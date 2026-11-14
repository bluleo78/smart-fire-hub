package com.smartfirehub.vectorstore;

/**
 * 별도 벡터 저장소가 지금 응답하지 않는다(접속 불가·제한 시간 초과·서버 오류) — ADR 0002 결정 3·11, WD-724.
 *
 * <p>서비스는 구현체(Qdrant 등)를 모르므로 저장소 장애를 이 포트 수준 예외 하나로 받는다. 검색 서비스는 이것을 잡아 그 단계만 빈 목록으로 돌리고(5xx 를 내지
 * 않는다), 웹·에이전트는 기능 응답의 {@code vectorStoreAvailable=false} 로 「저장소 일시 중단」을 안내한다.
 */
public class VectorStoreUnavailableException extends RuntimeException {

  public VectorStoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
