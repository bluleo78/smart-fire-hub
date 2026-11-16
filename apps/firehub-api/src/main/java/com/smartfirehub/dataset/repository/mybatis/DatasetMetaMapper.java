package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 검색 합본 입력용 데이터셋 메타 매퍼(WD-746). SQL 은 {@code mybatis/dataset/DatasetMetaMapper.xml}.
 *
 * <p>{@code JooqDatasetMetaReader} 와 같은 의미 — 데이터셋 본체+카테고리 이름, 컬럼 이름·표시명, 태그 이름, 전체 id. 모두 PG·Tibero
 * 공통 문장이다. 테넌트 정책(RLS) 대상 표라 tenant_id 조건은 두지 않는다(정책이 좁힌다).
 */
@PlatformMapper
public interface DatasetMetaMapper {

  /** 데이터셋 본체와 카테고리 이름(카테고리가 없으면 null). 데이터셋이 없으면 null. */
  MetaRow selectMeta(@Param("datasetId") long datasetId);

  /** 데이터셋 컬럼의 이름·표시명(컬럼 순서). */
  List<ColumnTextRow> selectColumnTexts(@Param("datasetId") long datasetId);

  /** 데이터셋 태그 이름. */
  List<String> selectTagNames(@Param("datasetId") long datasetId);

  /** 전체 데이터셋 id(오름차순) — 백필용. */
  List<Long> selectAllIds();

  /** 데이터셋 본체 + 카테고리 이름 한 행. */
  record MetaRow(String name, String description, String tableName, String categoryName) {}

  /** 컬럼 이름과 표시명 한 행. */
  record ColumnTextRow(String columnName, String displayName) {}
}
