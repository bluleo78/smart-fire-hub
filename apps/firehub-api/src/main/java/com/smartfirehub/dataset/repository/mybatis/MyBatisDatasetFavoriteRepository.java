package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.repository.DatasetFavoriteRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformRetries;
import java.util.HashSet;
import java.util.Set;

/**
 * DatasetFavoriteRepository 의 MyBatis 구현(WD-707) — {@code JooqDatasetFavoriteRepository} 와 같은 의미.
 */
@PlatformMyBatisRepository
public class MyBatisDatasetFavoriteRepository implements DatasetFavoriteRepository {

  private final DatasetFavoriteMapper mapper;

  public MyBatisDatasetFavoriteRepository(DatasetFavoriteMapper mapper) {
    this.mapper = mapper;
  }

  /** 이 사용자가 이 데이터셋을 즐겨찾기했는지. */
  @Override
  public boolean existsByUserIdAndDatasetId(Long userId, Long datasetId) {
    return mapper.count(userId, datasetId) > 0;
  }

  /**
   * 즐겨찾기를 추가한다(이미 있으면 아무 일도 하지 않는다). Tibero MERGE 는 같은 쌍이 동시에 처음 들어오면 한쪽이 유일 키 위반으로 실패할 수 있어(문장 단위
   * 실패, 트랜잭션은 산다) 한 번 다시 시도한다 — 두 번째는 이미 들어간 행을 만나 아무것도 하지 않는다. Tibero MERGE 전용 재시도다.
   */
  @Override
  public void insert(Long userId, Long datasetId) {
    PlatformRetries.runOnceOnDuplicateKey(() -> mapper.insertIfAbsent(userId, datasetId));
  }

  /** 즐겨찾기를 지운다(없으면 아무 일도 하지 않는다). */
  @Override
  public void delete(Long userId, Long datasetId) {
    mapper.delete(userId, datasetId);
  }

  /** 사용자가 즐겨찾기한 데이터셋 id 집합 — 목록 응답의 즐겨찾기 표시용. */
  @Override
  public Set<Long> findDatasetIdsByUserId(Long userId) {
    return new HashSet<>(mapper.selectDatasetIds(userId));
  }
}
