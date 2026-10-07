package com.smartfirehub.securitylevel.dto;

/**
 * 자격 저장 전 확인용(목업 s3 잠김 방지 ①②).
 *
 * @param callerLostDatasetCount 저장 후 "본인"이 더 이상 볼 수 없게 되는 데이터셋 수(개수만)
 * @param topLevelRoleCountAfter 저장 후 최상위 등급을 열람할 수 있는 역할 수 — 0 이면 저장이 거부된다
 */
public record ClearancePreviewResponse(
    long callerLostDatasetCount, long topLevelRoleCountAfter, long roleUserCount) {}
