package com.smartfirehub.proactive.dto;

import com.smartfirehub.securitylevel.dto.SecurityLevelSummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ProactiveJobExecutionResponse(
    Long id,
    Long jobId,
    String status,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    String errorMessage,
    Map<String, Object> result,
    List<String> deliveredChannels,
    LocalDateTime createdAt,
    /** 리포트 등급(WD-198) — 실행 동안 읽은 데이터셋의 최고 등급. null = 등급 데이터를 읽지 않음·이전 리포트(지금처럼 열림). */
    SecurityLevelSummary securityLevel) {}
