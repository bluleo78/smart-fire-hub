package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;
import java.time.LocalDateTime;

/**
 * ChartMapper 의 평평한 행·INSERT 홀더 모음(WD-710). id·이름, 잠금 수정 시각, 공유 여부별 수는 저장 쿼리와 모양이 같아 {@link
 * SavedQueryRows} 의 것을 그대로 쓴다.
 */
public final class ChartRows {

  private ChartRows() {}

  /**
   * 차트 조회 한 행 — config 는 JSON 텍스트 그대로 받아 구현이 맵으로 바꾼다(null·빈 값 → {@code {}}, 깨진 값 → 빈 맵, jOOQ 판과 같은
   * 처리). 생성자 결과 매핑으로 바로 {@code ChartResponse} 를 만들지 않는 이유가 이 변환이다.
   */
  public record ChartRow(
      Long id,
      String name,
      String description,
      Long savedQueryId,
      String savedQueryName,
      String chartType,
      String configJson,
      boolean shared,
      String createdByName,
      Long createdBy,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      long dashboardCount) {}

  /** 목록 한 행(WD-733) — {@link ChartRow} 에서 config 를 뺀 것. 목록 문장이 config 를 읽지 않는다. */
  public record ChartListRow(
      Long id,
      String name,
      String description,
      Long savedQueryId,
      String savedQueryName,
      String chartType,
      boolean shared,
      String createdByName,
      Long createdBy,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      long dashboardCount) {}

  /** 삭제 영향 조회 한 행 — 이름과 조회자에게 보이는지(소유 또는 공유). */
  public record ImpactRow(Long id, String name, boolean visible) {}

  /**
   * 차트 INSERT 파라미터 겸 생성 키 홀더. Tibero 에는 RETURNING 이 없어 jOOQ 의 {@code returning(ID)} 대신 JDBC 생성 키가
   * {@code id} 에 써 넣는다. config 는 이미 직렬화한 JSON 텍스트다.
   */
  public static class ChartInsert extends GeneratedId {
    private final String name;
    private final String description;
    private final Long savedQueryId;
    private final String chartType;
    private final String configJson;
    private final boolean shared;
    private final Long createdBy;

    public ChartInsert(
        String name,
        String description,
        Long savedQueryId,
        String chartType,
        String configJson,
        boolean shared,
        Long createdBy) {
      this.name = name;
      this.description = description;
      this.savedQueryId = savedQueryId;
      this.chartType = chartType;
      this.configJson = configJson;
      this.shared = shared;
      this.createdBy = createdBy;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    public Long getSavedQueryId() {
      return savedQueryId;
    }

    public String getChartType() {
      return chartType;
    }

    /** config JSON 텍스트 — XML 의 jsonValue 조각이 {@code configJson} 으로 읽는다. */
    public String getConfigJson() {
      return configJson;
    }

    /** 공유 여부 — XML 의 boolLiteral 조각이 {@code shared} 로 읽는다. */
    public boolean isShared() {
      return shared;
    }

    public Long getCreatedBy() {
      return createdBy;
    }
  }
}
