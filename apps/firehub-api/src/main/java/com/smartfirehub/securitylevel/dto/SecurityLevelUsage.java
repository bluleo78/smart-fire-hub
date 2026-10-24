package com.smartfirehub.securitylevel.dto;

/**
 * 등급별 사용량 — 개수만(설정 관리자가 볼 수 없는 데이터셋 이름을 노출하지 않는다, 스펙 §5-1).
 *
 * @param reportCount 이 등급을 리포트 등급으로 가진 스마트 작업 실행 수(WD-198) — 삭제 시 옮길 등급이 필요한지 판단에 함께 쓴다
 */
public record SecurityLevelUsage(
    Long levelId, long datasetCount, long roleCount, long reportCount) {}
