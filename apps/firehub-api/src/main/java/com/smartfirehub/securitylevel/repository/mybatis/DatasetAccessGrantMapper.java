package com.smartfirehub.securitylevel.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository.GrantRow;
import com.smartfirehub.securitylevel.repository.mybatis.DatasetAccessGrantRows.GrantInsert;
import com.smartfirehub.securitylevel.repository.mybatis.DatasetAccessGrantRows.SubjectRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 데이터셋 허용 목록(dataset_access_grant) MyBatis 매퍼(WD-711). SQL 은 {@code
 * mybatis/securitylevel/DatasetAccessGrantMapper.xml}. 문장 1:1 바인딩만 한다 — 테넌트는 정책(RLS)이 맡는다.
 */
@PlatformMapper
public interface DatasetAccessGrantMapper {

  /** 데이터셋 id 조각에 든 항목의 (데이터셋, 사용자, 역할). */
  List<SubjectRow> selectSubjects(@Param("chunks") List<List<Long>> chunks);

  /** 대상(사용자 또는 역할) 하나의 항목 id — 없으면 null. 대상은 데이터셋 안에서 유일하다. */
  Long selectIdBySubject(
      @Param("datasetId") long datasetId,
      @Param("userId") Long userId,
      @Param("roleId") Long roleId);

  /**
   * 없을 때만 넣는다 — 넣은 행 수(0 또는 1). PG 는 ON CONFLICT DO NOTHING, Tibero 는 CASE 식 유일 색인과 같은 식의 MERGE.
   */
  int insertIfAbsent(
      @Param("datasetId") long datasetId,
      @Param("userId") Long userId,
      @Param("roleId") Long roleId,
      @Param("grantedBy") Long grantedBy);

  /** 항목 하나를 넣고 생성 키를 홀더에 돌려준다(중복이면 유일 제약 위반 예외). */
  int insert(GrantInsert row);

  /**
   * 원본 데이터셋의 항목을 대상 데이터셋으로 한 문장에 복사하고 새로 들어간 행 수를 돌려준다. 대상에 이미 있는 대상은 건너뛴다(NOT EXISTS, PG 는 ON
   * CONFLICT DO NOTHING 도 더한다).
   */
  int copy(
      @Param("fromDatasetId") long fromDatasetId,
      @Param("toDatasetId") long toDatasetId,
      @Param("grantedBy") Long grantedBy);

  int deleteSubject(
      @Param("datasetId") long datasetId,
      @Param("userId") Long userId,
      @Param("roleId") Long roleId);

  int deleteById(@Param("id") long id);

  /** 데이터셋의 화면용 행(이름 조인) — 추가 시각·id 오름차순. */
  List<GrantRow> selectRowsByDataset(@Param("datasetId") long datasetId);

  /** 데이터셋·대상의 화면용 행. */
  List<GrantRow> selectRowsByDatasetAndSubject(
      @Param("datasetId") long datasetId,
      @Param("userId") Long userId,
      @Param("roleId") Long roleId);

  /** 항목 1개의 화면용 행(이름 조인) — 없으면 null. */
  GrantRow selectRowsById(@Param("id") long id);

  /** 항목 1개의 원 행(이름 없음). */
  GrantRow selectPlainById(@Param("id") long id);

  int countByDataset(@Param("datasetId") long datasetId);

  int countUser(@Param("datasetId") long datasetId, @Param("userId") long userId);

  /** 이 역할이 걸린 데이터셋 행을 id 오름차순으로 FOR UPDATE 잠그고 id 를 돌려준다. */
  List<Long> lockDatasetsGrantedToRole(@Param("roleId") long roleId);

  List<Long> selectDatasetsWhereRoleIsSoleGrantOnAllowlistLevel(@Param("roleId") long roleId);

  List<Long> selectDatasetIdsWithoutAllowlist(@Param("levelId") long levelId);
}
