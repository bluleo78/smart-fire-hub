package com.smartfirehub.ontology.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformRetries;
import com.smartfirehub.ontology.repository.GraphOntologySourceRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * GraphOntologySourceRepository 의 MyBatis 구현(WD-711) — {@code JooqGraphOntologySourceRepository} 와
 * 같은 의미.
 *
 * <p>클래스 레벨 {@code @Transactional} 은 jOOQ 구현 그대로이며 지우면 안 된다. RLS 컨텍스트는 트랜잭션 시작 시점에만 심기는데, 판정 쿼리가
 * 트랜잭션 없이 돌면 출처가 0행이 되어 {@link #existsUnreadableSource} 가 false(전부 열림, fail-open)가 된다. 쓰기({@link
 * #record})는 호출자(연결·매핑 저장) 트랜잭션에 합류한다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisGraphOntologySourceRepository implements GraphOntologySourceRepository {

  private final GraphOntologySourceMapper mapper;

  public MyBatisGraphOntologySourceRepository(GraphOntologySourceMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 출처를 멱등으로 남긴다. Tibero MERGE 는 같은 (온톨로지, 데이터셋)이 동시에 처음 기록되면 한쪽이 중복 키(-10007)로 실패할 수 있다(문장 단위 실패라
   * 트랜잭션은 산다). 한 번 다시 시도하면 이미 들어간 행을 만나 아무것도 하지 않는다 — PG 의 DO NOTHING 과 같은 결과다. PG 에서는 이 예외가 나지
   * 않는다.
   */
  @Override
  public void record(long ontologyId, long datasetId) {
    PlatformRetries.runOnceOnDuplicateKey(() -> mapper.record(ontologyId, datasetId));
  }

  @Transactional(readOnly = true)
  @Override
  public List<Long> findDatasetIds(long ontologyId) {
    return mapper.selectDatasetIds(ontologyId);
  }

  @Transactional(readOnly = true)
  @Override
  public boolean existsUnreadableSource(
      long ontologyId, DatasetVisibility visibility, boolean deletedSourceReadable) {
    return mapper.countUnreadable(
            ontologyId, MyBatisDatasetVisibility.params(visibility), deletedSourceReadable)
        > 0;
  }

  /** 1000 개 넘는 id 도 500 개씩 나눠 한 문장에 OR 로 잇는다(Tibero IN 상한, Review Focus 3). */
  @Transactional(readOnly = true)
  @Override
  public List<Long> findDatasetsWithGraphHistory(long tenantId, List<Long> datasetIds) {
    if (datasetIds.isEmpty()) {
      return List.of();
    }
    return mapper.selectDatasetsWithGraphHistory(tenantId, InChunks.of(datasetIds));
  }
}
