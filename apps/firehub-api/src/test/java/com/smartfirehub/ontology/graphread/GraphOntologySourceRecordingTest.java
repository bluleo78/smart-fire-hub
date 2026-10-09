package com.smartfirehub.ontology.graphread;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.mapping.repository.MappingRepository;
import com.smartfirehub.ontology.binding.DatasetOntologyRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WD-28 출처 기록 — 연결(bind)·재연결·매핑 저장(upsert) 시점에 (온톨로지, 데이터셋) 행이 남는지 검증한다.
 *
 * <p>리포지토리 호출은 픽스처 블록(inTenantFixture) <b>밖</b>에서 한다 — 리포지토리 자신의 @Transactional 이 GUC 를 세우는지가 이
 * 테스트가 증명하는 배선이다. 데이터셋 id 는 실제 행이 필요 없다(dataset_id 에 FK 없음, audit 패턴).
 */
class GraphOntologySourceRecordingTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private DatasetOntologyRepository bindingRepository;
  @Autowired private MappingRepository mappingRepository;

  private long ontologyA;
  private long ontologyB;
  private long datasetId;

  @BeforeEach
  void setUp() {
    ontologyA = insertOntology("출처기록A-");
    ontologyB = insertOntology("출처기록B-");
    datasetId = TenantRlsTestSupport.nextTenantId(); // 공유 DB 에서 겹치지 않는 임의 id
  }

  @AfterEach
  void tearDown() {
    inTenantFixture(
        () -> {
          dsl.execute("delete from graph_ontology_source where dataset_id = ?", datasetId);
          dsl.execute("delete from dataset_mapping where dataset_id = ?", datasetId);
          dsl.execute("delete from dataset_ontology where dataset_id = ?", datasetId);
          dsl.execute("delete from ontology where id in (?, ?)", ontologyA, ontologyB);
        });
  }

  private long insertOntology(String prefix) {
    return inTenantFixture(
        () ->
            (Long)
                dsl.fetchValue(
                    "insert into ontology (domain, status) values (?, 'active') returning id",
                    prefix + TenantRlsTestSupport.nextTenantId()));
  }

  /** 이 데이터셋의 출처 행 온톨로지 id 목록(정렬). */
  private List<Long> sourceOntologies() {
    return inTenantFixture(
        () ->
            dsl.fetch(
                    "select ontology_id from graph_ontology_source where dataset_id = ? order by ontology_id",
                    datasetId)
                .getValues(0, Long.class));
  }

  @Test
  void 연결하면_출처_행이_생기고_tenant_id_는_GUC_기본값이다() {
    bindingRepository.bind(datasetId, ontologyA, null);

    assertThat(sourceOntologies()).containsExactly(ontologyA);
    Long tenant =
        inTenantFixture(
            () ->
                (Long)
                    dsl.fetchValue(
                        "select tenant_id from graph_ontology_source where dataset_id = ?",
                        datasetId));
    assertThat(tenant).isEqualTo(DEFAULT_TEST_TENANT_ID);
  }

  @Test
  void 다른_온톨로지로_재연결해도_옛_온톨로지_행이_남는다() {
    bindingRepository.bind(datasetId, ontologyA, null);
    bindingRepository.bind(datasetId, ontologyB, null);

    // 바인딩은 B 로 덮였지만 A 그래프에 쓴 잔존 데이터가 있을 수 있으므로 A 출처도 남아야 한다(스펙 §3).
    assertThat(bindingRepository.findOntologyIdByDataset(datasetId)).contains(ontologyB);
    assertThat(sourceOntologies())
        .containsExactly(Math.min(ontologyA, ontologyB), Math.max(ontologyA, ontologyB));
  }

  @Test
  void 같은_연결을_반복해도_행은_하나다() {
    bindingRepository.bind(datasetId, ontologyA, null);
    bindingRepository.bind(datasetId, ontologyA, null);

    assertThat(sourceOntologies()).containsExactly(ontologyA);
  }

  @Test
  void 매핑_저장도_출처_행을_남긴다() {
    mappingRepository.upsert(
        datasetId, ontologyA, "{\"entities\":[],\"relations\":[]}", "draft", null);

    assertThat(sourceOntologies()).containsExactly(ontologyA);
  }
}
