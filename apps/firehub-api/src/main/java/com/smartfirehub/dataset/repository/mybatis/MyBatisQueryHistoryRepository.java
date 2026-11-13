package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.dto.QueryHistoryResponse;
import com.smartfirehub.dataset.repository.QueryHistoryRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import java.util.List;

/**
 * QueryHistoryRepository 의 MyBatis 구현(WD-707) — {@code JooqQueryHistoryRepository} 와 같은 의미. 메서드에
 * {@code @Transactional} 을 달지 않는다(jOOQ 와 같음, 호출자 트랜잭션 참여). success 는 Tibero NUMBER(1) 이라 XML 이 1/0
 * 리터럴로 쓴다.
 */
@PlatformMyBatisRepository
public class MyBatisQueryHistoryRepository implements QueryHistoryRepository {

  private final QueryHistoryMapper mapper;

  public MyBatisQueryHistoryRepository(QueryHistoryMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 쿼리 실행 이력 한 건을 남긴다. 오류 메시지는 SQL 엔진 예외 문구라 길이를 통제할 수 없어 {@link
   * TiberoTextLimits#truncateErrorMessage} 로 Tibero {@code VARCHAR2(65532)} 바이트 한도 안으로 자른다(WD-728)
   * — 자르지 않으면 INSERT 가 실패해 이력이 사라진다. SQL 본문은 의미 값이라 자르지 않는다.
   */
  @Override
  public void save(
      Long datasetId,
      Long userId,
      String sql,
      String queryType,
      int affectedRows,
      long executionTimeMs,
      boolean success,
      String error) {
    mapper.insert(
        datasetId,
        userId,
        sql,
        queryType,
        affectedRows,
        executionTimeMs,
        success,
        TiberoTextLimits.truncateErrorMessage(error));
  }

  /** 데이터셋의 실행 이력 한 쪽(0 부터 세는 page × size 만큼 건너뛴다). */
  @Override
  public List<QueryHistoryResponse> findByDatasetId(Long datasetId, int page, int size) {
    return mapper.selectPage(datasetId, size, (long) page * size);
  }

  /** 데이터셋의 실행 이력 수. */
  @Override
  public long countByDatasetId(Long datasetId) {
    return mapper.countByDatasetId(datasetId);
  }
}
