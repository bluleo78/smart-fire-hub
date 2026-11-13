package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.analytics.repository.mybatis.AnalyticsRows.IdName;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsRows.SharingCounts;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsRows.VersionRow;
import com.smartfirehub.analytics.repository.mybatis.ChartRows.ChartInsert;
import com.smartfirehub.analytics.repository.mybatis.ChartRows.ChartRow;
import com.smartfirehub.analytics.repository.mybatis.ChartRows.ImpactRow;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 분석 차트(chart) MyBatis 매퍼(WD-710). SQL 은 {@code mybatis/analytics/ChartMapper.xml}. 테넌트는 정책(RLS)이
 * 맡는다.
 *
 * <p>목록 범위 인자(shared·mine)와 검색 패턴·종류는 구현이 미리 정리해 넘긴다 — 빈 검색·빈 종류는 null, 검색은 {@code
 * LikePatternUtils.containsPattern} 으로 이스케이프한 패턴이다. id 목록은 {@code InChunks} 조각으로 받는다.
 */
@PlatformMapper
public interface ChartMapper {

  /** 목록 한 쪽 — 수정 시각 내림차순, 같으면 id 내림차순. */
  List<ChartRow> selectPage(
      @Param("shared") boolean shared,
      @Param("mine") boolean mine,
      @Param("userId") Long userId,
      @Param("pattern") String pattern,
      @Param("chartType") String chartType,
      @Param("savedQueryId") Long savedQueryId,
      @Param("limit") int limit,
      @Param("offset") long offset);

  /** 목록과 같은 범위·필터의 건수(jOOQ 처럼 조인 없이 chart 만 센다). */
  long count(
      @Param("shared") boolean shared,
      @Param("mine") boolean mine,
      @Param("userId") Long userId,
      @Param("pattern") String pattern,
      @Param("chartType") String chartType,
      @Param("savedQueryId") Long savedQueryId);

  /** 단건 — ownerOnly 면 주인만, 아니면 주인 또는 공유. 없으면 null. */
  ChartRow selectDetail(
      @Param("id") Long id, @Param("userId") Long userId, @Param("ownerOnly") boolean ownerOnly);

  /** 저장 쿼리를 쓰는 모든 차트 — 이름·id 오름차순, 조회자 가시성은 visible 열로. */
  List<ImpactRow> selectImpactRefs(
      @Param("savedQueryId") Long savedQueryId, @Param("userId") Long userId);

  /** 차트의 saved_query_id(소유 제한 없음) — 없으면 null. */
  Long selectSavedQueryId(@Param("chartId") Long chartId);

  /** 저장 쿼리 SQL(소유 제한 없음) — 없으면 null. */
  String selectSqlTextBySavedQueryId(@Param("savedQueryId") Long savedQueryId);

  /** 조회자가 볼 수 있는(소유 또는 공유) 차트의 저장 쿼리 SQL — 없으면 null. */
  String selectVisibleSqlText(@Param("chartId") Long chartId, @Param("userId") Long userId);

  /** 한 건을 넣고 생성 키를 홀더에 돌려준다. */
  int insert(ChartInsert row);

  /** 수정 시각을 FOR UPDATE 로 잠그고 읽는다 — 없으면 null. */
  VersionRow lockVersionById(@Param("id") Long id);

  /** 넘긴 필드만 고친다 — null 인 값 인자는 유지. 설명은 set 플래그가 참일 때만 쓰고 값이 null 이면 NULL 로 비운다. 수정자·수정 시각은 늘 쓴다. */
  int update(
      @Param("id") Long id,
      @Param("name") String name,
      @Param("setDescription") boolean setDescription,
      @Param("description") String description,
      @Param("chartType") String chartType,
      @Param("configJson") String configJson,
      @Param("isShared") Boolean isShared,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedAt") LocalDateTime updatedAt);

  /** id 조각 중 ownerId 가 만든 비공유 차트를 잠그고 id·이름을 읽는다. */
  List<IdName> selectShareTargetsForUpdate(
      @Param("chunks") List<List<Long>> chunks, @Param("ownerId") Long ownerId);

  /** id 조각의 차트를 공유로 바꾼다. 바꾼 행 수. */
  int markShared(@Param("chunks") List<List<Long>> chunks);

  /** id 조각 중 조회자에게 보이는(소유 또는 공유) 차트 id. */
  List<Long> selectVisibleIds(
      @Param("chunks") List<List<Long>> chunks, @Param("userId") Long userId);

  /** id 조각 중 ownerId 가 만든 차트의 공유 여부별 수. */
  SharingCounts countOwnedBySharing(
      @Param("chunks") List<List<Long>> chunks, @Param("ownerId") Long ownerId);

  /** id 조각의 차트가 쓰는 저장 쿼리 id(중복 없음, 순서 없음). */
  List<Long> selectSavedQueryIds(@Param("chunks") List<List<Long>> chunks);

  /** 주인의 차트를 지운다(위젯은 FK CASCADE). 지운 행 수. */
  int deleteByIdAndOwner(@Param("id") Long id, @Param("userId") Long userId);
}
