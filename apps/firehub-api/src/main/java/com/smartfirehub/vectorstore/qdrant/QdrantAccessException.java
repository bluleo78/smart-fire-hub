package com.smartfirehub.vectorstore.qdrant;

import com.smartfirehub.vectorstore.VectorStoreUnavailableException;

/**
 * Qdrant 호출이 실패했다(접속 불가, 제한 시간 초과, 서버 오류). 색인 구현이 클라이언트 예외를 이 한 종류로 감싼다(WD-720).
 *
 * <p>포트 수준 {@link VectorStoreUnavailableException} 을 이어받아 서비스가 구현체를 몰라도 저장소 장애로 다루게 한다(WD-724). 기동
 * 때 이 예외가 일시 장애인지 설정 오류인지는 {@link QdrantVectorStoreLifecycle#isTransient} 가 gRPC 상태로 가른다.
 */
public class QdrantAccessException extends VectorStoreUnavailableException {

  public QdrantAccessException(String message, Throwable cause) {
    super(message, cause);
  }
}
