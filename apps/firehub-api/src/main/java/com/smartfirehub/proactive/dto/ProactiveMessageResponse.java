package com.smartfirehub.proactive.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 프로액티브 메시지 응답 DTO — jobId는 execution JOIN으로 조회한 상위 잡 ID.
 *
 * <p>{@code canViewReport}(WD-162): 이 메시지를 받은 사람이 리포트 전문을 열 수 있는지 — 서버 열람 규칙(작업 작성자만)과 같은 조건(수신자 =
 * 작업 주인)으로 계산한다. 작성자가 아닌 수신자에게는 false 라 화면이 「리포트 보기」 대신 요약과 안내를 보인다.
 */
public record ProactiveMessageResponse(
    Long id,
    Long userId,
    Long jobId,
    Long executionId,
    String title,
    Map<String, Object> content,
    String messageType,
    Boolean read,
    LocalDateTime readAt,
    String jobName,
    LocalDateTime createdAt,
    boolean canViewReport) {}
