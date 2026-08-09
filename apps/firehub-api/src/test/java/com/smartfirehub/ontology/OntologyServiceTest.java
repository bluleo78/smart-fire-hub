package com.smartfirehub.ontology;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.ontology.dto.GraphResponse;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.ontology.service.OntologyService;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

// OntologyService 단위 테스트 — MockWebServer로 ai-agent를 흉내내어 getGraph 프록시가
// camelCase 필드를 정확히 역직렬화하고 실패 응답(502)을 예외로 전파하는지 검증한다.
// getOntology 는 DB 읽기로 전환되어 OntologyRepositoryTest 가 커버한다(여기선 리포지토리 mock).
// Spring 컨텍스트 없이 서비스 객체를 직접 생성하는 순수 단위 테스트.
class OntologyServiceTest {
  private MockWebServer server;
  private OntologyService service;
  private OntologyRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    repository = mock(OntologyRepository.class);
    service =
        new OntologyService(
            server.url("/").toString(),
            "test-token",
            repository,
            mock(AuditLogService.class),
            mock(UserRepository.class));
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  // getGraph()의 다중 단어 camelCase 필드(sourceChunkCount, subjectKey, objectKey)가
  // Jackson 역직렬화 시 이름 불일치 없이 정확히 매핑되는지 검증한다.
  // schemaVersion(5-4)은 두 노드로 있음/없음(레거시) 두 경로를 모두 확인한다.
  @Test
  void getGraph_는_노드와_엣지의_camelCase_필드를_정확히_역직렬화한다() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"nodes\":[{\"key\":\"n1\",\"type\":\"Incident\",\"name\":\"화재\",\"sourceChunkCount\":3,\"schemaVersion\":2},"
                    + "{\"key\":\"n2\",\"type\":\"Cause\",\"name\":\"누전\",\"sourceChunkCount\":1}],"
                    + "\"edges\":[{\"subjectKey\":\"n1\",\"type\":\"CAUSED_BY\",\"objectKey\":\"n2\"}]}"));
    GraphResponse res = service.getGraph();
    assertThat(res.nodes().get(0).sourceChunkCount()).isEqualTo(3);
    assertThat(res.nodes().get(0).key()).isEqualTo("n1");
    assertThat(res.nodes().get(0).type()).isEqualTo("Incident");
    assertThat(res.nodes().get(0).name()).isEqualTo("화재");
    assertThat(res.nodes().get(0).schemaVersion()).isEqualTo(2);
    assertThat(res.nodes().get(1).schemaVersion()).isNull(); // schemaVersion 미포함 응답 → 레거시 노드
    assertThat(res.edges().get(0).subjectKey()).isEqualTo("n1");
    assertThat(res.edges().get(0).type()).isEqualTo("CAUSED_BY");
    assertThat(res.edges().get(0).objectKey()).isEqualTo("n2");
  }

  @Test
  void getGraph_는_ai_agent_502를_예외로_전파한다() {
    server.enqueue(new MockResponse().setResponseCode(502).setBody("{\"error\":\"graph read failed\"}"));
    assertThatThrownBy(() -> service.getGraph()).isInstanceOf(ExternalServiceException.class);
  }

  // 생성 검증(#305): 생성 경로도 동일한 validateCore를 타므로 null description을 400으로 거부한다.
  @Test
  void createOntology_는_null_엔티티_description을_거부한다() {
    var bad =
        new com.smartfirehub.ontology.dto.CreateOntologyRequest(
            "판매",
            List.of(new OntologyResponse.EntityType("Customer", null, "n", "exact", List.of())),
            List.of());
    assertThatThrownBy(() -> service.createOntology(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("엔티티 설명(description)은 null일 수 없습니다");
    verify(repository, never()).createOntology(any());
  }

  // (Task 7 리뷰 C-1) validateCore의 검사 순서 회귀 가드 — 관계명은 유효하지만 description이 null이고
  // subject가 존재하지 않는 타입을 참조하는 경우, subject 참조 무결성 에러가 description 에러보다 먼저
  // 떠야 원본 순서(관계명 blank → subject 존재 → object 존재 → description null)와 같다. 이 순서는
  // validateCore 안에 있고 createOntology/changeStatus가 여전히 그 메서드를 부르므로, PUT이 사라져도
  // 가드는 createOntology 경로로 계속 지켜야 한다(원래 updateOntology_는_관계명이_유효해도_description보다
  // _subject_참조를_먼저_검사한다가 지키던 것).
  @Test
  void createOntology_는_관계명이_유효해도_description보다_subject_참조를_먼저_검사한다() {
    var bad =
        new com.smartfirehub.ontology.dto.CreateOntologyRequest(
            "d",
            List.of(new OntologyResponse.EntityType("Incident", "a", "n", "exact", List.of())),
            List.of(new OntologyResponse.Triple("Ghost", "OCCURRED_AT", "Incident", null)));
    assertThatThrownBy(() -> service.createOntology(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("관계가 존재하지 않는 엔티티 타입을 참조합니다(subject)");
    verify(repository, never()).createOntology(any());
  }

  // 유효 생성 페이로드 헬퍼.
  private com.smartfirehub.ontology.dto.CreateOntologyRequest validCreate() {
    return new com.smartfirehub.ontology.dto.CreateOntologyRequest(
        "판매",
        List.of(new OntologyResponse.EntityType("Customer", "고객", "표기 그대로", "exact", List.of())),
        List.of());
  }

  // 검증 통과 시 리포지토리에 위임하고 발급 id를 그대로 반환한다.
  @Test
  void createOntology_는_검증후_리포지토리에_위임한다() {
    when(repository.createOntology(any())).thenReturn(5L);
    long id = service.createOntology(validCreate());
    assertThat(id).isEqualTo(5L);
    verify(repository).createOntology(any());
  }

  // 코드리뷰 결함 #1: 사전 검사가 리포지토리 existsLiveDomain을 거쳐 IllegalStateException(→409)으로
  // 막는지 확인한다. 사전 검사 없이 INSERT까지 가면 DB 제약 위반이 영문 문구로 새어나간다.
  @Test
  void createOntology_는_도메인_중복이면_409를_던지고_INSERT를_시도하지_않는다() {
    when(repository.existsLiveDomain("판매")).thenReturn(true);
    assertThatThrownBy(() -> service.createOntology(validCreate()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("이미 같은 도메인의 온톨로지가 있습니다");
    verify(repository, never()).createOntology(any());
  }

  // 도메인 공백은 IllegalArgumentException(→400)이며 리포지토리를 호출하지 않는다.
  @Test
  void createOntology_도메인_공백이면_거부하고_리포지토리_미호출() {
    var bad =
        new com.smartfirehub.ontology.dto.CreateOntologyRequest(
            "  ",
            List.of(new OntologyResponse.EntityType("A", "a", "n", "exact", List.of())),
            List.of());
    assertThatThrownBy(() -> service.createOntology(bad))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, never()).createOntology(any());
  }

  // draft로 생성할 때는 엔티티가 0개여도 통과해야 한다 — UI가 "도메인명만 받아 빈 껍데기를 만들고
  // 편집기로 채우는" 흐름을 쓰기 때문. 완전성은 활성화 시점에 검사한다.
  @Test
  void createOntology_는_draft면_엔티티_0개를_허용한다() {
    when(repository.createOntology(any())).thenReturn(42L);
    var empty =
        new com.smartfirehub.ontology.dto.CreateOntologyRequest(
            "빈 초안 도메인", List.of(), List.of(), "draft");

    long id = service.createOntology(empty);

    assertThat(id).isEqualTo(42L);
    verify(repository).createOntology(any());
  }

  // active로 생성할 때는 기존 규칙 그대로 — 엔티티 최소 1개.
  @Test
  void createOntology_는_active면_엔티티_0개를_거부한다() {
    var empty =
        new com.smartfirehub.ontology.dto.CreateOntologyRequest(
            "빈 활성 도메인", List.of(), List.of(), "active");

    assertThatThrownBy(() -> service.createOntology(empty))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("엔티티 타입은 최소 1개");
    verify(repository, never()).createOntology(any());
  }

  // 형식 검증은 draft에도 적용된다 — 잘못된 resolution은 초안이라도 저장하면 안 된다.
  @Test
  void createOntology_는_draft여도_잘못된_resolution을_거부한다() {
    var bad =
        new com.smartfirehub.ontology.dto.CreateOntologyRequest(
            "잘못된 초안 도메인",
            List.of(new OntologyResponse.EntityType("Incident", "설명", "명명", "invalid", List.of())),
            List.of(),
            "draft");

    assertThatThrownBy(() -> service.createOntology(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resolution");
    verify(repository, never()).createOntology(any());
  }

  // archived 상태로는 생성할 수 없다 — 은퇴는 운영을 마친 것에 대한 조치이지 생성 시점의 선택이 아니다.
  @Test
  void createOntology_는_archived_생성을_거부한다() {
    var archived =
        new com.smartfirehub.ontology.dto.CreateOntologyRequest(
            "은퇴 생성 시도 도메인",
            List.of(new OntologyResponse.EntityType("Incident", "설명", "명명", "exact", List.of())),
            List.of(),
            "archived");

    assertThatThrownBy(() -> service.createOntology(archived))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("archived");
    verify(repository, never()).createOntology(any());
  }

  // 관계 참조 무결성은 형식 검증이라 draft에도 적용된다 — draft라고 존재하지 않는 엔티티 타입을
  // 참조하는 관계까지 허용하면 안 된다(직전 태스크의 회귀 가드).
  @Test
  void createOntology_는_draft여도_존재하지_않는_타입을_참조하는_관계를_거부한다() {
    var bad =
        new com.smartfirehub.ontology.dto.CreateOntologyRequest(
            "존재하지 않는 타입 참조 draft 도메인",
            List.of(new OntologyResponse.EntityType("Incident", "설명", "명명", "exact", List.of())),
            List.of(new OntologyResponse.Triple("Incident", "OCCURRED_AT", "Building", "설명")),
            "draft");

    assertThatThrownBy(() -> service.createOntology(bad)).isInstanceOf(IllegalArgumentException.class);
    verify(repository, never()).createOntology(any());
  }

  // draft/active/archived 외의 임의 문자열(오타 등)이 그대로 통과하면 "active".equals(status)가 false가
  // 되어 완전성 게이트를 조용히 건너뛰고 DB CHECK 제약에서 500으로 터진다 — 생성 경로도 400으로 막는다.
  @Test
  void createOntology_는_알수없는_status를_거부한다() {
    var bad =
        new com.smartfirehub.ontology.dto.CreateOntologyRequest(
            "오타 status 도메인",
            List.of(new OntologyResponse.EntityType("Incident", "설명", "명명", "exact", List.of())),
            List.of(),
            "acitve");

    assertThatThrownBy(() -> service.createOntology(bad)).isInstanceOf(IllegalArgumentException.class);
    verify(repository, never()).createOntology(any());
  }

  // getById는 리포지토리 findById에 위임한다.
  @Test
  void getById_는_findById에_위임한다() {
    var expected = new OntologyResponse("판매", 1, List.of(), List.of());
    when(repository.findById(2L)).thenReturn(expected);
    assertThat(service.getById(2L)).isEqualTo(expected);
  }
}
