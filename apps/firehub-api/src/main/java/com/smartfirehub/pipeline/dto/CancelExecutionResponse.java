package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;

/**
 * 실행 취소 요청 응답(WD-153).
 *
 * @param executionId 취소를 요청한 실행 id
 * @param status {@code CANCELLED} — 대기 중(PENDING)이라 즉시 취소됐다. {@code RUNNING} — 취소 요청을 기록했고 러너가 다음
 *     체크포인트(스텝 사이·API 페이지 사이·재시도 대기 중·AI 배치 사이 등)에서 멈춘다. 진행 중인 호출·문장은 끝까지(자기 타임아웃까지) 돈다
 * @param cancelRequestedAt 첫 취소 요청 시각(재요청은 첫 요청을 덮지 않는다)
 */
public record CancelExecutionResponse(
    Long executionId, String status, LocalDateTime cancelRequestedAt) {}
