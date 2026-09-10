package com.smartfirehub.proactive.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 실행 이력 목록(GET /proactive/jobs/{id}/executions)의 행 1건.
 *
 * <p>{@link ProactiveJobExecutionResponse}와 달리 리포트 본문({@code result.summary}/{@code sections} 등)을
 * 포함하지 않는다. 실행 건수가 많거나 리포트가 길면 목록 응답이 수만 자에 달해 MCP 도구 결과 토큰 한도를 초과하고,
 * 이를 우회하기 위한 극단적인 offset 순차 개별 조회(N+1)를 유발했다 (#604). 리포트 본문이 필요하면
 * {@code get_execution}(단건 상세, {@link ProactiveJobExecutionResponse})으로 조회해야 한다.
 */
public record ProactiveJobExecutionSummaryResponse(
    Long id,
    Long jobId,
    String status,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    String errorMessage,
    List<String> deliveredChannels,
    LocalDateTime createdAt) {}
