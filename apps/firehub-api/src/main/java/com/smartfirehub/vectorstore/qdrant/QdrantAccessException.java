package com.smartfirehub.vectorstore.qdrant;

/**
 * Qdrant 호출이 실패했다(접속 불가, 제한 시간 초과, 서버 오류). 색인 구현이 클라이언트 예외를 이 한 종류로 감싼다(WD-720).
 *
 * <p>기능 응답 「저장소 일시 중단」과 웹·에이전트 안내는 WD-724 에서 이 예외를 기준으로 붙인다.
 */
public class QdrantAccessException extends RuntimeException {

  public QdrantAccessException(String message, Throwable cause) {
    super(message, cause);
  }
}
