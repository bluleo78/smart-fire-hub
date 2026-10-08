package com.smartfirehub.ontology.graphread;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * V135 백필 검증. Testcontainers DB 는 V135 적용 시점에 바인딩·매핑이 0건이라 "백필 결과 존재"를 그대로 보면 공허하다. 그래서 리포지토리를 거치지
 * 않은 바인딩·매핑 행(=V135 이전 데이터와 같은 상태)을 심고, <b>V135 파일의 백필 구간을 잘라</b> 소유자 롤로
 * 재실행한다(PipelineIncrementalSchemaTest 와 같은 방식). 운영 Flyway 도 소유자 롤(RLS 우회·GUC 없음)로 돈다.
 */
class GraphOntologySourceBackfillTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  private Long ontologyA;
  private Long ontologyB;
  private final long boundDataset = TenantRlsTestSupport.nextTenantId();
  private final long mappedDataset = TenantRlsTestSupport.nextTenantId();

  @AfterEach
  void tearDown() {
    inTenantFixture(
        () -> {
          dsl.execute(
              "delete from graph_ontology_source where dataset_id in (?, ?)",
              boundDataset,
              mappedDataset);
          dsl.execute("delete from dataset_ontology where dataset_id = ?", boundDataset);
          dsl.execute("delete from dataset_mapping where dataset_id = ?", mappedDataset);
          if (ontologyA != null)
            dsl.execute("delete from ontology where id in (?, ?)", ontologyA, ontologyB);
        });
  }

  @Test
  void 백필은_바인딩과_draft_매핑을_원행의_테넌트로_옮기고_재실행해도_멱등이다() throws Exception {
    inTenantFixture(
        () -> {
          ontologyA =
              (Long)
                  dsl.fetchValue(
                      "insert into ontology (domain, status) values (?, 'active') returning id",
                      "백필A-" + TenantRlsTestSupport.nextTenantId());
          ontologyB =
              (Long)
                  dsl.fetchValue(
                      "insert into ontology (domain, status) values (?, 'active') returning id",
                      "백필B-" + TenantRlsTestSupport.nextTenantId());
          // 리포지토리를 거치지 않는다 — V135 이전 데이터처럼 출처 행이 없는 상태를 만든다.
          dsl.execute(
              "insert into dataset_ontology (dataset_id, ontology_id) values (?, ?)",
              boundDataset,
              ontologyA);
          dsl.execute(
              "insert into dataset_mapping (dataset_id, ontology_id, spec, status) values (?, ?, '{}'::jsonb, 'draft')",
              mappedDataset,
              ontologyB);
        });
    assertThat(sources()).as("사전 상태 — 백필 전에는 출처가 없어야 이 테스트가 의미 있다").isEmpty();

    String backfill = loadBackfill();
    DSLContext owner = DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
    owner.execute(backfill);
    owner.execute(backfill); // 재실행해도 PK 충돌 없이 그대로(ON CONFLICT DO NOTHING)

    assertThat(sources())
        .containsExactlyInAnyOrder(
            List.of(DEFAULT_TEST_TENANT_ID, ontologyA, boundDataset),
            List.of(DEFAULT_TEST_TENANT_ID, ontologyB, mappedDataset));
  }

  /** 두 데이터셋의 출처 행 (tenant_id, ontology_id, dataset_id). 소유자 롤로 읽어 테넌트 오기입도 드러나게 한다. */
  private List<List<Long>> sources() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES)
        .fetch(
            "select tenant_id, ontology_id, dataset_id from graph_ontology_source where dataset_id in (?, ?)",
            boundDataset,
            mappedDataset)
        .map(r -> List.of(r.get(0, Long.class), r.get(1, Long.class), r.get(2, Long.class)));
  }

  /** V135 파일에서 BACKFILL-BEGIN ~ BACKFILL-END 사이(INSERT 한 문장)만 잘라 읽는다. */
  private String loadBackfill() throws IOException {
    try (InputStream in =
        getClass().getResourceAsStream("/db/migration/V135__graph_ontology_source.sql")) {
      assertThat(in).as("V135 파일을 클래스패스에서 찾지 못했다").isNotNull();
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      int begin = sql.indexOf("-- BACKFILL-BEGIN");
      int end = sql.indexOf("-- BACKFILL-END");
      assertThat(begin).as("백필 시작 표식").isGreaterThanOrEqualTo(0);
      assertThat(end).as("백필 끝 표식").isGreaterThan(begin);
      return sql.substring(begin + "-- BACKFILL-BEGIN".length(), end).trim();
    }
  }
}
