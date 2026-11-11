package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.rowsearch.SearchConfig;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * dataset_column.is_searchable MyBatis 매퍼(WD-707). SQL 은 {@code
 * mybatis/dataset/SearchColumnMapper.xml}.
 */
@PlatformMapper
public interface SearchColumnMapper {

  /** 검색 대상으로 표시된 컬럼(column_order, id 순). */
  List<SearchConfig.Field> selectSearchableFields(@Param("datasetId") long datasetId);

  /**
   * 청크 목록에 든 이름만 true, 나머지는 false. 청크는 IN 목록당 500 개 이하이며 여럿이면 OR 로 잇는다(Tibero IN 1000 개 상한). 빈 목록이면
   * 전부 false.
   */
  int updateSearchable(
      @Param("datasetId") long datasetId, @Param("chunks") List<List<String>> chunks);

  /** 이 컬럼이 검색 대상이면 1, 아니면 0. */
  int countSearchable(@Param("columnId") long columnId);
}
