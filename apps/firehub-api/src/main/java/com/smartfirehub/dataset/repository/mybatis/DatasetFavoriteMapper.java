package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** dataset_favorite MyBatis 매퍼(WD-707). SQL 은 {@code mybatis/dataset/DatasetFavoriteMapper.xml}. */
@PlatformMapper
public interface DatasetFavoriteMapper {

  /** 이 사용자·데이터셋 즐겨찾기 행 수(0 또는 1). */
  int count(@Param("userId") Long userId, @Param("datasetId") Long datasetId);

  /** 이미 있으면 아무것도 하지 않는다(PG ON CONFLICT DO NOTHING, Tibero MERGE). */
  int insertIfAbsent(@Param("userId") Long userId, @Param("datasetId") Long datasetId);

  /** 즐겨찾기를 지우고 지운 행 수를 돌려준다. */
  int delete(@Param("userId") Long userId, @Param("datasetId") Long datasetId);

  /** 사용자가 즐겨찾기한 데이터셋 id 전부. */
  List<Long> selectDatasetIds(@Param("userId") Long userId);

  /** chunks(InChunks.of 로 나눈 데이터셋 id) 가운데 사용자가 즐겨찾기한 id(WD-707 데이터셋 목록 한 쪽 표시용). */
  List<Long> selectDatasetIdsAmong(
      @Param("userId") Long userId, @Param("chunks") List<List<Long>> chunks);
}
