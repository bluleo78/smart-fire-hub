package com.smartfirehub.graphingest.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.graphingest.dto.GraphIngestRecord;
import com.smartfirehub.graphingest.dto.GraphIngestRecord.StaleRow;
import com.smartfirehub.graphingest.repository.GraphIngestRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * GraphIngestRepository 의 MyBatis 구현(WD-711) — {@code JooqGraphIngestRepository} 와 같은 의미.
 *
 * <p>클래스 레벨 {@code @Transactional} 은 jOOQ 구현 그대로이며 지우면 안 된다. 호출자 GraphIngestService 는 트랜잭션 경계를 만들지
 * 않는데, RLS 컨텍스트는 트랜잭션 시작 시점에만 심긴다. 없으면 save 는 tenant_id NOT NULL 위반, findStale 은 에러 없이 0행이 되어 낡음
 * 감지가 영원히 발화하지 않는다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisGraphIngestRepository implements GraphIngestRepository {

  private final GraphIngestMapper mapper;

  public MyBatisGraphIngestRepository(GraphIngestMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public long save(
      long datasetId,
      int schemaVersionAtIngest,
      int chunkCount,
      int nodeCount,
      int edgeCount,
      int extractionFailures,
      String status) {
    GraphIngestInsert row =
        new GraphIngestInsert(
            datasetId,
            schemaVersionAtIngest,
            chunkCount,
            nodeCount,
            edgeCount,
            extractionFailures,
            status);
    mapper.insert(row);
    return row.getId();
  }

  @Override
  public List<GraphIngestRecord> findByDataset(long datasetId) {
    return mapper.selectByDataset(datasetId);
  }

  @Override
  public List<StaleRow> findStale(DatasetVisibility datasetVisibility) {
    return mapper.selectStale(MyBatisDatasetVisibility.params(datasetVisibility));
  }
}
