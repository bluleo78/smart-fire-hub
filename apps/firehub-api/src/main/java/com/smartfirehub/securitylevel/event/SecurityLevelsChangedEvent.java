package com.smartfirehub.securitylevel.event;

/**
 * 테넌트의 등급 정의가 바뀌었다(추가·수정·삭제·순서 변경). 커밋 후 구독. 등급 삭제의 데이터셋 일괄 이동은 데이터셋별 이벤트 대신 이 이벤트(DELETED) 하나로 알린다
 * — 구독자는 테넌트 전체를 다시 맞춘다.
 *
 * @param levelId 대상 등급(REORDERED 면 null)
 */
public record SecurityLevelsChangedEvent(long tenantId, Kind kind, Long levelId) {

  /** 변경 종류. UPDATED 는 정책(allowlist_required·ai_policy 등) 변경을 포함한다. */
  public enum Kind {
    CREATED,
    UPDATED,
    DELETED,
    REORDERED
  }
}
