package com.smartfirehub.file.repository.mybatis;

import com.smartfirehub.file.repository.FileDatasetConfigRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.util.Optional;

/**
 * FileDatasetConfigRepository 의 MyBatis 구현(WD-712) — {@code JooqFileDatasetConfigRepository} 와 같은
 * 의미. jOOQ 구현처럼 {@code @Transactional} 을 달지 않는다(호출자 트랜잭션 참여). file_dataset_config 는 테넌트 정책(RLS)
 * 대상이라 tenant_id 는 DEFAULT 가 채우고 정책이 거른다.
 */
@PlatformMyBatisRepository
public class MyBatisFileDatasetConfigRepository implements FileDatasetConfigRepository {

  private final FileDatasetConfigMapper mapper;

  /** 매퍼를 주입받는다(플랫폼 MyBatis 빈 — 매퍼 SQL 은 mybatis/ 아래 XML). */
  public MyBatisFileDatasetConfigRepository(FileDatasetConfigMapper mapper) {
    this.mapper = mapper;
  }

  /** 버킷/프리픽스 매핑을 저장한다(데이터셋 생성 시 1회). */
  @Override
  public void save(Long datasetId, String bucket, String prefix) {
    mapper.insert(datasetId, bucket, prefix);
  }

  /** 데이터셋의 버킷/프리픽스 매핑을 조회한다. */
  @Override
  public Optional<FileDatasetConfig> findByDatasetId(Long datasetId) {
    return Optional.ofNullable(mapper.selectByDatasetId(datasetId));
  }
}
