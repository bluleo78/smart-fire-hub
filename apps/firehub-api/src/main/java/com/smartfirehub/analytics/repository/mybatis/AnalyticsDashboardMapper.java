package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.analytics.repository.mybatis.AnalyticsDashboardRows.DashboardInsert;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsDashboardRows.DashboardRow;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsDashboardRows.ImpactRow;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsRows.VersionRow;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 분석 대시보드(dashboard) MyBatis 매퍼(WD-710). SQL 은 {@code
 * mybatis/analytics/AnalyticsDashboardMapper.xml}. 테넌트는 정책(RLS)이 맡는다.
 *
 * <p>목록 범위 인자(shared·mine)와 검색 패턴은 구현이 미리 정리해 넘긴다 — 빈 검색은 null, 검색은 {@code
 * LikePatternUtils.containsPattern} 으로 이스케이프한 패턴이다. 조회자 id 는 가시성 조각({@code dashboardVisibleTo})이 읽는
 * 이름 {@code userId} 로 받는다. id 목록은 {@code InChunks} 조각으로 받는다.
 */
@PlatformMapper
public interface AnalyticsDashboardMapper {

  /** id 조각의 차트를 위젯으로 쓰는 대시보드별 위젯 수와 가시성 — 이름·id 오름차순. */
  List<ImpactRow> selectImpact(
      @Param("chunks") List<List<Long>> chunks, @Param("userId") Long userId);

  /** 목록 한 쪽 — 수정 시각 내림차순, 같으면 id 내림차순. */
  List<DashboardRow> selectPage(
      @Param("shared") boolean shared,
      @Param("mine") boolean mine,
      @Param("userId") Long userId,
      @Param("pattern") String pattern,
      @Param("limit") int limit,
      @Param("offset") long offset);

  /** 목록과 같은 범위·검색의 건수(jOOQ 처럼 조인 없이 dashboard 만 센다). */
  long count(
      @Param("shared") boolean shared,
      @Param("mine") boolean mine,
      @Param("userId") Long userId,
      @Param("pattern") String pattern);

  /** 단건 — ownerOnly 면 주인만, 아니면 주인 또는 공유. 없으면 null. widgetCount 는 0. */
  DashboardRow selectDetail(
      @Param("id") Long id, @Param("userId") Long userId, @Param("ownerOnly") boolean ownerOnly);

  /** 주인의 대시보드 행을 FOR UPDATE 로 잠그고 id 를 돌려준다 — 없거나 남의 것이면 null. */
  Long lockOwnedId(@Param("id") Long id, @Param("userId") Long userId);

  /** 한 건을 넣고 생성 키를 홀더에 돌려준다. */
  int insert(DashboardInsert row);

  /** 수정 시각을 FOR UPDATE 로 잠그고 읽는다 — 없으면 null. */
  VersionRow lockVersionById(@Param("id") Long id);

  /**
   * 넘긴 필드만 고친다 — null 인 값 인자는 유지. 설명은 set 플래그가 참일 때만 쓰고 값이 null 이면 NULL 로 비운다. 자동 새로고침은 값이 있으면 그 값,
   * 없고 clearAutoRefresh 면 NULL. 수정자·수정 시각은 늘 쓴다.
   */
  int update(
      @Param("id") Long id,
      @Param("name") String name,
      @Param("setDescription") boolean setDescription,
      @Param("description") String description,
      @Param("isShared") Boolean isShared,
      @Param("autoRefreshSeconds") Integer autoRefreshSeconds,
      @Param("clearAutoRefresh") boolean clearAutoRefresh,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedAt") LocalDateTime updatedAt);

  /** 주인의 대시보드를 지운다(위젯은 FK CASCADE). 지운 행 수. */
  int deleteByIdAndOwner(@Param("id") Long id, @Param("userId") Long userId);
}
