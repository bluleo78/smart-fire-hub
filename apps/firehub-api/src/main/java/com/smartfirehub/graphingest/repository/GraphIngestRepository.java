package com.smartfirehub.graphingest.repository;

import com.smartfirehub.graphingest.dto.GraphIngestRecord;
import com.smartfirehub.graphingest.dto.GraphIngestRecord.StaleRow;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.util.List;

/**
 * 그래프 적재 이력(dataset_graph_ingest 테이블) 저장·조회 리포지토리. 최신성 판정에 dataset·dataset_ontology·ontology 를
 * 조인한다.
 *
 * <p>구현은 {@code JooqGraphIngestRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface GraphIngestRepository {

  /** GraphRAG 적재 이력 1행 INSERT, 생성된 id 반환. */
  long save(
      long datasetId,
      int schemaVersionAtIngest,
      int chunkCount,
      int nodeCount,
      int edgeCount,
      int extractionFailures,
      String status);

  /** 특정 데이터셋의 적재 이력(최신순). */
  List<GraphIngestRecord> findByDataset(long datasetId);

  /**
   * 데이터셋별 "최신 적재행"의 schema_version이, 그 데이터셋이 실제 바인딩된 온톨로지의 현재 schema_version보다 낮은(=낡은) 데이터셋 목록.
   * 데이터셋마다 자기 바인딩 온톨로지를 기준으로 비교한다 — 온톨로지가 여러 개인 상황에서 모든 데이터셋을 하나의 "기본" 버전과 비교하던 과거 버그를 고친다. 바인딩이 없는
   * 데이터셋은 애초에 적재가 불가능해졌으므로(ontology-source.ts 참고) 결과에 나타나지 않는다.
   *
   * <p>{@code DISTINCT ON (dataset_id)} 로 데이터셋별 최신행을 뽑은 뒤(같은 시각이면 id DESC 로 타이브레이크), 파생
   * 테이블(latest)의 컬럼을 문자열 한정 이름(name())으로 재참조하여 필터링한다. jOOQ의 {@code Table.field(Field)} API는 원본
   * Field 객체를 그대로 넘기면 파생 테이블에서 매칭되지 않는 경우가 있어, 명시적으로 {@code field(name("latest", "col"),
   * Type.class)} 형태를 사용한다.
   *
   * @param datasetVisibility {@code "dataset"} 이름 관례로 렌더하는
   *     가시성(JooqDatasetVisibility#condition(DatasetVisibility)) — 조회자가 볼 수 없는 데이터셋 id 는 결과에서 뺀다(보안
   *     등급, 스펙 §2.5 존재 은닉). 필터 없이 쓰려면 DatasetVisibility.UNRESTRICTED
   */
  List<StaleRow> findStale(DatasetVisibility datasetVisibility);
}
