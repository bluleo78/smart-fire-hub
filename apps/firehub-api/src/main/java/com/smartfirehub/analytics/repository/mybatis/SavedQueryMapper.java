package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.repository.SavedQueryRepository.RawSavedQuery;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsRows.IdName;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsRows.SharingCounts;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsRows.VersionRow;
import com.smartfirehub.analytics.repository.mybatis.SavedQueryRows.SavedQueryInsert;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 분석 저장 쿼리(saved_query) MyBatis 매퍼(WD-710). SQL 은 {@code mybatis/analytics/SavedQueryMapper.xml}.
 * 테넌트는 정책(RLS)이 맡는다.
 *
 * <p>목록 범위 인자(shared·mine)와 검색 패턴·폴더는 구현이 미리 정리해 넘긴다 — 빈 검색·빈 폴더는 null, 검색은 {@code
 * LikePatternUtils.containsPattern} 으로 이스케이프한 패턴이다.
 */
@PlatformMapper
public interface SavedQueryMapper {

  /** 목록 한 쪽 — 수정 시각 내림차순, 같으면 id 내림차순. */
  List<SavedQueryListResponse> selectPage(
      @Param("shared") boolean shared,
      @Param("mine") boolean mine,
      @Param("userId") Long userId,
      @Param("pattern") String pattern,
      @Param("folder") String folder,
      @Param("vis") VisibilityParams vis,
      @Param("limit") int limit,
      @Param("offset") long offset);

  /** 목록과 같은 범위·필터의 건수. */
  long count(
      @Param("shared") boolean shared,
      @Param("mine") boolean mine,
      @Param("userId") Long userId,
      @Param("pattern") String pattern,
      @Param("folder") String folder);

  /** 단건 — ownerOnly 면 주인만, 아니면 주인 또는 공유. 없으면 null. */
  SavedQueryResponse selectDetail(
      @Param("id") Long id,
      @Param("userId") Long userId,
      @Param("ownerOnly") boolean ownerOnly,
      @Param("vis") VisibilityParams vis);

  /** 소유권 제한 없는 원본 행 — 없으면 null. */
  RawSavedQuery selectRaw(@Param("id") Long id);

  /** id 조각 중 ownerId 가 만든 비공유 쿼리를 잠그고 id·이름을 읽는다. */
  List<IdName> selectShareTargetsForUpdate(
      @Param("chunks") List<List<Long>> chunks, @Param("ownerId") Long ownerId);

  /** id 조각의 쿼리를 공유로 바꾼다. 바꾼 행 수. */
  int markShared(@Param("chunks") List<List<Long>> chunks);

  /** id 조각 중 ownerId 가 만든 쿼리의 공유 여부별 수. */
  SharingCounts countOwnedBySharing(
      @Param("chunks") List<List<Long>> chunks, @Param("ownerId") Long ownerId);

  /** 한 건을 넣고 생성 키를 홀더에 돌려준다. */
  int insert(SavedQueryInsert row);

  /** 수정 시각을 FOR UPDATE 로 잠그고 읽는다 — 없으면 null. */
  VersionRow lockVersionById(@Param("id") Long id);

  /**
   * 넘긴 필드만 고친다 — null 인 값 인자는 유지. 설명·폴더는 set 플래그가 참일 때만 쓰고 값이 null 이면 NULL 로 비운다. 수정자·수정 시각은 늘 쓴다.
   */
  int update(
      @Param("id") Long id,
      @Param("name") String name,
      @Param("setDescription") boolean setDescription,
      @Param("description") String description,
      @Param("sqlText") String sqlText,
      @Param("datasetId") Long datasetId,
      @Param("setFolder") boolean setFolder,
      @Param("folder") String folder,
      @Param("isShared") Boolean isShared,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedAt") LocalDateTime updatedAt);

  /** 주인의 쿼리를 지운다. 지운 행 수. */
  int deleteByIdAndOwner(@Param("id") Long id, @Param("userId") Long userId);

  /** 목록과 같은 범위의 폴더 이름(중복 없음, 오름차순, null 제외). */
  List<String> selectDistinctFolders(
      @Param("shared") boolean shared, @Param("mine") boolean mine, @Param("userId") Long userId);

  /** 쿼리를 쓰는 차트 중 ownerId 가 아닌 사용자가 만든 것의 수. ownerId 는 null 이 아니다(구현이 null 을 0 으로 먼저 끝낸다). */
  long countOtherUserCharts(
      @Param("savedQueryId") Long savedQueryId, @Param("ownerId") Long ownerId);
}
