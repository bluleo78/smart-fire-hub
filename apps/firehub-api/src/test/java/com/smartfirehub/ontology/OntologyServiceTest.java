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
import com.smartfirehub.ontology.dto.UpdateOntologyRequest;
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

  // 유효한 최소 편집 페이로드(엔티티 1개·관계 0개) — 기대 버전 1.
  private UpdateOntologyRequest validRequest() {
    return new UpdateOntologyRequest(
        "화재조사 보고서",
        1,
        List.of(new OntologyResponse.EntityType("Incident", "설명", "명명", "exact", List.of())),
        List.of());
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

  // 편집 검증: resolution 이 embedding|exact 가 아니면 IllegalArgumentException(→400), 리포지토리 미호출.
  @Test
  void updateOntology_는_잘못된_resolution을_거부한다() {
    UpdateOntologyRequest bad =
        new UpdateOntologyRequest(
            "d",
            1,
            List.of(new OntologyResponse.EntityType("Incident", "설명", "명명", "invalid", List.of())),
            List.of());
    assertThatThrownBy(() -> service.updateOntology(bad))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, org.mockito.Mockito.never()).updateOntology(any());
  }

  // 편집 검증: 중복 엔티티 타입명은 거부한다.
  @Test
  void updateOntology_는_중복_타입명을_거부한다() {
    UpdateOntologyRequest dup =
        new UpdateOntologyRequest(
            "d",
            1,
            List.of(
                new OntologyResponse.EntityType("Incident", "a", "n", "exact", List.of()),
                new OntologyResponse.EntityType("Incident", "b", "n", "exact", List.of())),
            List.of());
    assertThatThrownBy(() -> service.updateOntology(dup))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // 편집 검증: domain 이 비면 거부한다.
  @Test
  void updateOntology_는_빈_domain을_거부한다() {
    UpdateOntologyRequest blank =
        new UpdateOntologyRequest(
            "  ",
            1,
            List.of(new OntologyResponse.EntityType("Incident", "a", "n", "exact", List.of())),
            List.of());
    assertThatThrownBy(() -> service.updateOntology(blank))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // 편집 검증(5-2): 관계가 존재하지 않는 엔티티 타입을 참조하면 거부한다.
  @Test
  void updateOntology_는_존재하지_않는_타입을_참조하는_관계를_거부한다() {
    UpdateOntologyRequest bad =
        new UpdateOntologyRequest(
            "d",
            1,
            List.of(new OntologyResponse.EntityType("Incident", "a", "n", "exact", List.of())),
            List.of(new OntologyResponse.Triple("Incident", "OCCURRED_AT", "Building", "설명")));
    assertThatThrownBy(() -> service.updateOntology(bad))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, org.mockito.Mockito.never()).updateOntology(any());
  }

  // 편집 검증(5-2): 동일한 (subject, relation, object) 트리플이 중복되면 거부한다.
  @Test
  void updateOntology_는_중복된_관계를_거부한다() {
    var entities =
        List.of(
            new OntologyResponse.EntityType("Incident", "a", "n", "exact", List.of()),
            new OntologyResponse.EntityType("Building", "b", "n", "embedding", List.of()));
    UpdateOntologyRequest dup =
        new UpdateOntologyRequest(
            "d",
            1,
            entities,
            List.of(
                new OntologyResponse.Triple("Incident", "OCCURRED_AT", "Building", "설명1"),
                new OntologyResponse.Triple("Incident", "OCCURRED_AT", "Building", "설명2")));
    assertThatThrownBy(() -> service.updateOntology(dup))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // 편집 검증(5-2): 속성명이 Neo4j 노드 예약 필드(key/type/name/sourceChunkIds)와 겹치면 거부한다.
  @Test
  void updateOntology_는_예약어_속성명을_거부한다() {
    UpdateOntologyRequest bad =
        new UpdateOntologyRequest(
            "d",
            1,
            List.of(
                new OntologyResponse.EntityType(
                    "Incident",
                    "a",
                    "n",
                    "exact",
                    List.of(new OntologyResponse.Property("type", "설명", "text", null)))),
            List.of());
    assertThatThrownBy(() -> service.updateOntology(bad))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, org.mockito.Mockito.never()).updateOntology(any());
  }

  // 편집 검증(5-2): 같은 엔티티 타입 내 속성명이 중복되면 거부한다(DB UNIQUE 위반이 500으로 새기 전에 차단).
  @Test
  void updateOntology_는_같은_엔티티_내_중복_속성명을_거부한다() {
    UpdateOntologyRequest dup =
        new UpdateOntologyRequest(
            "d",
            1,
            List.of(
                new OntologyResponse.EntityType(
                    "Incident",
                    "a",
                    "n",
                    "exact",
                    List.of(
                        new OntologyResponse.Property("피해액", "설명1", "number", "원"),
                        new OntologyResponse.Property("피해액", "설명2", "number", "원")))),
            List.of());
    assertThatThrownBy(() -> service.updateOntology(dup))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // 편집 검증(5-5): 리네임의 to가 최종 엔티티 타입 목록에 없으면 거부한다.
  @Test
  void updateOntology_는_리네임_to가_최종_타입에_없으면_거부한다() {
    UpdateOntologyRequest bad =
        new UpdateOntologyRequest(
            "d",
            1,
            List.of(new OntologyResponse.EntityType("RootCause", "a", "n", "exact", List.of())),
            List.of(),
            List.of(new UpdateOntologyRequest.TypeRename("Cause", "Unrelated")));
    assertThatThrownBy(() -> service.updateOntology(bad))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, org.mockito.Mockito.never()).updateOntology(any());
  }

  // 편집 검증(5-5): 리네임의 from이 최종 엔티티 타입 목록에 여전히 남아 있으면 거부한다(리네임 안 됨).
  @Test
  void updateOntology_는_리네임_from이_여전히_타입으로_남아있으면_거부한다() {
    UpdateOntologyRequest bad =
        new UpdateOntologyRequest(
            "d",
            1,
            List.of(new OntologyResponse.EntityType("Cause", "a", "n", "exact", List.of())),
            List.of(),
            List.of(new UpdateOntologyRequest.TypeRename("Cause", "Cause")));
    assertThatThrownBy(() -> service.updateOntology(bad))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // 편집 검증(5-5): from과 to가 같은(무변화) 리네임은 거부한다.
  @Test
  void updateOntology_는_리네임_from과_to가_같으면_거부한다() {
    UpdateOntologyRequest bad =
        new UpdateOntologyRequest(
            "d",
            1,
            List.of(new OntologyResponse.EntityType("RootCause", "a", "n", "exact", List.of())),
            List.of(),
            List.of(new UpdateOntologyRequest.TypeRename("RootCause", "RootCause")));
    assertThatThrownBy(() -> service.updateOntology(bad))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // happy: 유효 페이로드는 리포지토리 updateOntology 호출 후 갱신본을 재조회해 반환한다.
  @Test
  void updateOntology_는_유효하면_리포지토리를_호출하고_갱신본을_반환한다() {
    OntologyResponse updated =
        new OntologyResponse(
            "화재조사 보고서",
            2,
            List.of(new OntologyResponse.EntityType("Incident", "설명", "명명", "exact", List.of())),
            List.of());
    when(repository.updateOntology(any())).thenReturn(2);
    when(repository.findOntology()).thenReturn(updated);

    OntologyResponse res = service.updateOntology(validRequest());

    assertThat(res.schemaVersion()).isEqualTo(2);
    verify(repository).updateOntology(any());
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

  // getById는 리포지토리 findById에 위임한다.
  @Test
  void getById_는_findById에_위임한다() {
    var expected = new OntologyResponse("판매", 1, List.of(), List.of());
    when(repository.findById(2L)).thenReturn(expected);
    assertThat(service.getById(2L)).isEqualTo(expected);
  }
}
