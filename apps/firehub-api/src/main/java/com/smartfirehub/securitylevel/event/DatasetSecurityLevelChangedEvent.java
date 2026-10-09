package com.smartfirehub.securitylevel.event;

/**
 * 데이터셋 1개의 보안 등급이 바뀌었다(커밋 후 구독 — {@code @TransactionalEventListener(AFTER_COMMIT)}).
 *
 * <p>왜 이벤트인가: 등급 변경의 후속 처리(PYTHON 슬롯 롤 GRANT 동기화, 외부 벡터 정리)가 이 서비스를 직접 부르면 흐름 간 결합이 생긴다. 리스너는 비동기
 * 스레드에서 돌 수 있으므로 tenantId 를 함께 싣는다.
 *
 * @param fromLevelId 변경 전 등급(알 수 없으면 null)
 */
public record DatasetSecurityLevelChangedEvent(
    long tenantId, long datasetId, Long fromLevelId, long toLevelId, Cause cause) {

  /** 변경 원인. */
  public enum Cause {
    /** 사용자가 등급 변경 다이얼로그로 바꿈. */
    MANUAL,
    /** 파이프라인 입력 등급에 따른 자동 상향. */
    AUTO_RAISE,
    /** 러너가 새로 만든 빈 TEMP 를 입력 최대 등급으로 정확히 맞춤(하향 포함). */
    PIPELINE_TEMP_ASSIGN,
    /** 복제 원본 등급 상속. */
    CLONE_INHERIT
  }
}
