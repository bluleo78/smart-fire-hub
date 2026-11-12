package com.smartfirehub.mapping.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformRetries;
import com.smartfirehub.mapping.repository.MappingRepository;
import com.smartfirehub.mapping.repository.StoredMapping;
import com.smartfirehub.ontology.repository.GraphOntologySourceRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * MappingRepository 의 MyBatis 구현(WD-708) — {@code JooqMappingRepository} 와 같은 의미.
 *
 * <p>클래스 단위 plain {@code @Transactional} 은 jOOQ 구현 그대로이며 지우면 안 된다. 호출자(MappingService.get)는 트랜잭션
 * 경계를 만들지 않는데, 트랜잭션이 없으면 RLS 컨텍스트가 심기지 않아 조회가 조용히 0행(「저장했는데 미매핑」)이 된다. 저장·활성화는 서비스 트랜잭션에 합류한다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisMappingRepository implements MappingRepository {

  private final MappingMapper mapper;
  // WD-28: 매핑 저장 시 출처를 남긴다. 같은 트랜잭션에 합류한다. 인터페이스로 받는다 — 구현은 배선이 고른다.
  private final GraphOntologySourceRepository sourceRepository;

  public MyBatisMappingRepository(
      MappingMapper mapper, GraphOntologySourceRepository sourceRepository) {
    this.mapper = mapper;
    this.sourceRepository = sourceRepository;
  }

  /**
   * 매핑 문서 UPSERT. Tibero MERGE 는 같은 데이터셋이 동시에 처음 저장되면 한쪽이 중복 키(-10007)로 실패할 수 있다(문장 단위 실패라 트랜잭션은
   * 산다). 한 번 다시 시도하면 이미 들어간 행을 만나 MATCHED 갱신이 되어 PG 의 DO UPDATE 와 같은 결과다.
   */
  @Override
  public void upsert(long datasetId, long ontologyId, String specJson, String status, Long userId) {
    PlatformRetries.runOnceOnDuplicateKey(
        () -> mapper.upsert(datasetId, ontologyId, specJson, status, userId));
    // WD-28: 표 투영은 mapping.ontologyId 로 쓰므로 매핑 저장 시점에도 출처를 남긴다.
    sourceRepository.record(ontologyId, datasetId);
  }

  @Override
  public Optional<StoredMapping> findByDataset(long datasetId) {
    return Optional.ofNullable(mapper.selectByDataset(datasetId));
  }

  @Override
  public Optional<StoredMapping> findByDatasetForUpdate(long datasetId) {
    return Optional.ofNullable(mapper.selectByDatasetForUpdate(datasetId));
  }

  @Override
  public boolean deleteByDatasetExceptOntology(long datasetId, long keepOntologyId) {
    return mapper.deleteByDatasetExceptOntology(datasetId, keepOntologyId) > 0;
  }

  @Override
  public void updateStatus(long datasetId, String status, Long userId) {
    mapper.updateStatus(datasetId, status, userId);
  }

  @Override
  public List<OntologyMappingRow> findByOntologyForUpdate(long ontologyId) {
    return mapper.selectByOntologyForUpdate(ontologyId);
  }

  @Override
  public List<LiveMappingRow> findLiveByOntology(
      long ontologyId, DatasetVisibility nameVisibility) {
    return mapper.selectLiveByOntology(ontologyId, MyBatisDatasetVisibility.params(nameVisibility));
  }

  @Override
  public void updateSpec(long datasetId, String specJson) {
    mapper.updateSpec(datasetId, specJson);
  }
}
