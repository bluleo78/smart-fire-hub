package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.dto.QueryHistoryResponse;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** query_history MyBatis 매퍼(WD-707). SQL 은 {@code mybatis/dataset/QueryHistoryMapper.xml}. */
@PlatformMapper
public interface QueryHistoryMapper {

  /** 쿼리 실행 이력 한 건을 넣는다. */
  int insert(
      @Param("datasetId") Long datasetId,
      @Param("userId") Long userId,
      @Param("sql") String sql,
      @Param("queryType") String queryType,
      @Param("affectedRows") int affectedRows,
      @Param("executionTimeMs") long executionTimeMs,
      @Param("success") boolean success,
      @Param("error") String error);

  /** 데이터셋의 실행 이력 한 쪽(limit·offset). */
  List<QueryHistoryResponse> selectPage(
      @Param("datasetId") Long datasetId, @Param("limit") int limit, @Param("offset") long offset);

  /** 데이터셋의 실행 이력 수. */
  long countByDatasetId(@Param("datasetId") Long datasetId);
}
