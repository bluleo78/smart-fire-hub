package com.smartfirehub.pipeline.dto;

import java.time.OffsetDateTime;

/**
 * 스텝 증분 책갈피.
 *
 * @param lastRunAt 마지막 성공 실행의 책갈피(없으면 null → 전체 읽기)
 * @param fullRebuildPending 전체 재생성 예약 여부(true 면 이번 실행은 전체를 읽고 출력을 비운다)
 * @param outputDatasetId 파이프라인 재저장 시 이월 여부 판단에 쓴다 — 출력이 바뀌면 이월하지 않는다.
 */
public record StepCursor(
    OffsetDateTime lastRunAt, boolean fullRebuildPending, Long outputDatasetId) {}
