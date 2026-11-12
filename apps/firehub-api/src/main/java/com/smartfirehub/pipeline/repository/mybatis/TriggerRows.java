package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;
import java.time.LocalDateTime;

/** TriggerMapper·TriggerTenantResolverMapper 의 평평한 행·INSERT 홀더 모음(WD-708). */
public final class TriggerRows {

  private TriggerRows() {}

  /** 트리거 한 줄 — config·trigger_state 는 JSON 문자열 그대로(구현이 맵으로 푼다). */
  public record TriggerRow(
      Long id,
      Long pipelineId,
      String triggerType,
      String name,
      String description,
      boolean isEnabled,
      String configJson,
      String stateJson,
      Long createdBy,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /** 잠근 행의 수정 시각 — id 를 함께 읽어 「행 없음」과 「수정 시각이 빈 행」을 가른다. */
  public record VersionRow(Long id, LocalDateTime updatedAt) {}

  /** 감시 목록 정리(removeWatchedDataset·clearPollSnapshot)용 잠근 행 — 자바에서 고쳐 쓸 열만. */
  public record WatchRow(Long id, boolean isEnabled, String configJson, String stateJson) {}

  /**
   * 트리거 INSERT 파라미터 겸 생성 키 홀더. Tibero 에는 RETURNING 이 없어 jOOQ 의 {@code returning(ID)} 대신 JDBC 생성 키가
   * {@code id} 에 써 넣는다.
   */
  public static class TriggerInsert extends GeneratedId {
    private final Long pipelineId;
    private final String triggerType;
    private final String name;
    private final String description;
    private final String configJson;
    private final Long createdBy;

    public TriggerInsert(
        Long pipelineId,
        String triggerType,
        String name,
        String description,
        String configJson,
        Long createdBy) {
      this.pipelineId = pipelineId;
      this.triggerType = triggerType;
      this.name = name;
      this.description = description;
      this.configJson = configJson;
      this.createdBy = createdBy;
    }

    public Long getPipelineId() {
      return pipelineId;
    }

    public String getTriggerType() {
      return triggerType;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    public String getConfigJson() {
      return configJson;
    }

    public Long getCreatedBy() {
      return createdBy;
    }
  }

  /**
   * 외부 식별자 해석 결과 행. PG 는 정책 우회 함수가 (trigger_id, tenant_id) 를 결과 집합으로, Tibero 는 {@code FH_TENANT}
   * 함수가 테넌트 id 하나를 OUT 인자로 돌려준다 — 한 매퍼 시그니처로 두려고 가변 객체로 둔다(NotificationOutboxMapper.TenantIdsQuery
   * 와 같은 방식). Tibero 는 {@code tenantId} 만 채워지고 트리거 id 는 구현이 RLS 아래에서 다시 읽는다(스펙 D2).
   */
  public static class RefLookup {
    private final String identifier;
    private Long tenantId;

    public RefLookup(String identifier) {
      this.identifier = identifier;
    }

    public String getIdentifier() {
      return identifier;
    }

    public Long getTenantId() {
      return tenantId;
    }

    public void setTenantId(Long tenantId) {
      this.tenantId = tenantId;
    }
  }

  /** PG 해석 함수의 결과 한 줄. */
  public record RefRow(Long triggerId, Long tenantId) {}
}
