package com.smartfirehub.analytics.dto;

/**
 * 대시보드 위젯 그리드의 좌표·크기 허용 범위를 한 곳에 모은 상수 (WD-193).
 *
 * <p>위젯 추가(AddWidgetRequest)·단건 수정(UpdateWidgetRequest)·배치 일괄 저장(UpdateWidgetLayoutRequest)이 모두 같은
 * 규칙을 써야 경로마다 저장 가능한 값이 달라지지 않는다. 그래서 Bean Validation 애노테이션이 참조할 수 있는 컴파일 타임 상수로 둔다.
 *
 * <p>근거:
 *
 * <ul>
 *   <li>웹 편집기(DashboardEditorPage)의 react-grid-layout 은 {@code cols={lg:12}} 고정이다 → 열 좌표 X 는 0~11,
 *       너비는 1~12.
 *   <li>높이는 기존 규칙(1~12)을 그대로 유지한다. 웹 그리드의 minW/minH=2 로 올리지 않는 이유는 이미 저장된 너비·높이 1 위젯을 다시 저장할 수 없게
 *       되기 때문이다.
 *   <li>행 좌표 Y 는 그리드에 maxRows 가 없어 상한이 없으므로, 대시보드당 위젯 최대 수(20)를 모두 최대 높이(12)로 세로로 쌓았을 때의
 *       끝(20×12=240)을 상한으로 둔다. 이보다 아래에 놓일 정상 배치는 없다.
 * </ul>
 */
public final class DashboardGridLimits {

  /** 그리드 열 수 — 웹 react-grid-layout cols.lg 와 같다. */
  public static final int GRID_COLUMNS = 12;

  /** 대시보드 하나에 둘 수 있는 위젯 최대 수 — AnalyticsDashboardService 위젯 추가 한도와 같다. */
  public static final int MAX_WIDGETS_PER_DASHBOARD = 20;

  /** 위젯 너비·높이 최소값(격자 칸 수). */
  public static final int MIN_SIZE = 1;

  /** 위젯 너비·높이 최대값(격자 칸 수). */
  public static final int MAX_SIZE = GRID_COLUMNS;

  /** 열 좌표 X 최소값. */
  public static final int MIN_POSITION = 0;

  /** 열 좌표 X 최대값 — 마지막 열(0부터 세므로 열 수 - 1). */
  public static final int MAX_POSITION_X = GRID_COLUMNS - 1;

  /** 행 좌표 Y 최대값 — 위젯 최대 수 × 최대 높이. */
  public static final int MAX_POSITION_Y = MAX_WIDGETS_PER_DASHBOARD * MAX_SIZE;

  private DashboardGridLimits() {}
}
