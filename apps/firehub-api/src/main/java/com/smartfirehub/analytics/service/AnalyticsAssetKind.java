package com.smartfirehub.analytics.service;

import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.DashboardNotFoundException;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;

/**
 * 워크스페이스 분석 자산 관리(analytics:admin, WD-199)가 다루는 자산 종류.
 *
 * <p>대시보드·차트·저장 쿼리는 테이블 모양(id·name·is_shared·created_by·updated_at·tenant_id)이 같아 관리자 경로를 한 벌의 코드로
 * 처리한다. 종류마다 다른 것(테이블 이름·URL 경로·감사 action·없을 때 던지는 404 예외)만 여기에 모은다.
 */
public enum AnalyticsAssetKind {
  DASHBOARD("dashboard", "dashboards", "ANALYTICS_DASHBOARD", "대시보드"),
  CHART("chart", "charts", "ANALYTICS_CHART", "차트"),
  QUERY("saved_query", "queries", "ANALYTICS_QUERY", "저장 쿼리");

  /** DB 테이블 이름 — 감사 로그 resource 로도 쓴다(스마트 작업의 proactive_job 과 같은 관례). */
  private final String table;

  /** 관리자 API 경로 조각(/api/v1/analytics/admin/{path}) — 작성자 API 경로와 같은 복수형. */
  private final String path;

  /** 감사 action 접두사 — 뒤에 _ADMIN_UNSHARE / _ADMIN_DELETE 가 붙는다. */
  private final String auditPrefix;

  /** 감사 설명 문구에 쓰는 한국어 이름. */
  private final String label;

  AnalyticsAssetKind(String table, String path, String auditPrefix, String label) {
    this.table = table;
    this.path = path;
    this.auditPrefix = auditPrefix;
    this.label = label;
  }

  public String table() {
    return table;
  }

  public String label() {
    return label;
  }

  /** 관리자 공유 해제 감사 action (예: ANALYTICS_CHART_ADMIN_UNSHARE). */
  public String unshareAction() {
    return auditPrefix + "_ADMIN_UNSHARE";
  }

  /** 관리자 삭제 감사 action (예: ANALYTICS_CHART_ADMIN_DELETE). */
  public String deleteAction() {
    return auditPrefix + "_ADMIN_DELETE";
  }

  /** 없거나 다른 워크스페이스 자산일 때 — 작성자 경로와 같은 예외·문구(전역 핸들러가 404)로 답해 존재를 드러내지 않는다. */
  public RuntimeException notFound(Long id) {
    return switch (this) {
      case DASHBOARD -> DashboardNotFoundException.forId(id);
      case CHART -> ChartNotFoundException.forId(id);
      case QUERY -> SavedQueryNotFoundException.forId(id);
    };
  }

  /** URL 경로 조각 → 종류. 컨트롤러가 경로 정규식으로 이미 거르므로 모르는 값은 프로그래밍 오류다. */
  public static AnalyticsAssetKind fromPath(String path) {
    for (AnalyticsAssetKind kind : values()) {
      if (kind.path.equals(path)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("알 수 없는 분석 자산 종류: " + path);
  }
}
