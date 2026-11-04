package com.smartfirehub.file.repository;

import java.util.Optional;

/**
 * file_dataset_config(데이터셋→MinIO 버킷/프리픽스) 접근 리포지토리.
 *
 * <p>FILE 데이터셋은 개별 파일 행을 저장하지 않고, 데이터셋 1건당 버킷/프리픽스 매핑 1건만 갖는다. public 스키마 테이블이지만 jOOQ 코드젠 대상에 포함되지
 * 않아(Task 4 시점 기준) 생성 클래스 대신 plain jOOQ DSL(field/table/name)로 접근한다.
 *
 * <p>구현은 {@code JooqFileDatasetConfigRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface FileDatasetConfigRepository {

  /** FILE 데이터셋의 버킷/프리픽스 매핑 값 객체. */
  record FileDatasetConfig(Long datasetId, String bucket, String prefix) {}

  /** 버킷/프리픽스 매핑을 저장한다(데이터셋 생성 시 1회). */
  void save(Long datasetId, String bucket, String prefix);

  /** 데이터셋의 버킷/프리픽스 매핑을 조회한다. */
  Optional<FileDatasetConfig> findByDatasetId(Long datasetId);
}
