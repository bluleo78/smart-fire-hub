package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;
import java.time.LocalDateTime;

/** ProactiveJobMapper 의 평평한 행·INSERT 홀더 모음(WD-709). config 는 JSON 문자열 그대로(구현이 맵으로 푼다). */
public final class ProactiveJobRows {

  private ProactiveJobRows() {}

  /** 작업 한 줄 — 양식 이름은 report_template LEFT JOIN 으로 함께 읽는다. */
  public record JobRow(
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
      String configJson,
      LocalDateTime lastExecutedAt,
      LocalDateTime nextExecuteAt,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /** 잠근 행의 수정 시각 — id 를 함께 읽어 「행 없음」과 「수정 시각이 빈 행」을 가른다. */
  public record VersionRow(Long id, LocalDateTime updatedAt) {}

  /** 작업 INSERT 파라미터 겸 생성 키 홀더(Tibero 에는 RETURNING 이 없어 JDBC 생성 키가 id 에 써 넣는다). */
  public static class JobInsert extends GeneratedId {
    private final Long userId;
    private final String name;
    private final String prompt;
    private final Long templateId;
    private final String cronExpression;
    private final String timezone;
    private final boolean enabled;
    private final String triggerType;
    private final String configJson;

    public JobInsert(
        Long userId,
        String name,
        String prompt,
        Long templateId,
        String cronExpression,
        String timezone,
        boolean enabled,
        String triggerType,
        String configJson) {
      this.userId = userId;
      this.name = name;
      this.prompt = prompt;
      this.templateId = templateId;
      this.cronExpression = cronExpression;
      this.timezone = timezone;
      this.enabled = enabled;
      this.triggerType = triggerType;
      this.configJson = configJson;
    }

    public Long getUserId() {
      return userId;
    }

    public String getName() {
      return name;
    }

    public String getPrompt() {
      return prompt;
    }

    public Long getTemplateId() {
      return templateId;
    }

    public String getCronExpression() {
      return cronExpression;
    }

    public String getTimezone() {
      return timezone;
    }

    public boolean isEnabled() {
      return enabled;
    }

    public String getTriggerType() {
      return triggerType;
    }

    public String getConfigJson() {
      return configJson;
    }
  }
}
