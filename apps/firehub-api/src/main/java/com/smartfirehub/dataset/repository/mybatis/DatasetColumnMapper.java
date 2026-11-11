package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetColumnSnapshot;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * dataset_column MyBatis 매퍼(WD-707). SQL 은 {@code mybatis/dataset/DatasetColumnMapper.xml}.
 *
 * <p>dataset_column 은 테넌트 정책(RLS) 아래 있어 tenant_id 를 SQL 에 쓰지 않는다.
 */
@PlatformMapper
public interface DatasetColumnMapper {

  /** 데이터셋의 컬럼 전부(column_order, id 순). */
  List<DatasetColumnResponse> selectByDatasetId(@Param("datasetId") Long datasetId);

  /** id 로 컬럼 한 건. 없으면 null. */
  DatasetColumnResponse selectById(@Param("id") Long id);

  /** 생성 키는 {@link ColumnInsert#setId} 로 돌려받는다. */
  int insert(ColumnInsert row);

  /**
   * 부분 수정. null 인 항목은 기존 값을 보존한다. {@code clearMaxLength} 가 true 면 max_length 를 NULL 로 만든다(VARCHAR →
   * TEXT 등 — 값이 함께 와도 NULL 이 이긴다, jOOQ 구현과 같다).
   */
  int update(
      @Param("id") Long id,
      @Param("columnName") String columnName,
      @Param("displayName") String displayName,
      @Param("dataType") String dataType,
      @Param("maxLength") Integer maxLength,
      @Param("clearMaxLength") boolean clearMaxLength,
      @Param("isNullable") Boolean isNullable,
      @Param("isIndexed") Boolean isIndexed,
      @Param("description") String description,
      @Param("isPrimaryKey") Boolean isPrimaryKey);

  /** 데이터셋 컬럼의 가장 큰 column_order. 컬럼이 없으면 null. */
  Integer selectMaxOrder(@Param("datasetId") Long datasetId);

  /** 데이터셋의 컬럼을 모두 지운다. */
  int deleteByDatasetId(@Param("datasetId") Long datasetId);

  /** 되돌리기용 보관본 — 데이터셋 컬럼 전부를 id 까지(column_order, id 순). */
  List<DatasetColumnSnapshot> selectSnapshotByDatasetId(@Param("datasetId") Long datasetId);

  /** 보관 행을 id 까지 그대로 다시 넣는다(tenant_id 는 정책 기본값이 채운다). */
  int insertSnapshotRow(
      @Param("id") Long id,
      @Param("datasetId") Long datasetId,
      @Param("columnName") String columnName,
      @Param("displayName") String displayName,
      @Param("dataType") String dataType,
      @Param("isNullable") Boolean isNullable,
      @Param("isIndexed") Boolean isIndexed,
      @Param("description") String description,
      @Param("columnOrder") Integer columnOrder,
      @Param("maxLength") Integer maxLength,
      @Param("isPrimaryKey") Boolean isPrimaryKey,
      @Param("isSearchable") Boolean isSearchable);

  /** 같은 데이터셋에 같은 이름의 컬럼 수(excludeColumnId 행은 뺀다). */
  int countByDatasetIdAndName(
      @Param("datasetId") Long datasetId,
      @Param("columnName") String columnName,
      @Param("excludeColumnId") Long excludeColumnId);

  /** 컬럼이 속한 데이터셋 id. 컬럼이 없으면 null. */
  Long selectDatasetIdByColumnId(@Param("columnId") Long columnId);

  /** 컬럼 한 건을 지운다. */
  int deleteById(@Param("id") Long id);

  /** 데이터셋의 모든 컬럼에서 PK 표시를 내린다. */
  int clearPrimaryKeys(@Param("datasetId") Long datasetId);

  /** 청크 목록에 든 컬럼을 PK 로 만든다(IN 목록당 500 개 이하, 여럿이면 OR 로 잇는다 — Tibero IN 1000 개 상한). */
  int markPrimaryKeys(@Param("datasetId") Long datasetId, @Param("chunks") List<List<Long>> chunks);

  /** 컬럼 한 건의 column_order 를 바꾼다(다른 데이터셋 컬럼은 건드리지 않게 dataset_id 도 건다). */
  int updateOrder(
      @Param("datasetId") Long datasetId, @Param("id") Long id, @Param("order") int order);

  /** 다른 데이터셋에서 같은 이름 컬럼의 비어 있지 않은 첫 설명(id 순). 없으면 null. */
  String selectFirstDescriptionByColumnName(
      @Param("columnName") String columnName, @Param("excludeDatasetId") Long excludeDatasetId);

  /** 컬럼 설명만 바꾼다. */
  int updateDescription(@Param("columnId") Long columnId, @Param("description") String description);
}
