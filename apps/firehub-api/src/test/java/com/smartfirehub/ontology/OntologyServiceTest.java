package com.smartfirehub.ontology;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
  @Test
  void getGraph_는_노드와_엣지의_camelCase_필드를_정확히_역직렬화한다() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"nodes\":[{\"key\":\"n1\",\"type\":\"Incident\",\"name\":\"화재\",\"sourceChunkCount\":3}],"
                    + "\"edges\":[{\"subjectKey\":\"n1\",\"type\":\"CAUSED_BY\",\"objectKey\":\"n2\"}]}"));
    GraphResponse res = service.getGraph();
    assertThat(res.nodes().get(0).sourceChunkCount()).isEqualTo(3);
    assertThat(res.nodes().get(0).key()).isEqualTo("n1");
    assertThat(res.nodes().get(0).type()).isEqualTo("Incident");
    assertThat(res.nodes().get(0).name()).isEqualTo("화재");
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
}
