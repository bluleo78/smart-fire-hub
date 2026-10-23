package com.smartfirehub.proactive.dto;

import java.time.LocalDateTime;

/**
 * 워크스페이스 전체 스마트 작업 목록의 행 1건 — proactive:admin 전용(WD-192).
 *
 * <p>관리자가 남의 작업을 정리(끄기·삭제)할 수 있게 하는 <b>최소 정보만</b> 담는다. 프롬프트·설정(config: 수신자·지표 SQL 포함)·리포트 양식·실행
 * 결과·오류 문구는 싣지 않는다 — 작성자의 데이터 권한으로 만든 내용이 관리자에게 새면 안 되기 때문이다. 필드를 늘릴 때는 이 원칙을 먼저 확인한다.
 *
 * @param id 작업 id (끄기·삭제 대상 지정용)
 * @param name 작업 이름
 * @param ownerName 작성자 이름
 * @param enabled 활성 여부
 * @param ownerEligible 작성자가 지금 실행 자격이 있는가 — 없으면 실행 때마다 건너뛴다(WD-166)
 * @param ownerIneligibleReason 자격이 없는 사유 코드(OWNER_INACTIVE·OWNER_NO_PERMISSION), 있으면 null
 * @param nextExecuteAt 다음 실행 시각(UTC 벽시계, 스케줄 미등록이면 null)
 * @param timezone 다음 실행 시각을 작업 기준 시간대로 보여 주기 위한 IANA 시간대(작업 내용이 아니라 표시용)
 * @param lastExecutionStatus 마지막 실행 상태(RUNNING·COMPLETED·FAILED, 이력 없으면 null)
 */
public record ProactiveJobAdminSummaryResponse(
    Long id,
    String name,
    String ownerName,
    Boolean enabled,
    Boolean ownerEligible,
    String ownerIneligibleReason,
    LocalDateTime nextExecuteAt,
    String timezone,
    String lastExecutionStatus) {}
