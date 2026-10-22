package com.smartfirehub.mapping.repository;

import static org.jooq.impl.DSL.currentOffsetDateTime;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.when;

import com.smartfirehub.ontology.graphread.GraphOntologySourceRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

// 데이터셋 매핑 문서 저장/조회 — OntologyRepository와 동일 plain-SQL DSL(codegen 비의존).
@Repository
@RequiredArgsConstructor
// RLS GUC 는 트랜잭션 시작 시점에만 주입되는데 호출자 MappingService.get 은 트랜잭션 경계를 만들지
// 않는다. 여기서 열지 않으면 조회가 조용히 0행 — save/activate 는 서비스 트랜잭션이라 쓰기만 성공하고
// "저장했는데 UI 에는 미매핑" 으로 나타난다. 지우지 말 것.
@Transactional
public class MappingRepository {

  private final DSLContext dsl;
  // WD-28: 매핑 저장 시 출처를 남긴다. 같은 트랜잭션(이 클래스의 @Transactional)에 합류한다.
  private final GraphOntologySourceRepository sourceRepository;

  private static final Table<?> DATASET_MAPPING = table(name("dataset_mapping"));
  private static final Field<Long> M_DATASET_ID =
      field(name("dataset_mapping", "dataset_id"), Long.class);
  // V101 에서 dataset_id 유니크가 (tenant_id, dataset_id) 로 접혔다. ON CONFLICT 추론 대상도
  // 같은 컬럼 집합이어야 하므로 tenant_id 를 참조한다. 값 자체는 INSERT 에서 세팅하지 않고
  // 컬럼 DEFAULT(GUC app.tenant_id)가 채운다 — 앱이 테넌트를 직접 쓰지 않는다는 원칙 유지.
  private static final Field<Long> M_TENANT_ID =
      field(name("dataset_mapping", "tenant_id"), Long.class);
  private static final Field<Long> M_ONTOLOGY_ID =
      field(name("dataset_mapping", "ontology_id"), Long.class);
  private static final Field<JSONB> M_SPEC = field(name("dataset_mapping", "spec"), JSONB.class);
  private static final Field<String> M_STATUS =
      field(name("dataset_mapping", "status"), String.class);
  private static final Field<Long> M_UPDATED_BY =
      field(name("dataset_mapping", "updated_by"), Long.class);
  private static final Field<java.time.OffsetDateTime> M_UPDATED_AT =
      field(name("dataset_mapping", "updated_at"), java.time.OffsetDateTime.class);

  // WD-129: 온톨로지 요소 삭제 차단 문구에 데이터셋 이름을 싣기 위한 조인 대상. 별칭 없이 "dataset" 이름을 쓰는 것은
  // DatasetAccessGuard#visibleCondition() 이 그 이름 관례로 조건을 만들기 때문이다.
  private static final Table<?> DATASET = table(name("dataset"));
  private static final Field<Long> D_ID = field(name("dataset", "id"), Long.class);
  private static final Field<String> D_NAME = field(name("dataset", "name"), String.class);

  /** 한 온톨로지에 묶인 매핑 한 건(이름 변경 연쇄 갱신용) — spec 은 raw JSON. */
  public record OntologyMappingRow(long datasetId, String specJson) {}

  /**
   * 데이터셋이 살아 있는 매핑 한 건(사용처 조회용). datasetName 은 조회자가 볼 수 없는 데이터셋이면 null 이다 — 보안 등급상 이름을 보여 줄 수 없는
   * 데이터셋도 "사용 중"이라는 사실은 숨기지 않는다(삭제는 똑같이 막혀야 하므로).
   */
  public record LiveMappingRow(long datasetId, String datasetName, String specJson) {}

  // 매핑 문서 UPSERT — dataset_id UNIQUE라 재저장 시 spec/status/updated_* 갱신.
  public void upsert(long datasetId, long ontologyId, String specJson, String status, Long userId) {
    JSONB spec = JSONB.valueOf(specJson);
    dsl.insertInto(DATASET_MAPPING)
        .set(M_DATASET_ID, datasetId)
        .set(M_ONTOLOGY_ID, ontologyId)
        .set(M_SPEC, spec)
        .set(M_STATUS, status)
        .set(M_UPDATED_BY, userId)
        .set(M_UPDATED_AT, currentOffsetDateTime())
        .onConflict(M_TENANT_ID, M_DATASET_ID)
        .doUpdate()
        .set(M_ONTOLOGY_ID, ontologyId)
        .set(M_SPEC, spec)
        .set(M_STATUS, status)
        .set(M_UPDATED_BY, userId)
        .set(M_UPDATED_AT, currentOffsetDateTime())
        .execute();
    // WD-28: 표 투영(graphrag_project_table)은 mapping.ontologyId 로 쓰므로 매핑 저장 시점에도 출처를 남긴다.
    sourceRepository.record(ontologyId, datasetId);
  }

  // 데이터셋 매핑 조회(없으면 empty).
  public Optional<StoredMapping> findByDataset(long datasetId) {
    return dsl.select(M_ONTOLOGY_ID, M_SPEC, M_STATUS)
        .from(DATASET_MAPPING)
        .where(M_DATASET_ID.eq(datasetId))
        .fetchOptional(
            r -> new StoredMapping(r.get(M_ONTOLOGY_ID), r.get(M_SPEC).data(), r.get(M_STATUS)));
  }

  // WD-186: 데이터셋 매핑을 행 잠금과 함께 읽는다(필드 이름 변경 연쇄·필드 삭제/타입 변경 판정 전용). 지식 모델 요소 이름 변경
  // (findByOntologyForUpdate)이 같은 행을 읽고-고쳐-쓰므로, 잠그지 않고 고치면 한쪽 갱신이 유실된다. 호출자는 이 앞에 온톨로지 행
  // (공유)·데이터셋 행 잠금을 먼저 잡는다(잠금 순서: 온톨로지 행 → 데이터셋 행 → 매핑 행).
  public Optional<StoredMapping> findByDatasetForUpdate(long datasetId) {
    return dsl.select(M_ONTOLOGY_ID, M_SPEC, M_STATUS)
        .from(DATASET_MAPPING)
        .where(M_DATASET_ID.eq(datasetId))
        .forUpdate()
        .fetchOptional(
            r -> new StoredMapping(r.get(M_ONTOLOGY_ID), r.get(M_SPEC).data(), r.get(M_STATUS)));
  }

  // 상태만 전환(draft→active). updated_by/at 함께 갱신.
  public void updateStatus(long datasetId, String status, Long userId) {
    dsl.update(DATASET_MAPPING)
        .set(M_STATUS, status)
        .set(M_UPDATED_BY, userId)
        .set(M_UPDATED_AT, currentOffsetDateTime())
        .where(M_DATASET_ID.eq(datasetId))
        .execute();
  }

  // WD-129: 이 온톨로지에 묶인 매핑 전부를 행 잠금과 함께 읽는다(이름 변경 연쇄 갱신 전용).
  // 동시성의 주 장치는 이 잠금이 아니다 — 호출자가 먼저 잡는 온톨로지 행 잠금(OntologyRepository.lockAndFindStatus)과 매핑
  // 저장의 FOR SHARE 가 리네임·삭제와 매핑 저장을 직렬화한다(잠금 순서: 온톨로지 행 → 매핑 행). 이 행 잠금은 그 규약을 거치지
  // 않는 다른 매핑 쓰기(예: 상태 전환)가 읽고-고쳐-쓰는 사이에 끼어 갱신이 유실되지 않게 하는 보조 장치다.
  // 데이터셋 존재 여부는 보지 않는다 — 데이터셋 삭제가 매핑 행을 지우지 않아 고아 매핑이 남을 수 있는데, 이름을 맞춰 두는 것은
  // 해가 없고 나중에 같은 매핑을 되살려도 어긋나지 않는다.
  public List<OntologyMappingRow> findByOntologyForUpdate(long ontologyId) {
    return dsl.select(M_DATASET_ID, M_SPEC)
        .from(DATASET_MAPPING)
        .where(M_ONTOLOGY_ID.eq(ontologyId))
        .orderBy(M_DATASET_ID)
        .forUpdate()
        .fetch(r -> new OntologyMappingRow(r.get(M_DATASET_ID), r.get(M_SPEC).data()));
  }

  // WD-129: 이 온톨로지에 묶이고 데이터셋이 아직 있는 매핑(사용처 판정·삭제 차단용).
  // 데이터셋과 INNER JOIN 하는 이유: 데이터셋을 지워도 dataset_mapping 행은 남는다(FK 없음, DatasetService.deleteDataset 이
  // 지우지 않음). 고아 매핑까지 세면 사용자가 손댈 수 없는 "사용 중"이 생겨 요소를 영영 지울 수 없다.
  // 이름은 nameVisible(보안 등급 가시성)일 때만 싣는다 — PipelineStepRepository 의 출력 데이터셋 이름과 같은 방식.
  public List<LiveMappingRow> findLiveByOntology(long ontologyId, Condition nameVisible) {
    Field<String> visibleName = when(nameVisible, D_NAME).as("visible_dataset_name");
    return dsl.select(M_DATASET_ID, visibleName, M_SPEC)
        .from(DATASET_MAPPING)
        .join(DATASET)
        .on(D_ID.eq(M_DATASET_ID))
        .where(M_ONTOLOGY_ID.eq(ontologyId))
        .orderBy(M_DATASET_ID)
        .fetch(
            r -> new LiveMappingRow(r.get(M_DATASET_ID), r.get(visibleName), r.get(M_SPEC).data()));
  }

  // WD-129: spec 만 교체한다(온톨로지 요소 이름 변경의 연쇄 갱신). upsert 를 쓰지 않는 이유: upsert 는 status 를 덮고
  // 그래프 출처를 다시 기록한다 — 이름만 맞추는 갱신이 active 매핑을 draft 로 떨어뜨리면 안 된다.
  public void updateSpec(long datasetId, String specJson) {
    dsl.update(DATASET_MAPPING)
        .set(M_SPEC, JSONB.valueOf(specJson))
        .set(M_UPDATED_AT, currentOffsetDateTime())
        .where(M_DATASET_ID.eq(datasetId))
        .execute();
  }
}
