package com.smartfirehub.analytics.dto;

import java.time.LocalDateTime;

/**
 * 워크스페이스 전체 분석 자산(대시보드·차트·저장 쿼리) 목록의 행 1건 — analytics:admin 전용(WD-199).
 *
 * <p>관리자가 남의 자산을 정리(공유 해제·삭제)할 수 있게 하는 <b>최소 정보만</b> 담는다. SQL 원문·차트 설정·위젯 구성·설명은 싣지 않는다 — 작성자의 데이터
 * 권한으로 만든 내용이 관리자에게 새면 안 되기 때문이다. 필드를 늘릴 때는 이 원칙을 먼저 확인한다.
 *
 * @param id 자산 id (공유 해제·삭제 대상 지정용)
 * @param name 자산 이름
 * @param ownerId 작성자 id
 * @param ownerName 작성자 이름(계정이 지워졌으면 null)
 * @param ownerActive 작성자가 지금 이 워크스페이스의 활성 멤버인가 — 계정 비활성·멤버십 정지·워크스페이스 제거면 false(정리 대상 판단용)
 * @param isShared 공유 여부
 * @param updatedAt 마지막 수정 시각
 */
public record AnalyticsAdminItemResponse(
    Long id,
    String name,
    Long ownerId,
    String ownerName,
    boolean ownerActive,
    boolean isShared,
    LocalDateTime updatedAt) {}
