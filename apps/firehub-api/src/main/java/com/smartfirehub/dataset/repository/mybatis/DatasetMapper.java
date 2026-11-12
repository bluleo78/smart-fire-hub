package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.repository.DatasetRepository.DatasetTableLevel;
import com.smartfirehub.dataset.repository.DatasetWriteTarget;
import com.smartfirehub.dataset.repository.mybatis.DatasetRows.DatasetRow;
import com.smartfirehub.dataset.repository.mybatis.DatasetRows.LockedRow;
import com.smartfirehub.dataset.repository.mybatis.DatasetRows.SchemaRow;
import com.smartfirehub.dataset.repository.mybatis.DatasetRows.TagRow;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * dataset MyBatis 매퍼(WD-707). SQL 은 {@code mybatis/dataset/DatasetMapper.xml}.
 *
 * <p>dataset·dataset_tag·dataset_favorite·dataset_category·security_level 은 테넌트 정책(RLS) 아래 있어
 * tenant_id 를 SQL 에 쓰지 않는다. 목록 필터의 빈 문자열은 리포지토리가 null 로 바꿔 넘긴다(null 인 필터는 걸지 않는다).
 */
@PlatformMapper
public interface DatasetMapper {

  /**
   * 목록 한 쪽의 데이터셋 id(중복 제거, id 오름차순). pattern 은 {@code LikePatternUtils.containsPattern} 로 이스케이프한 검색
   * 패턴(null 이면 검색하지 않음), favoriteUserId 는 즐겨찾기만 볼 때의 사용자(null 이면 거르지 않음).
   */
  List<Long> selectPageIds(
      @Param("categoryId") Long categoryId,
      @Param("storageType") String storageType,
      @Param("originType") String originType,
      @Param("status") String status,
      @Param("securityLevelId") Long securityLevelId,
      @Param("pattern") String pattern,
      @Param("favoriteUserId") Long favoriteUserId,
      @Param("vis") VisibilityParams vis,
      @Param("limit") int limit,
      @Param("offset") int offset);

  /** {@link #selectPageIds} 와 같은 조건의 중복 없는 건수. */
  long countFiltered(
      @Param("categoryId") Long categoryId,
      @Param("storageType") String storageType,
      @Param("originType") String originType,
      @Param("status") String status,
      @Param("securityLevelId") Long securityLevelId,
      @Param("pattern") String pattern,
      @Param("favoriteUserId") Long favoriteUserId,
      @Param("vis") VisibilityParams vis);

  /** id 청크(조각당 500 개 이하, OR 로 잇는다)의 데이터셋 행(id 오름차순). */
  List<DatasetRow> selectByIds(@Param("chunks") List<List<Long>> chunks);

  /** 응답 행 한 건(카테고리·상태 변경자 이름 포함). 없으면 null. */
  DatasetRow selectById(@Param("id") Long id);

  /** id 청크의 태그(태그 이름 오름차순). */
  List<TagRow> selectTagsByDatasetIds(@Param("chunks") List<List<Long>> chunks);

  /** FOR UPDATE 로 잠근다(두 DB 같은 모드). 행이 없으면 빈 목록. */
  List<Long> lockIdForUpdate(@Param("id") Long id);

  /** PG FOR NO KEY UPDATE · Tibero FOR UPDATE. */
  List<Long> lockIdNoKeyUpdate(@Param("id") Long id);

  /** PG FOR SHARE · Tibero FOR UPDATE. */
  List<Long> lockIdShare(@Param("id") Long id);

  /** 쓰기 대상 읽기 + PG FOR KEY SHARE · Tibero FOR UPDATE. */
  DatasetWriteTarget lockWriteTargetKeyShare(@Param("id") Long id);

  /** 테이블 이름판 쓰기 대상 읽기 + PG FOR KEY SHARE · Tibero FOR UPDATE. */
  DatasetWriteTarget lockWriteTargetKeyShareByTableName(@Param("tableName") String tableName);

  /** 쓰기 대상 읽기 + FOR UPDATE(두 DB). */
  DatasetWriteTarget lockWriteTargetForUpdate(@Param("id") Long id);

  /** 쓰기 대상 읽기 + PG FOR NO KEY UPDATE · Tibero FOR UPDATE. */
  DatasetWriteTarget lockWriteTargetNoKeyUpdate(@Param("id") Long id);

  /** id·수정 시각 읽기 + PG FOR NO KEY UPDATE · Tibero FOR UPDATE. 행이 없으면 null. */
  LockedRow lockUpdatedAt(@Param("id") Long id);

  /** 생성 키는 {@link DatasetInsert#setId} 로 돌려받는다. */
  int insert(DatasetInsert row);

  /** 이 파이프라인 스텝이 만든 데이터셋 id. 없으면 null. */
  Long selectIdBySourcePipelineStepId(@Param("stepId") Long stepId);

  /** 이름·설명·카테고리·수정 시각·수정자를 바꾼다. */
  int update(
      @Param("id") Long id,
      @Param("name") String name,
      @Param("description") String description,
      @Param("categoryId") Long categoryId,
      @Param("now") LocalDateTime now,
      @Param("updatedBy") Long updatedBy);

  /** 상태·상태 메모·상태 변경자·변경 시각을 바꾼다. */
  int updateStatus(
      @Param("id") Long id,
      @Param("status") String status,
      @Param("statusNote") String statusNote,
      @Param("statusUpdatedBy") Long statusUpdatedBy,
      @Param("now") LocalDateTime now);

  /** 데이터셋을 지우고 지운 행 수(0 이면 이미 없음)를 돌려준다. */
  int deleteById(@Param("id") Long id);

  /** 볼 수 있는 데이터셋 가운데 같은 이름(1행으로 자른 부분 질의를 센다 — 0 또는 1). */
  int countVisibleByName(
      @Param("name") String name,
      @Param("vis") VisibilityParams vis,
      @Param("excludeId") Long excludeId);

  /** 같은 테이블 이름(0 또는 1). */
  int countByTableName(@Param("tableName") String tableName);

  /** 테이블 이름. 행이 없으면 null. */
  String selectTableNameById(@Param("id") Long id);

  /** 생성자 id. 행이 없거나 값이 NULL 이면 null. */
  Long selectCreatedById(@Param("id") Long id);

  /** 수정 시각. 행이 없거나 값이 NULL 이면 null. */
  LocalDateTime selectUpdatedAtById(@Param("id") Long id);

  /** 수정자 id. 행이 없거나 값이 NULL 이면 null. */
  Long selectUpdatedById(@Param("id") Long id);

  /**
   * 스키마 메타 행(데이터셋 LEFT JOIN 컬럼, table_name·id·column_order 순). vis 는 가시성 조각 파라미터(별칭 d 기준), chunks 가
   * null 이면 id 로 거르지 않는다.
   */
  List<SchemaRow> selectSchemaMetaRows(
      @Param("vis") VisibilityParams vis, @Param("chunks") List<List<Long>> chunks);

  /** (테이블 이름, 보안 등급 id). onlyTable 이 null 이면 table_name 이 있는 전부. */
  List<DatasetTableLevel> selectTableLevels(@Param("onlyTable") String onlyTable);
}
