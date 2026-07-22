package com.smartfirehub.graphingest.dto;

/** 재추출 필요(stale) 데이터셋 응답 — 최신 적재 버전이 현재 온톨로지 버전보다 낮은 데이터셋. */
public record StaleDatasetResponse(
    long datasetId, String latestIngestedAt, int schemaVersionAtIngest, int currentSchemaVersion) {}
