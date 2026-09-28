package com.smartfirehub.embedding.reembed;

/**
 * 같은 테넌트의 재임베딩이 이미 임대를 쥐고 있다. JobRunr 가 이 예외로 잡을 재시도(기본 10회, 지수 백오프)하므로,
 * 앞선 잡이 끝나거나(SUPERSEDED 포함) 임대가 만료되면 이 잡이 이어서 새 설정을 처리한다 — 조용히 포기하지 않는다.
 */
public class ReembedBusyException extends RuntimeException {
  public ReembedBusyException(long tenantId) {
    super("테넌트 " + tenantId + " 의 재임베딩이 이미 진행 중입니다 — 끝난 뒤 다시 시도합니다");
  }
}
