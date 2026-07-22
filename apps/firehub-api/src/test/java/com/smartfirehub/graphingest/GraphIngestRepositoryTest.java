package com.smartfirehub.graphingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.graphingest.repository.GraphIngestRepository;
import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** GraphIngestRepository 통합 테스트 — 실제 Postgres(smartfirehub_test) 대상. */
class GraphIngestRepositoryTest extends IntegrationTestBase {

  @Autowired private GraphIngestRepository repo;

  @Test
  void save_and_findByDataset_returnsNewestFirst() {
    repo.save(9001L, 1, 10, 20, 15, 0, "SUCCESS");
    repo.save(9001L, 1, 11, 22, 16, 2, "PARTIAL");

    var rows = repo.findByDataset(9001L);

    assertThat(rows).hasSize(2);
    // 최신순(DESC) 정렬 검증
    assertThat(rows.get(0).ingestedAt()).isAfterOrEqualTo(rows.get(1).ingestedAt());
    assertThat(rows).extracting("status").contains("SUCCESS", "PARTIAL");
  }

  @Test
  void findStale_returnsDatasetsBelowCurrentVersion_latestRowOnly() {
    repo.save(9101L, 1, 1, 1, 1, 0, "SUCCESS"); // v1 적재(낡음)
    repo.save(9102L, 3, 1, 1, 1, 0, "SUCCESS"); // v3 적재(최신, 낡지 않음)

    var stale = repo.findStale(3); // 현재 온톨로지 버전 3

    assertThat(stale).extracting("datasetId").contains(9101L).doesNotContain(9102L);
  }

  @Test
  void findStale_usesOnlyLatestRowPerDataset() {
    // 9201: 과거엔 v1로 적재됐지만 이후 v3로 재적재됨 → 최신 기준으로는 stale 아님
    repo.save(9201L, 1, 1, 1, 1, 0, "SUCCESS");
    repo.save(9201L, 3, 1, 1, 1, 0, "SUCCESS");

    var stale = repo.findStale(3);

    assertThat(stale).extracting("datasetId").doesNotContain(9201L);
  }
}
