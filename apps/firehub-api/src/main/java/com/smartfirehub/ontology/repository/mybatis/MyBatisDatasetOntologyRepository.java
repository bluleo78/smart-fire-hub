package com.smartfirehub.ontology.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformRetries;
import com.smartfirehub.ontology.repository.DatasetOntologyRepository;
import com.smartfirehub.ontology.repository.GraphOntologySourceRepository;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetOntologyRepository 의 MyBatis 구현(WD-711) — {@code JooqDatasetOntologyRepository} 와 같은 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 plain {@code @Transactional} 이다 — DatasetOntologyService.get 은 경계를
 * 만들지 않고, bind 는 서비스 트랜잭션(재연결 시 매핑 삭제와 원자적)에 합류한다. 출처 기록(GraphOntologySourceRepository.record)도 같은
 * 트랜잭션에 합류해 바인딩이 롤백되면 함께 롤백된다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisDatasetOntologyRepository implements DatasetOntologyRepository {

  private final DatasetOntologyMapper mapper;
  // 연결·재연결 시 출처를 남긴다(WD-28). 인터페이스로 받는다 — 구현(jOOQ·MyBatis)은 배선이 고른다.
  private final GraphOntologySourceRepository sourceRepository;

  public MyBatisDatasetOntologyRepository(
      DatasetOntologyMapper mapper, GraphOntologySourceRepository sourceRepository) {
    this.mapper = mapper;
    this.sourceRepository = sourceRepository;
  }

  /**
   * 데이터셋을 온톨로지에 연결한다(업서트 — 재연결이면 ontology_id·bound_by·bound_at 갱신). PG 는 ON CONFLICT, Tibero 는
   * MERGE.
   *
   * <p>Tibero MERGE 는 같은 데이터셋이 동시에 처음 연결되면 한쪽이 중복 키(-10007)로 실패할 수 있다(문장 단위 실패라 트랜잭션은 산다). 한 번 다시
   * 시도하면 이미 들어간 행을 만나 MATCHED 갱신이 되어 PG 의 DO UPDATE 와 같은 결과다. PG 에서는 이 예외가 나지 않는다. 재연결이어도 옛 온톨로지
   * 출처는 지우지 않는다 — 예전 그래프의 잔존 데이터 출처이기 때문이다.
   */
  @Override
  public void bind(long datasetId, long ontologyId, Long userId) {
    PlatformRetries.runOnceOnDuplicateKey(() -> mapper.bind(datasetId, ontologyId, userId));
    sourceRepository.record(ontologyId, datasetId);
  }

  @Override
  public Optional<Long> findOntologyIdByDataset(long datasetId) {
    return Optional.ofNullable(mapper.selectOntologyIdByDataset(datasetId));
  }
}
