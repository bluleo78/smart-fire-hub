package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.repository.SearchColumnRepository;
import com.smartfirehub.dataset.rowsearch.SearchConfig;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.util.Collection;
import org.springframework.transaction.annotation.Transactional;

/**
 * SearchColumnRepository 의 MyBatis 구현(WD-707) — {@code JooqSearchColumnRepository} 와 같은 의미.
 *
 * <p>클래스 레벨 {@code @Transactional} 은 jOOQ 구현 그대로다(RLS 테이블이라 테넌트 컨텍스트는 트랜잭션이 열릴 때 심긴다).
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisSearchColumnRepository implements SearchColumnRepository {

  private final SearchColumnMapper mapper;

  public MyBatisSearchColumnRepository(SearchColumnMapper mapper) {
    this.mapper = mapper;
  }

  /** 검색 대상 필드를 column_order 순으로. */
  @Override
  public SearchConfig findConfig(long datasetId) {
    return new SearchConfig(mapper.selectSearchableFields(datasetId));
  }

  /**
   * 지정한 컬럼만 true, 나머지는 false. PG {@code = ANY(?)} 는 Tibero 에 없어 두 DB 공통으로 {@code CASE WHEN
   * column_name IN (…) OR column_name IN (…)} 로 푼다 — 이름이 1000 개를 넘어도 터지지 않게 {@link InChunks} 로 500
   * 개씩 나눈다.
   */
  @Override
  public void setSearchable(long datasetId, Collection<String> columnNames) {
    mapper.updateSearchable(datasetId, InChunks.of(columnNames));
  }

  /** 컬럼이 검색 대상 필드인지. 컬럼이 없으면 false. */
  @Override
  public boolean isSearchable(long columnId) {
    return mapper.countSearchable(columnId) > 0;
  }
}
