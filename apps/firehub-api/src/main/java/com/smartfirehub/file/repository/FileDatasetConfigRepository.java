package com.smartfirehub.file.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record3;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * file_dataset_config(데이터셋→MinIO 버킷/프리픽스) 접근 리포지토리.
 *
 * <p>FILE 데이터셋은 개별 파일 행을 저장하지 않고, 데이터셋 1건당 버킷/프리픽스 매핑 1건만 갖는다. public 스키마 테이블이지만 jOOQ 코드젠 대상에 포함되지
 * 않아(Task 4 시점 기준) 생성 클래스 대신 plain jOOQ DSL(field/table/name)로 접근한다.
 */
@Repository
public class FileDatasetConfigRepository {

  /** FILE 데이터셋의 버킷/프리픽스 매핑 값 객체. */
  public record FileDatasetConfig(Long datasetId, String bucket, String prefix) {}

  private static final Table<?> T = table(name("file_dataset_config"));
  private static final Field<Long> DATASET_ID =
      field(name("file_dataset_config", "dataset_id"), Long.class);
  private static final Field<String> BUCKET =
      field(name("file_dataset_config", "bucket"), String.class);
  private static final Field<String> PREFIX =
      field(name("file_dataset_config", "prefix"), String.class);

  private final DSLContext dsl;

  public FileDatasetConfigRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** 버킷/프리픽스 매핑을 저장한다(데이터셋 생성 시 1회). */
  public void save(Long datasetId, String bucket, String prefix) {
    dsl.insertInto(T)
        .columns(DATASET_ID, BUCKET, PREFIX)
        .values(datasetId, bucket, prefix)
        .execute();
  }

  /** 데이터셋의 버킷/프리픽스 매핑을 조회한다. */
  public Optional<FileDatasetConfig> findByDatasetId(Long datasetId) {
    Record3<Long, String, String> r =
        dsl.select(DATASET_ID, BUCKET, PREFIX).from(T).where(DATASET_ID.eq(datasetId)).fetchOne();
    return r == null
        ? Optional.empty()
        : Optional.of(new FileDatasetConfig(r.value1(), r.value2(), r.value3()));
  }
}
