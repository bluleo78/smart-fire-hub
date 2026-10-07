package com.smartfirehub.securitylevel.dto;

/** 등급별 사용량 — 개수만(설정 관리자가 볼 수 없는 데이터셋 이름을 노출하지 않는다, 스펙 §5-1). */
public record SecurityLevelUsage(Long levelId, long datasetCount, long roleCount) {}
