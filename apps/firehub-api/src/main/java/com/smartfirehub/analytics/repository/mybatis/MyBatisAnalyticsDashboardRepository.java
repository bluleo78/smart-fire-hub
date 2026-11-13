package com.smartfirehub.analytics.repository.mybatis;

import static com.smartfirehub.global.platformdb.mybatis.PlatformText.blankToNull;
import static com.smartfirehub.global.platformdb.mybatis.PlatformText.searchPattern;

import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsDashboardRows.DashboardInsert;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsDashboardRows.DashboardRow;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * AnalyticsDashboardRepository 의 MyBatis 구현(WD-710) — {@code JooqAnalyticsDashboardRepository} 와 같은
 * 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 트랜잭션이 없다(서비스 트랜잭션 안에서 불린다). 공유 가시성(소유 또는 공유)은 XML 의 {@code
 * DashboardVisibility.dashboardVisibleTo} 조각 하나로 목록·단건·삭제 영향 판정을 맞춘다(데이터셋 참조와 같은 규칙, WD-143). 위젯
 * 목록은 리포지토리가 읽지 않는다 — 단건 조회는 호출자가 넘긴 목록과 그 크기를, 목록·소유자 단건은 빈 목록을 싣는다(jOOQ 판과 같다).
 */
@PlatformMyBatisRepository
public class MyBatisAnalyticsDashboardRepository implements AnalyticsDashboardRepository {

  private final AnalyticsDashboardMapper mapper;

  public MyBatisAnalyticsDashboardRepository(AnalyticsDashboardMapper mapper) {
    this.mapper = mapper;
  }

  /** 대상 차트를 쓰는 대시보드별 위젯 수·가시성(WD-86). 빈 입력은 쿼리 없이 빈 목록 — jOOQ 판과 같다. */
  @Override
  public List<DeleteImpactResponse.DashboardRawRef> findImpactByChartIds(
      Collection<Long> chartIds, Long userId) {
    if (chartIds.isEmpty()) return List.of();
    return mapper.selectImpact(InChunks.of(chartIds), userId).stream()
        .map(
            r ->
                new DeleteImpactResponse.DashboardRawRef(
                    r.id(), r.name(), r.visible(), r.widgetCount()))
        .toList();
  }

  @Override
  public List<DashboardResponse> findAll(
      String search, Boolean sharedOnly, Boolean mineOnly, Long userId, int page, int size) {
    // jOOQ 판 offset(page * size)과 같은 값 — int 곱 오버플로를 피하려 long 으로 곱한다.
    return mapper
        .selectPage(
            Boolean.TRUE.equals(sharedOnly),
            Boolean.TRUE.equals(mineOnly),
            userId,
            searchPattern(search),
            size,
            (long) page * size)
        .stream()
        .map(r -> toResponse(r, List.of(), r.widgetCount()))
        .toList();
  }

  @Override
  public long countAll(String search, Boolean sharedOnly, Boolean mineOnly, Long userId) {
    return mapper.count(
        Boolean.TRUE.equals(sharedOnly),
        Boolean.TRUE.equals(mineOnly),
        userId,
        searchPattern(search));
  }

  /** 보이는(소유 또는 공유) 대시보드 단건 — 위젯은 호출자가 넘긴 목록, 위젯 수는 그 크기(jOOQ 판과 같다). */
  @Override
  public Optional<DashboardResponse> findById(
      Long id, Long userId, List<DashboardResponse.DashboardWidgetResponse> widgets) {
    return Optional.ofNullable(mapper.selectDetail(id, userId, false))
        .map(r -> toResponse(r, widgets, widgets.size()));
  }

  /** 주인의 대시보드 단건 — 위젯은 빈 목록, 위젯 수는 0(jOOQ 판과 같다). */
  @Override
  public Optional<DashboardResponse> findByIdForOwner(Long id, Long userId) {
    return Optional.ofNullable(mapper.selectDetail(id, userId, true))
        .map(r -> toResponse(r, List.of(), 0));
  }

  @Override
  public boolean lockByIdForOwner(Long id, Long userId) {
    return mapper.lockOwnedId(id, userId) != null;
  }

  @Override
  public Long insert(CreateDashboardRequest req, Long userId) {
    DashboardInsert row =
        new DashboardInsert(
            req.name(), req.description(), req.isShared(), req.autoRefreshSeconds(), userId);
    mapper.insert(row);
    return row.getId();
  }

  @Override
  public Optional<LockedRowVersion> lockUpdatedAtById(Long id) {
    return Optional.ofNullable(mapper.lockVersionById(id))
        .map(row -> new LockedRowVersion(row.updatedAt()));
  }

  /**
   * 넘긴 필드만 고친다 — null 은 「변경 없음」, 설명의 빈 문자열(공백만 포함)은 「비움」(NULL, WD-197). 자동 새로고침은 값이 있으면 그 값, 없고
   * clearAutoRefresh 가 참이면 NULL(#568). 수정자·수정 시각은 늘 쓰고, 수정 시각은 jOOQ 판과 같은 자바 시각을 바인딩한다.
   */
  @Override
  public void update(Long id, UpdateDashboardRequest req, Long userId) {
    mapper.update(
        id,
        req.name(),
        req.description() != null,
        blankToNull(req.description()),
        req.isShared(),
        req.autoRefreshSeconds(),
        Boolean.TRUE.equals(req.clearAutoRefresh()),
        userId,
        LocalDateTime.now());
  }

  @Override
  public boolean deleteById(Long id, Long userId) {
    return mapper.deleteByIdAndOwner(id, userId) > 0;
  }

  /** 조회 행 + 위젯 목록·수 → 응답. */
  private static DashboardResponse toResponse(
      DashboardRow r, List<DashboardResponse.DashboardWidgetResponse> widgets, int widgetCount) {
    return new DashboardResponse(
        r.id(),
        r.name(),
        r.description(),
        r.shared(),
        r.autoRefreshSeconds(),
        widgets,
        widgetCount,
        r.createdByName(),
        r.createdBy(),
        r.createdAt(),
        r.updatedAt());
  }
}
