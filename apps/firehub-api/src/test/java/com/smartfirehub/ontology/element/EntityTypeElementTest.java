package com.smartfirehub.ontology.element;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.PatchOntologyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateEntityTypeRequest;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.support.OntologyTestSupport;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 요소 단위 엔티티 타입 CRUD — 전체 스키마를 왕복시키지 않고 타입 하나만 고치는 경로.
// 픽스처(자기 온톨로지 생성)는 OntologyElementTestSupport가 소유한다 — Task 4·5의 테스트도 같은 걸 쓴다.
// 컨트롤러 계층 테스트(POST_entity_types...)를 위해 @AutoConfigureMockMvc를 추가하고, 실제 DB 권한
// 시딩 대신 OntologyControllerTest와 동일하게 JwtTokenProvider/PermissionService를 목으로 대체한다
// (풀 컨텍스트 통합 테스트라 실빈이 기본이므로, 이 두 빈만 @MockitoBean으로 오버라이드한다).
@AutoConfigureMockMvc
class EntityTypeElementTest extends OntologyElementTestSupport {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUpAuth() {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("ontology:write"));
  }

  @Test
  void 도메인을_패치하면_새_schemaVersion과_변경된_도메인을_반환한다() {
    int before = ontologyRepository.currentSchemaVersion(ontologyId);

    var result = elementService.patchDomain(ontologyId, new PatchOntologyRequest("새 도메인"));

    assertThat(result.schemaVersion()).isEqualTo(before + 1);
    assertThat(ontologyRepository.findById(ontologyId).domain()).isEqualTo("새 도메인");
  }

  // 새 값이 현재 도메인과 같은 재제출(no-op)은 existsLiveDomain이 자기 자신과 충돌시키지 않도록
  // 예외 처리돼야 한다 — 안 그러면 화면을 그대로 다시 저장하기만 해도 409가 난다.
  @Test
  void 자기_자신의_현재_도메인으로_패치하면_성공한다() {
    String currentDomain = ontologyRepository.findById(ontologyId).domain();

    var result = elementService.patchDomain(ontologyId, new PatchOntologyRequest(currentDomain));

    assertThat(result.schemaVersion()).isEqualTo(2); // draft 생성(1) 다음 첫 편집
  }

  // OntologyService.createOntology와 동일한 사전 검사가 요소 단위 PATCH 경로에도 있어야
  // V79 부분 유니크 인덱스 위반이 영문 "Data integrity violation" 409로 새어나가지 않는다.
  @Test
  void 다른_살아있는_온톨로지와_같은_도메인으로_패치하면_기존_문구와_동일하게_거부된다() {
    long otherId = OntologyTestSupport.createWithStatus(ontologyService, "충돌도메인-" + System.nanoTime(), "active");
    try {
      String otherDomain = ontologyRepository.findById(otherId).domain();

      assertThatThrownBy(() -> elementService.patchDomain(ontologyId, new PatchOntologyRequest(otherDomain)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("이미 같은 도메인의 온톨로지가 있습니다: " + otherDomain);
    } finally {
      OntologyTestSupport.deleteRow(dsl, otherId);
    }
  }

  @Test
  void 타입을_추가하면_새_id와_증가된_schemaVersion을_반환한다() {
    int before = ontologyRepository.currentSchemaVersion(ontologyId);

    var result = elementService.addEntityType(ontologyId,
        new CreateEntityTypeRequest("Gateway", "게이트웨이", "표기 그대로", "exact"));

    assertThat(result.entityType().id()).isNotNull();
    assertThat(result.entityType().type()).isEqualTo("Gateway");
    assertThat(result.schemaVersion()).isEqualTo(before + 1);
    assertThat(ontologyRepository.findById(ontologyId).entities())
        .extracting(OntologyResponse.EntityType::type)
        .containsExactly("Sensor", "Building", "Gateway"); // sort_order = max+1로 뒤에 붙는다
  }

  @Test
  void 중복된_타입명_추가는_기존_문구와_동일한_400으로_거부된다() {
    assertThatThrownBy(() -> elementService.addEntityType(ontologyId,
        new CreateEntityTypeRequest("Sensor", "중복", "x", "exact")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("중복된 엔티티 타입명: Sensor");
  }

  // (Task 7 리뷰 I-1) naming은 NOT NULL 컬럼(#305) — null이 그대로 INSERT되면 제약 위반 500이 새어나간다.
  // OntologyRules.validateEntityTypeCommon이 요소 경로(addEntityType)에서도 이 규칙을 실제로 막는지 고정한다.
  @Test
  void null_naming을_가진_타입_추가는_거부된다() {
    assertThatThrownBy(() -> elementService.addEntityType(ontologyId,
        new CreateEntityTypeRequest("Gateway", "x", null, "exact")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("엔티티 명명 규칙(naming)은 null일 수 없습니다");
  }

  // (Task 7 리뷰 I-2) 컬럼 제약은 NOT NULL일 뿐 NOT BLANK가 아니다 — 빈 문자열 description/naming은
  // 허용해야 기존에 그렇게 저장된 데이터가 요소 경로로도 정상 왕복(재조회→재저장)된다. 후일 blank까지
  // 막는 강화가 들어오면 이 테스트가 먼저 깨져 "의도된 변경"인지 확인하게 만드는 캐너리다.
  @Test
  void 빈_문자열_description과_naming은_허용된다() {
    var result = elementService.addEntityType(ontologyId,
        new CreateEntityTypeRequest("Gateway", "", "", "exact"));

    assertThat(result.entityType().description()).isEmpty();
    assertThat(result.entityType().naming()).isEmpty();
  }

  @Test
  void 리네임은_관계를_건드리지_않고_id를_보존한다() {
    long sensorId = typeId("Sensor");

    var result = elementService.updateEntityType(ontologyId, sensorId,
        new UpdateEntityTypeRequest("Detector", null, null, null));

    assertThat(result.entityType().id()).isEqualTo(sensorId);
    OntologyResponse after = ontologyRepository.findById(ontologyId);
    assertThat(after.entities()).extracting(OntologyResponse.EntityType::type)
        .containsExactly("Detector", "Building");
    // 관계는 FK로 매달려 있으므로 이름만 따라 바뀐다 — 별도 갱신이 없어야 한다.
    assertThat(after.relations()).singleElement().satisfies(t -> {
      assertThat(t.subject()).isEqualTo("Detector");
      assertThat(t.relation()).isEqualTo("INSTALLED_IN");
      assertThat(t.object()).isEqualTo("Building");
    });
  }

  @Test
  void 타입을_삭제하면_참조_관계도_함께_사라지고_그_id가_응답에_담긴다() {
    long relationId = ontologyRepository.findById(ontologyId).relations().get(0).id();

    var result = elementService.deleteEntityType(ontologyId, typeId("Sensor"));

    assertThat(result.deletedRelationIds()).containsExactly(relationId);
    OntologyResponse after = ontologyRepository.findById(ontologyId);
    assertThat(after.entities()).extracting(OntologyResponse.EntityType::type).containsExactly("Building");
    assertThat(after.relations()).isEmpty();
  }

  @Test
  void active_온톨로지의_마지막_타입은_삭제할_수_없다() {
    // draft로 만든 뒤 타입 1개만 남기고 활성화한다 — active의 완전성 게이트를 재현하기 위함.
    elementService.deleteEntityType(ontologyId, typeId("Sensor"));
    ontologyService.changeStatus(ontologyId, "active");

    assertThatThrownBy(() -> elementService.deleteEntityType(ontologyId, typeId("Building")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("엔티티 타입은 최소 1개 이상이어야 합니다.");
  }

  @Test
  void archived_온톨로지는_요소_편집이_거부된다() {
    ontologyService.changeStatus(ontologyId, "active");
    ontologyService.changeStatus(ontologyId, "archived");

    assertThatThrownBy(() -> elementService.addEntityType(ontologyId,
        new CreateEntityTypeRequest("Gateway", "x", "y", "exact")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("은퇴한 온톨로지는 편집할 수 없습니다. 먼저 복귀시키세요.");
  }

  // 라우팅·권한·직렬화가 실제로 붙어 있는지 한 번은 컨트롤러를 통해 확인한다.
  @Test
  void POST_entity_types는_생성된_타입과_새_버전을_JSON으로_반환한다() throws Exception {
    mockMvc.perform(post("/api/v1/ontology/{id}/entity-types", ontologyId)
            .header("Authorization", "Bearer valid-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"Gateway","description":"게이트웨이","naming":"표기 그대로","resolution":"exact"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entityType.type").value("Gateway"))
        .andExpect(jsonPath("$.entityType.id").isNumber())
        .andExpect(jsonPath("$.schemaVersion").isNumber());
  }

}
