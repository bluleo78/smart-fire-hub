package com.smartfirehub.ontology;

import static org.assertj.core.api.Assertions.*;

import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.ontology.dto.GraphResponse;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.service.OntologyProxyService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

// OntologyProxyService 단위 테스트 — MockWebServer로 ai-agent를 흉내내어
// 정상 응답이 DTO로 그대로 통과되는지, 실패 응답(502)이 예외로 전파되는지 검증한다.
// Spring 컨텍스트 없이 서비스 객체를 직접 생성하는 순수 단위 테스트.
class OntologyProxyServiceTest {
  private MockWebServer server;
  private OntologyProxyService service;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    service = new OntologyProxyService(server.url("/").toString(), "test-token");
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void getOntology_는_ai_agent_응답을_DTO로_통과시킨다() {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"domain\":\"화재조사 보고서\",\"entities\":[{\"type\":\"Incident\",\"description\":\"d\",\"naming\":\"n\",\"resolution\":\"exact\"}],\"relations\":[]}"));
    OntologyResponse res = service.getOntology();
    assertThat(res.domain()).isEqualTo("화재조사 보고서");
    assertThat(res.entities()).hasSize(1);
    assertThat(res.entities().get(0).type()).isEqualTo("Incident");
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
}
