package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.repository.DatasetTagRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;

/**
 * DatasetTagRepository 의 MyBatis 구현(WD-707) — {@code JooqDatasetTagRepository} 와 같은 의미.
 *
 * <p>jOOQ 구현처럼 메서드에 {@code @Transactional} 을 달지 않는다(호출자 트랜잭션 참여).
 */
@PlatformMyBatisRepository
public class MyBatisDatasetTagRepository implements DatasetTagRepository {

  private final DatasetTagMapper mapper;

  public MyBatisDatasetTagRepository(DatasetTagMapper mapper) {
    this.mapper = mapper;
  }

  /** 데이터셋의 태그 이름을 이름 오름차순으로 돌려준다. */
  @Override
  public List<String> findByDatasetId(Long datasetId) {
    return mapper.selectByDatasetId(datasetId);
  }

  /**
   * 대소문자 무시로 이미 있으면 0. PG 는 ON CONFLICT DO NOTHING, Tibero 는 MERGE(WHEN NOT MATCHED) 이다.
   *
   * <p>Tibero 의 MERGE 는 같은 태그가 동시에 처음 들어오면 한쪽이 유일 키 위반으로 실패할 수 있다(문장 단위 실패라 트랜잭션은 산다). 한 번 다시 시도하면
   * 이미 들어간 행을 만나 0 이 되어 PG 의 DO NOTHING 과 같은 결과다. 이 재시도는 Tibero MERGE 전용이며 PG 에서는 이 예외가 나지 않는다.
   */
  @Override
  public int insert(Long datasetId, String tagName, Long userId) {
    try {
      return mapper.insertIfAbsent(datasetId, tagName, userId);
    } catch (DuplicateKeyException e) {
      return mapper.insertIfAbsent(datasetId, tagName, userId);
    }
  }

  /** 대소문자 무시로 같은 태그를 지우고 지운 행 수를 돌려준다(jOOQ 판과 같다). */
  @Override
  public int deleteIgnoreCase(Long datasetId, String tagName) {
    return mapper.deleteIgnoreCase(datasetId, tagName);
  }

  /** 볼 수 있는 데이터셋(보안 등급 가시성)에 붙은 태그 이름을 중복 없이 돌려준다. */
  @Override
  public List<String> findAllDistinctTags(DatasetVisibility visibleDataset) {
    return mapper.selectAllDistinct(MyBatisDatasetVisibility.params(visibleDataset));
  }
}
