package com.smartfirehub.dataset.search;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record4;
import org.jooq.Table;
import org.springframework.stereotype.Component;

/**
 * 데이터셋 메타를 검색 합본 빌더 입력({@link DatasetSourceTextBuilder.Input})으로 읽어온다. 없으면 null.
 *
 * <p>jOOQ 필드 참조 방식은 {@code DatasetRepository} 와 동일하게 {@code field(name(...))} 동적 참조를 쓴다
 * (생성 코드 의존 없이 public 스키마 테이블을 직접 가리킴).
 */
@Component
public class DatasetMetaReader {

  private final DSLContext dsl;

  // dataset 본체 + 카테고리명
  private static final Table<?> DATASET = table(name("dataset"));
  private static final Field<Long> DS_ID = field(name("dataset", "id"), Long.class);
  private static final Field<String> DS_NAME = field(name("dataset", "name"), String.class);
  private static final Field<String> DS_DESCRIPTION =
      field(name("dataset", "description"), String.class);
  private static final Field<String> DS_TABLE_NAME =
      field(name("dataset", "table_name"), String.class);
  private static final Field<Long> DS_CATEGORY_ID =
      field(name("dataset", "category_id"), Long.class);

  private static final Table<?> DATASET_CATEGORY = table(name("dataset_category"));
  private static final Field<Long> DC_ID = field(name("dataset_category", "id"), Long.class);
  private static final Field<String> DC_NAME =
      field(name("dataset_category", "name"), String.class);

  private static final Table<?> DATASET_COLUMN = table(name("dataset_column"));
  private static final Field<Long> COL_DATASET_ID =
      field(name("dataset_column", "dataset_id"), Long.class);
  private static final Field<String> COL_COLUMN_NAME =
      field(name("dataset_column", "column_name"), String.class);
  private static final Field<String> COL_DISPLAY_NAME =
      field(name("dataset_column", "display_name"), String.class);

  private static final Table<?> DATASET_TAG = table(name("dataset_tag"));
  private static final Field<Long> DT_DATASET_ID =
      field(name("dataset_tag", "dataset_id"), Long.class);
  private static final Field<String> DT_TAG_NAME =
      field(name("dataset_tag", "tag_name"), String.class);

  public DatasetMetaReader(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * 데이터셋 단건 메타를 합본 입력으로 구성한다.
   *
   * @return 데이터셋이 없으면 null (삭제됨 → 호출측에서 인덱스 제거 판단)
   */
  public DatasetSourceTextBuilder.Input read(long datasetId) {
    // 1) dataset + category(name) 단건 — LEFT JOIN 으로 카테고리 없는 데이터셋도 조회. 없으면 null.
    Record4<String, String, String, String> row =
        dsl.select(DS_NAME, DS_DESCRIPTION, DS_TABLE_NAME, DC_NAME)
            .from(DATASET)
            .leftJoin(DATASET_CATEGORY)
            .on(DS_CATEGORY_ID.eq(DC_ID))
            .where(DS_ID.eq(datasetId))
            .fetchOne();
    if (row == null) {
      return null;
    }

    // 2) 컬럼명 + 표시명을 한 리스트에 합쳐 검색 텍스트를 풍부화한다(null/빈값은 빌더가 거름).
    List<String> columnNames = new ArrayList<>();
    dsl.select(COL_COLUMN_NAME, COL_DISPLAY_NAME)
        .from(DATASET_COLUMN)
        .where(COL_DATASET_ID.eq(datasetId))
        .forEach(
            r -> {
              columnNames.add(r.get(COL_COLUMN_NAME));
              columnNames.add(r.get(COL_DISPLAY_NAME));
            });

    // 3) 태그명 목록
    List<String> tagNames =
        dsl.select(DT_TAG_NAME)
            .from(DATASET_TAG)
            .where(DT_DATASET_ID.eq(datasetId))
            .fetch(r -> r.get(DT_TAG_NAME));

    return new DatasetSourceTextBuilder.Input(
        row.get(DS_NAME),
        row.get(DS_DESCRIPTION),
        row.get(DS_TABLE_NAME),
        columnNames,
        tagNames,
        row.get(DC_NAME));
  }

  /** 백필용: 전체 데이터셋 id 목록(오름차순). */
  public List<Long> findAllIds() {
    return dsl.select(DS_ID).from(DATASET).orderBy(DS_ID.asc()).fetch(r -> r.get(DS_ID));
  }
}
