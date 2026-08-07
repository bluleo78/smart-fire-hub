package com.smartfirehub.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.mapping.dto.MappingResponse;
import com.smartfirehub.mapping.dto.MappingSpec;
import com.smartfirehub.mapping.service.MappingService;
import com.smartfirehub.ontology.binding.DatasetOntologyService;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.ontology.service.OntologyService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.OntologyTestSupport;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// 상태 강제 검증 — 목록 필터가 아니라 bind()가 관문임을 고정한다.
// 이 테스트가 없으면 "UI에서 안 보이니 괜찮다"는 소프트 보장으로 되돌아간다.
class OntologyStatusEnforcementTest extends IntegrationTestBase {

  private static final long TEST_DATASET_ID = 999_101L;

  @Autowired private OntologyService service;
  @Autowired private OntologyRepository repository;
  @Autowired private DatasetOntologyService bindingService;
  @Autowired private MappingService mappingService;
  @Autowired private DSLContext dsl;

  private Long createdId;

  @AfterEach
  void cleanup() {
    dsl.deleteFrom(table(name("dataset_mapping")))
        .where(field(name("dataset_id"), Long.class).eq(TEST_DATASET_ID))
        .execute();
    dsl.deleteFrom(table(name("dataset_ontology")))
        .where(field(name("dataset_id"), Long.class).eq(TEST_DATASET_ID))
        .execute();
    OntologyTestSupport.deleteRow(dsl, createdId);
    createdId = null;
  }

  private long given(String domain, String status) {
    createdId = OntologyTestSupport.createWithStatus(service, domain, status);
    return createdId;
  }

  @Test
  void 초안_온톨로지에는_바인딩할_수_없다() {
    long id = given("강제 테스트 draft 바인딩", "draft");
    assertThatThrownBy(() -> bindingService.bind(TEST_DATASET_ID, id, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("초안");
  }

  @Test
  void 은퇴한_온톨로지에는_바인딩할_수_없다() {
    long id = given("강제 테스트 archived 바인딩", "archived");
    assertThatThrownBy(() -> bindingService.bind(TEST_DATASET_ID, id, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("은퇴");
  }

  @Test
  void 운영_중인_온톨로지에는_바인딩할_수_있다() {
    long id = given("강제 테스트 active 바인딩", "active");
    assertThatCode(() -> bindingService.bind(TEST_DATASET_ID, id, 1L)).doesNotThrowAnyException();
  }

  @Test
  void 존재하지_않는_온톨로지_바인딩은_기존대로_거부된다() {
    assertThatThrownBy(() -> bindingService.bind(TEST_DATASET_ID, 999_999L, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("존재하지 않는");
  }

  // archived의 정의는 "신규 바인딩만 차단, 기존 적재는 보존"이다. 이미 active인 매핑은 그 사이 온톨로지가
  // 은퇴했더라도 재투영(ai-agent의 loadTableGraph)이 소비할 수 있어야 한다. loadTableGraph는
  // GET /datasets/{id}/mapping(MappingService.get())으로 매핑을 읽으므로, 이 조회·재사용 경로에
  // 상태 가드가 없다는 것을 고정한다. 활성화(activate) 자체는 이미 active 상태에서만 요구되므로
  // 여기서는 활성화 이후 온톨로지가 archived로 전이된 뒤의 "조회" 경로만 검증한다.
  @Test
  void 은퇴된_온톨로지의_기존_active_매핑은_재투영을_위해_계속_조회된다() {
    long ontologyId = given("강제 테스트 재투영 가드", "active");
    bindingService.bind(TEST_DATASET_ID, ontologyId, 1L);

    // 엔티티/관계를 비운 최소 스펙 — conformance 검증이 데이터셋 컬럼 존재를 요구하지 않으므로
    // 실제 데이터셋 없이도(TEST_DATASET_ID는 dataset_ontology와 마찬가지로 FK 없는 참조 id) 저장·활성화된다.
    MappingSpec emptySpec = new MappingSpec(List.of(), List.of());
    mappingService.save(TEST_DATASET_ID, emptySpec, 1L);
    MappingResponse activated = mappingService.activate(TEST_DATASET_ID, 1L);
    assertThat(activated.status()).isEqualTo("active");

    // 온톨로지를 archived로 전이 — 신규 바인딩(bind)만 막혀야 하고, 이미 active인 매핑 조회는 막히면 안 된다.
    OntologyTestSupport.transitionTo(service, ontologyId, "archived");

    Optional<MappingResponse> reprojected = mappingService.get(TEST_DATASET_ID);
    assertThat(reprojected).isPresent();
    assertThat(reprojected.get().status()).isEqualTo("active");
    assertThat(reprojected.get().ontologyId()).isEqualTo(ontologyId);

    // 재투영은 매핑뿐 아니라 온톨로지 스키마도 읽는다(graphrag-tools.ts의 graphrag_project_table:
    // getDatasetMapping → getOntologyById 순으로 두 번 조회). 은퇴한 온톨로지의 by-id 조회를
    // "일관성"을 이유로 막으면 매핑 조회는 통과해도 여기서 재투영이 죽는다 — 기존에 이미 적재된
    // 파이프라인이 소급해 깨지는 것이므로, getById가 archived 상태에서도 계속 성공해야 한다.
    OntologyResponse schema = service.getById(ontologyId);
    assertThat(schema.domain()).isEqualTo("강제 테스트 재투영 가드");
  }
}
