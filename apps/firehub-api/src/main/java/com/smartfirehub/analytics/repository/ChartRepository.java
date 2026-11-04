package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.global.dto.LockedRowVersion;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 분석 차트(chart) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqChartRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface ChartRepository {

  List<ChartResponse> findAll(
      String search,
      String chartType,
      Long savedQueryId,
      Boolean sharedOnly,
      Boolean mineOnly,
      Long userId,
      int page,
      int size);

  long countAll(
      String search,
      String chartType,
      Long savedQueryId,
      Boolean sharedOnly,
      Boolean mineOnly,
      Long userId);

  Optional<ChartResponse> findById(Long id, Long userId);

  Optional<ChartResponse> findByIdForOwner(Long id, Long userId);

  /**
   * 저장 쿼리를 참조하는 <b>모든</b> 차트를 가시성 판정과 함께 돌려준다(WD-86 삭제 영향 범위). 쿼리를 지우면 CASCADE 가 조회자의 가시성과 무관하게 전부
   * 지우므로 사용자 필터를 걸지 않고, 대신 목록 조회와 같은 규칙(본인 소유 또는 공유)으로 visible 을 계산한다 — 호출자가 볼 수 없는 이름을 버린다. RLS 가
   * 테넌트 범위를 한정하므로 트랜잭션 안에서 불러야 한다.
   */
  List<DeleteImpactResponse.ChartRawRef> findImpactRefsBySavedQueryId(
      Long savedQueryId, Long userId);

  /** Returns the saved_query_id for a given chart (no user filter — internal use). */
  Long findSavedQueryId(Long chartId);

  /** Returns raw saved_query sql_text by saved_query id (no user filter — internal use). */
  Optional<String> findSavedQuerySqlTextById(Long savedQueryId);

  /** Returns raw saved_query sql_text for a chart the user can access. */
  Optional<String> findSavedQuerySqlText(Long chartId, Long userId);

  Long insert(CreateChartRequest req, Long userId);

  /**
   * 수정 시각을 행 잠금(FOR UPDATE)과 함께 읽는다 — 편집 충돌(낙관적 잠금) 비교용(WD-364, 파이프라인 WD-353 과 같은 방식).
   *
   * <p>잠금 없이 읽고 비교하면 동시에 들어온 두 저장이 같은 값을 읽어 둘 다 통과한다. 잠금을 잡으면 나중 저장은 앞 저장이 커밋될 때까지 기다렸다가 바뀐 값을 읽어
   * 충돌로 거부된다. 소유자 확인을 마친 뒤에 부른다 — 먼저 부르면 남의 행에 대한 낡은 요청이 404 대신 409 를 받아 존재가 드러난다.
   */
  Optional<LockedRowVersion> lockUpdatedAtById(Long id);

  void update(Long id, UpdateChartRequest req, Long userId);

  /**
   * 주어진 차트 중 {@code ownerId} 가 만든 것만 공유로 바꾼다(WD-194).
   *
   * <p>대시보드를 공유할 때 위젯 차트를 함께 공유하는 용도다. 남이 만든 차트는 그 주인만 공유 여부를 정할 수 있으므로 작성자 조건 없이 바꾸면 남이 비공개로 되돌린
   * 차트가 내 대시보드 저장 한 번으로 주인 모르게 다시 공유된다. 그래서 소유자 인자를 필수로 받는다.
   *
   * <p>이미 공유된 차트는 건드리지 않고, 이번에 실제로 비공개 → 공유로 바뀐 차트의 id → 이름을 돌려준다 — 호출자가 그 차트마다 공유 켜기 감사를 남긴다
   * (WD-464). 결과는 true → true 갱신을 빼도 같다(공유 여부 외 컬럼은 바꾸지 않는다).
   */
  Map<Long, String> shareCharts(List<Long> chartIds, Long ownerId);

  /**
   * 주어진 차트 중 조회자에게 보이는(본인 소유이거나 공유된) 차트 id 만 돌려준다(WD-195).
   *
   * <p>대시보드 위젯 목록에서 볼 수 없는 차트의 이름·종류를 가리는 데 쓴다. 차트 단건·차트 데이터 경로와 같은 {@link #visibleTo} 규칙을 써서 "위젯
   * 목록에는 이름이 보이는데 데이터는 404" 같은 어긋남이 생기지 않게 한다. 위젯 수만큼이 아니라 한 번의 질의로 판정한다.
   */
  Set<Long> findVisibleIds(Collection<Long> chartIds, Long userId);

  /**
   * 주어진 차트 중 {@code ownerId} 가 만든 것을 공유 여부별로 센다(WD-325) — [비공개 수, 공유 수]. 대시보드 공유 안내 문구가 {@link
   * #shareCharts} 와 같은 대상(작성자 = 요청자)을 세도록 같은 조건을 쓴다.
   */
  int[] countOwnedBySharing(List<Long> chartIds, Long ownerId);

  List<Long> findSavedQueryIdsByChartIds(List<Long> chartIds);

  boolean deleteById(Long id, Long userId);
}
