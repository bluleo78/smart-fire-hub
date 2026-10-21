package com.smartfirehub.proactive.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record ProactiveJobResponse(
    Long id,
    Long userId,
    Long templateId,
    String templateName,
    String name,
    String prompt,
    String cronExpression,
    String timezone,
    Boolean enabled,
    String triggerType,
    Map<String, Object> config,
    LocalDateTime lastExecutedAt,
    LocalDateTime nextExecuteAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    ProactiveJobExecutionResponse lastExecution) {

  /**
   * 마지막 실행 결과만 바꾼 사본을 돌려준다.
   *
   * <p>잡 행 조회와 최근 실행 조회는 서로 다른 테이블이라 서비스에서 따로 읽어 합친다 (WD-134). 레코드 필드가 많아 호출처마다 생성자를 다시 쓰지 않도록 여기 한
   * 곳에 둔다.
   */
  public ProactiveJobResponse withLastExecution(ProactiveJobExecutionResponse execution) {
    return new ProactiveJobResponse(
        id,
        userId,
        templateId,
        templateName,
        name,
        prompt,
        cronExpression,
        timezone,
        enabled,
        triggerType,
        config,
        lastExecutedAt,
        nextExecuteAt,
        createdAt,
        updatedAt,
        execution);
  }
}
