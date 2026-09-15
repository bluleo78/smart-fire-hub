package com.smartfirehub.graphingest.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.graphingest.dto.GraphIngestRecord;
import com.smartfirehub.graphingest.dto.GraphIngestRecord.StaleRow;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * dataset_graph_ingest 읽기/쓰기 리포지토리.
 *
 * <p>jOOQ 코드젠(생성 클래스)에 의존하지 않는 plain-SQL {@code name()} 스타일을 사용한다.
 * ({@link com.smartfirehub.audit.repository.AuditLogRepository} 선례를 따름)
 */
@Repository
@RequiredArgsConstructor
// RLS GUC 는 트랜잭션 시작 시점에만 주입되는데 호출자 GraphIngestService(record/history/findStale)는
// 트랜잭션 경계를 만들지 않는다. 여기서 열지 않으면 record 는 tenant_id NOT NULL 위반, findStale 은
// 조용히 0행 — 즉 staleness 감지가 에러 없이 영원히 발화하지 않는다. 지우지 말 것.
@Transactional
public class GraphIngestRepository {

  private final DSLContext dsl;

  private static final Table<?> T = table(name("dataset_graph_ingest"));
  private static final Field<Long> ID = field(name("dataset_graph_ingest", "id"), Long.class);
  private static final Field<Long> DATASET_ID =
      field(name("dataset_graph_ingest", "dataset_id"), Long.class);
  private static final Field<LocalDateTime> INGESTED_AT =
      field(name("dataset_graph_ingest", "ingested_at"), LocalDateTime.class);
  private static final Field<Integer> SCHEMA_VER =
      field(name("dataset_graph_ingest", "schema_version_at_ingest"), Integer.class);
  private static final Field<Integer> CHUNK =
      field(name("dataset_graph_ingest", "chunk_count"), Integer.class);
  private static final Field<Integer> NODE =
      field(name("dataset_graph_ingest", "node_count"), Integer.class);
  private static final Field<Integer> EDGE =
      field(name("dataset_graph_ingest", "edge_count"), Integer.class);
  private static final Field<Integer> FAILURES =
      field(name("dataset_graph_ingest", "extraction_failures"), Integer.class);
  private static final Field<String> STATUS =
      field(name("dataset_graph_ingest", "status"), String.class);

  // 데이터셋↔온톨로지 바인딩(dataset_ontology) + 온톨로지(ontology) — findStale()이 데이터셋별로
  // 자기 바인딩 온톨로지의 현재 schema_version과 비교하기 위한 조인 대상.
  private static final Table<?> DATASET_ONTOLOGY = table(name("dataset_ontology"));
  private static final Field<Long> DO_DATASET_ID =
      field(name("dataset_ontology", "dataset_id"), Long.class);
  private static final Field<Long> DO_ONTOLOGY_ID =
      field(name("dataset_ontology", "ontology_id"), Long.class);
  private static final Table<?> ONTOLOGY = table(name("ontology"));
  private static final Field<Long> O_ID = field(name("ontology", "id"), Long.class);
  private static final Field<Integer> O_SCHEMA_VER = field(name("ontology", "schema_version"), Integer.class);

  /** GraphRAG 적재 이력 1행 INSERT, 생성된 id 반환. */
  public long save(
      long datasetId,
      int schemaVersionAtIngest,
      int chunkCount,
      int nodeCount,
      int edgeCount,
      int extractionFailures,
      String status) {
    return dsl.insertInto(T)
        .set(DATASET_ID, datasetId)
        .set(SCHEMA_VER, schemaVersionAtIngest)
        .set(CHUNK, chunkCount)
        .set(NODE, nodeCount)
        .set(EDGE, edgeCount)
        .set(FAILURES, extractionFailures)
        .set(STATUS, status)
        .returning(ID)
        .fetchOne()
        .get(ID);
  }

  /** 특정 데이터셋의 적재 이력(최신순). */
  public List<GraphIngestRecord> findByDataset(long datasetId) {
    return dsl.select(ID, DATASET_ID, INGESTED_AT, SCHEMA_VER, CHUNK, NODE, EDGE, FAILURES, STATUS)
        .from(T)
        .where(DATASET_ID.eq(datasetId))
        .orderBy(INGESTED_AT.desc())
        .fetch(
            r ->
                new GraphIngestRecord(
                    r.get(ID),
                    r.get(DATASET_ID),
                    r.get(INGESTED_AT),
                    r.get(SCHEMA_VER),
                    r.get(CHUNK),
                    r.get(NODE),
                    r.get(EDGE),
                    r.get(FAILURES),
                    r.get(STATUS)));
  }

  /**
   * 데이터셋별 "최신 적재행"의 schema_version이, 그 데이터셋이 실제 바인딩된 온톨로지의 현재
   * schema_version보다 낮은(=낡은) 데이터셋 목록. 데이터셋마다 자기 바인딩 온톨로지를 기준으로 비교한다
   * — 온톨로지가 여러 개인 상황에서 모든 데이터셋을 하나의 "기본" 버전과 비교하던 과거 버그를 고친다.
   * 바인딩이 없는 데이터셋은 애초에 적재가 불가능해졌으므로(ontology-source.ts 참고) 결과에 나타나지 않는다.
   *
   * <p>{@code DISTINCT ON (dataset_id)} 로 데이터셋별 최신행을 뽑은 뒤(같은 시각이면 id DESC 로 타이브레이크),
   * 파생 테이블(latest)의 컬럼을 문자열 한정 이름(name())으로 재참조하여 필터링한다.
   * jOOQ의 {@code Table.field(Field)} API는 원본 Field 객체를 그대로 넘기면 파생 테이블에서
   * 매칭되지 않는 경우가 있어, 명시적으로 {@code field(name("latest", "col"), Type.class)} 형태를 사용한다.
   */
  public List<StaleRow> findStale() {
    Table<?> latest =
        dsl.select(DATASET_ID, INGESTED_AT, SCHEMA_VER)
            .distinctOn(DATASET_ID)
            .from(T)
            .orderBy(DATASET_ID, INGESTED_AT.desc(), ID.desc())
            .asTable("latest");

    Field<Long> lDataset = field(name("latest", "dataset_id"), Long.class);
    Field<LocalDateTime> lAt = field(name("latest", "ingested_at"), LocalDateTime.class);
    Field<Integer> lVer = field(name("latest", "schema_version_at_ingest"), Integer.class);

    return dsl.select(lDataset, lAt, lVer, O_SCHEMA_VER)
        .from(latest)
        .join(DATASET_ONTOLOGY).on(DO_DATASET_ID.eq(lDataset))
        .join(ONTOLOGY).on(O_ID.eq(DO_ONTOLOGY_ID))
        .where(lVer.lt(O_SCHEMA_VER))
        .fetch(r -> new StaleRow(r.get(lDataset), r.get(lAt), r.get(lVer), r.get(O_SCHEMA_VER)));
  }
}
