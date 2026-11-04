package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.global.dto.LockedRowVersion;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 분석 대시보드(dashboard) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqAnalyticsDashboardRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface AnalyticsDashboardRepository {

  /**
   * 주어진 차트들을 위젯으로 쓰는 대시보드와 대시보드별 위젯 수(WD-86 삭제 영향 범위). 차트를 지우면 CASCADE 가 대시보드 가시성과 무관하게 위젯을 전부 지우므로
   * 사용자 필터를 걸지 않고, 대신 visible 을 함께 돌려 호출자가 볼 수 없는 이름을 버리게 한다. 위젯 수와 대시보드 목록을 한 쿼리(대시보드별 GROUP BY)로
   * 얻어 두 값이 서로 어긋나지 않게 한다. RLS 가 테넌트 범위를 한정하므로 트랜잭션 안에서 불러야 한다.
   */
  List<DeleteImpactResponse.DashboardRawRef> findImpactByChartIds(
      Collection<Long> chartIds, Long userId);

  List<DashboardResponse> findAll(
      String search, Boolean sharedOnly, Boolean mineOnly, Long userId, int page, int size);

  long countAll(String search, Boolean sharedOnly, Boolean mineOnly, Long userId);

  Optional<DashboardResponse> findById(
      Long id, Long userId, List<DashboardResponse.DashboardWidgetResponse> widgets);

  Optional<DashboardResponse> findByIdForOwner(Long id, Long userId);

  /**
   * 소유자 대시보드 행을 {@code SELECT ... FOR UPDATE} 로 잠근다(WD-202). 행이 없거나 남의 것이면 false.
   *
   * <p>위젯 추가는 "개수 세기 → 같은 차트 확인 → 삽입" 순서라, 잠금 없이 동시에 들어오면 모두 같은 개수를 보고 통과해 한도(20개)를 넘기거나 같은 차트가 겹쳐
   * 들어갔다. 대시보드 행을 잠가 같은 대시보드의 위젯 추가를 한 줄로 세운다 — 호출부 트랜잭션이 끝날 때 풀린다. user 를 조인하는 {@link
   * #findByIdForOwner} 에 걸면 사용자 행까지 잠기므로 dashboard 만 읽는 별도 쿼리로 둔다. 테넌트 범위는 다른 쿼리와 같이 RLS 가 건다.
   */
  boolean lockByIdForOwner(Long id, Long userId);

  Long insert(CreateDashboardRequest req, Long userId);

  /**
   * 수정 시각을 행 잠금(FOR UPDATE)과 함께 읽는다 — 편집 충돌(낙관적 잠금) 비교용(WD-364, 파이프라인 WD-353 과 같은 방식).
   *
   * <p>잠금 없이 읽고 비교하면 동시에 들어온 두 저장이 같은 값을 읽어 둘 다 통과한다. 잠금을 잡으면 나중 저장은 앞 저장이 커밋될 때까지 기다렸다가 바뀐 값을 읽어
   * 충돌로 거부된다. 소유자 확인을 마친 뒤에 부른다 — 먼저 부르면 남의 행에 대한 낡은 요청이 404 대신 409 를 받아 존재가 드러난다.
   */
  Optional<LockedRowVersion> lockUpdatedAtById(Long id);

  void update(Long id, UpdateDashboardRequest req, Long userId);

  boolean deleteById(Long id, Long userId);
}
