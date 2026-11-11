package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** dataset_tag MyBatis 매퍼(WD-707). SQL 은 {@code mybatis/dataset/DatasetTagMapper.xml}. */
@PlatformMapper
public interface DatasetTagMapper {

  /** 데이터셋의 태그 이름(이름 오름차순). */
  List<String> selectByDatasetId(@Param("datasetId") Long datasetId);

  /** 같은 데이터셋에 대소문자 무시로 같은 태그가 없을 때만 넣고 넣은 행 수(0 또는 1)를 돌려준다. */
  int insertIfAbsent(
      @Param("datasetId") Long datasetId,
      @Param("tagName") String tagName,
      @Param("userId") Long userId);

  /** 대소문자 무시로 같은 태그를 지우고 지운 행 수를 돌려준다. */
  int deleteIgnoreCase(@Param("datasetId") Long datasetId, @Param("tagName") String tagName);

  /** 볼 수 있는 데이터셋에 붙은 태그 이름(중복 없이) — 가시성 조각 파라미터 vis. */
  List<String> selectAllDistinct(@Param("vis") VisibilityParams vis);
}
