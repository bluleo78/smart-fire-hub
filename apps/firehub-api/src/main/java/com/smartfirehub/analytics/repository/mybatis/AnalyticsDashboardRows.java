package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;
import java.time.LocalDateTime;

/**
 * AnalyticsDashboardMapper 의 평평한 행·INSERT 홀더 모음(WD-710). 잠금 수정 시각은 분석 공용 {@link
 * AnalyticsRows.VersionRow} 를 쓴다.
 */
public final class AnalyticsDashboardRows {

  private AnalyticsDashboardRows() {}

  /**
   * 대시보드 조회 한 행. 위젯 목록은 이 행에 없다 — 단건 조회는 호출자가 넘긴 위젯 목록을, 목록은 빈 목록을 구현이 붙여 {@code DashboardResponse}
   * 를 만든다(생성자 결과 매핑으로 바로 만들지 않는 이유). widgetCount 는 목록에서만 상관 서브쿼리 값이고 단건 문장은 0 이다.
   */
  public record DashboardRow(
      Long id,
      String name,
      String description,
      boolean shared,
      Integer autoRefreshSeconds,
      String createdByName,
      Long createdBy,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      int widgetCount) {}

  /** 삭제 영향 조회 한 행 — 대시보드 id·이름, 조회자에게 보이는지, 대상 차트 위젯 수. */
  public record ImpactRow(Long id, String name, boolean visible, int widgetCount) {}

  /**
   * 대시보드 INSERT 파라미터 겸 생성 키 홀더. Tibero 에는 RETURNING 이 없어 jOOQ 의 {@code returning(ID)} 대신 JDBC 생성 키가
   * {@code id} 에 써 넣는다.
   */
  public static class DashboardInsert extends GeneratedId {
    private final String name;
    private final String description;
    private final boolean shared;
    private final Integer autoRefreshSeconds;
    private final Long createdBy;

    public DashboardInsert(
        String name,
        String description,
        boolean shared,
        Integer autoRefreshSeconds,
        Long createdBy) {
      this.name = name;
      this.description = description;
      this.shared = shared;
      this.autoRefreshSeconds = autoRefreshSeconds;
      this.createdBy = createdBy;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    /** 공유 여부 — XML 의 boolLiteral 조각이 {@code shared} 로 읽는다. */
    public boolean isShared() {
      return shared;
    }

    public Integer getAutoRefreshSeconds() {
      return autoRefreshSeconds;
    }

    public Long getCreatedBy() {
      return createdBy;
    }
  }
}
