package com.smartfirehub.file.repository.mybatis;

import com.smartfirehub.file.repository.FileDatasetConfigRepository.FileDatasetConfig;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import org.apache.ibatis.annotations.Param;

/**
 * file_dataset_config MyBatis 매퍼(WD-712). SQL 은 {@code mybatis/file/FileDatasetConfigMapper.xml}.
 */
@PlatformMapper
public interface FileDatasetConfigMapper {

  /** 버킷/프리픽스 매핑 한 건을 넣는다. 같은 데이터셋에 두 번 넣으면 유일 키 위반이 그대로 올라온다. */
  int insert(
      @Param("datasetId") Long datasetId,
      @Param("bucket") String bucket,
      @Param("prefix") String prefix);

  /** 데이터셋의 매핑. 없으면 null. */
  FileDatasetConfig selectByDatasetId(@Param("datasetId") Long datasetId);
}
