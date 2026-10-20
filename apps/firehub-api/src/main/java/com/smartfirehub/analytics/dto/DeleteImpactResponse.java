package com.smartfirehub.analytics.dto;

import java.util.List;

/**
 * 저장 쿼리·차트 삭제 시 DB ON DELETE CASCADE 로 함께 사라지는 항목(WD-86). 삭제 확인 창이 "함께 삭제되는 항목"을 보여 주는 데 쓴다.
 *
 * <p>개수는 가시성과 무관하게 <b>전부</b> 센다 — CASCADE 는 조회자가 볼 수 있는지와 상관없이 지우기 때문이다. 이름은 조회자가 볼 수 있는 항목(본인
 * 소유이거나 공유된 것 — 목록 조회와 같은 규칙)만 싣고, 볼 수 없는 항목은 {@code hidden*Count} 로 개수만 알린다.
 *
 * @param charts 함께 삭제되는 차트 중 조회자가 볼 수 있는 것(차트 삭제 영향에서는 항상 빈 목록)
 * @param hiddenChartCount 함께 삭제되는 차트 중 조회자가 볼 수 없는 것의 개수
 * @param widgetCount 함께 삭제되는 대시보드 위젯 개수(볼 수 없는 대시보드의 위젯 포함)
 * @param dashboards 위젯을 잃는 대시보드 중 조회자가 볼 수 있는 것
 * @param hiddenDashboardCount 위젯을 잃는 대시보드 중 조회자가 볼 수 없는 것의 개수
 */
public record DeleteImpactResponse(
    List<ItemRef> charts,
    int hiddenChartCount,
    int widgetCount,
    List<ItemRef> dashboards,
    int hiddenDashboardCount) {

  /** 응답에 싣는 항목 — id 와 이름만. */
  public record ItemRef(Long id, String name) {}

  /**
   * 리포지토리가 돌려주는 원본 행 — 가시성 판정 결과를 함께 담는다. 응답으로 직렬화하지 않는다(볼 수 없는 이름이 들어 있다). 차트·대시보드 행이 이 계약을 공유해
   * 가시성 분리(이름 vs 개수)를 한 벌의 코드로 한다.
   */
  public interface RawRef {
    Long id();

    String name();

    /** 조회자가 이 항목을 볼 수 있는지(본인 소유 또는 공유). */
    boolean visible();
  }

  /** 함께 삭제되는 차트 원본 행. */
  public record ChartRawRef(Long id, String name, boolean visible) implements RawRef {}

  /**
   * 위젯을 잃는 대시보드 원본 행 — 그 대시보드에서 함께 사라지는 위젯 수를 담는다. 위젯 수 합계와 대시보드 목록을 한 쿼리 결과에서 내어 서로 어긋나지 않게 한다.
   */
  public record DashboardRawRef(Long id, String name, boolean visible, int widgetCount)
      implements RawRef {}

  /** 저장 쿼리 삭제 영향 — 그 쿼리의 차트와, 그 차트들을 쓰는 대시보드 위젯. */
  public static DeleteImpactResponse forQuery(
      List<ChartRawRef> rawCharts, List<DashboardRawRef> rawDashboards) {
    return new DeleteImpactResponse(
        visibleOnly(rawCharts),
        hiddenCount(rawCharts),
        rawDashboards.stream().mapToInt(DashboardRawRef::widgetCount).sum(),
        visibleOnly(rawDashboards),
        hiddenCount(rawDashboards));
  }

  /** 차트 삭제 영향 — 차트 자체가 삭제 대상이라 함께 사라지는 차트는 없고, 그 차트를 쓰는 대시보드 위젯만 있다. */
  public static DeleteImpactResponse forChart(List<DashboardRawRef> rawDashboards) {
    return forQuery(List.of(), rawDashboards);
  }

  /** 볼 수 있는 항목만 id·이름으로 — 볼 수 없는 항목의 이름은 여기서 버린다. */
  private static List<ItemRef> visibleOnly(List<? extends RawRef> refs) {
    return refs.stream().filter(RawRef::visible).map(r -> new ItemRef(r.id(), r.name())).toList();
  }

  private static int hiddenCount(List<? extends RawRef> refs) {
    return (int) refs.stream().filter(r -> !r.visible()).count();
  }
}
