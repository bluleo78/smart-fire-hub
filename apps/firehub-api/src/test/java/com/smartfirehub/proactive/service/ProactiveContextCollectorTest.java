package com.smartfirehub.proactive.service;

import static com.smartfirehub.jooq.Tables.AUDIT_LOG;
import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ProactiveContextCollectorTest extends IntegrationTestBase {

  @Autowired private ProactiveContextCollector contextCollector;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private ProactiveJobExecutionRepository executionRepository;

  @Test
  void collectContext_includes_previousExecutions_when_jobId_provided() {
    var execution =
        new ProactiveJobExecutionResponse(
            1L,
            10L,
            "COMPLETED",
            LocalDateTime.now().minusDays(1),
            LocalDateTime.now().minusDays(1),
            null,
            Map.of(
                "title",
                "Test",
                "sections",
                List.of(Map.of("key", "s1", "label", "요약", "content", "테스트 내용"))),
            List.of(),
            LocalDateTime.now().minusDays(1));
    when(executionRepository.findByJobId(anyLong(), anyInt(), anyInt()))
        .thenReturn(List.of(execution));

    String context = contextCollector.collectContext(Map.of(), 10L);

    assertThat(context).contains("previousExecutions");
    assertThat(context).contains("테스트 내용");
  }

  @Test
  void collectContext_works_without_jobId() {
    String context = contextCollector.collectContext(Map.of(), null);

    assertThat(context).doesNotContain("previousExecutions");
    // 위 단언만으로는 공허하다 — 수집이 통째로 실패해 "{}" 를 돌려줘도 통과한다. 실제로 이
    // 밴드에서 테넌트 컨텍스트 유실이 예외로 번져 "{}" 가 되던 시기에도 이 테스트는 초록이었다.
    // 그래서 네 섹션이 실제로 담겼는지를 내용 단언으로 못박는다.
    assertThat(context).contains("stats", "systemHealth", "attentionItems", "activityFeed");
  }

  /**
   * 병렬 수집이 <b>조용히 엉뚱한 테넌트</b>로 도는 회귀를 잡는다 — 이 밴드가 고친 결함의 형태다.
   *
   * <p>무엇이 있었나: {@code collectContext} 는 4개 대시보드 조회를 공용 {@code ForkJoinPool} 에서
   * 돌렸고, 그 풀에는 테넌트 데코레이터가 붙지 않아 컨텍스트가 유실됐다. GUC 가 비면 {@code audit_log}
   * 의 정책({@code IS NOT DISTINCT FROM})이 <b>모든 테넌트의 tenant_id IS NULL 행</b>(로그인·회원가입
   * 감사)에 매칭돼, 그 행들이 활동 피드를 타고 proactive LLM 컨텍스트로 흘러 들어갔다.
   *
   * <p><b>왜 이런 모양의 테스트인가</b>: 기존 스위트는 <i>던지는</i> 형태만 잡는다({@code join()} 이
   * 예외를 되던지면 결과가 "{}" 가 되어 형제 테스트가 빨개진다). <b>조용한</b> 형태 — 엉뚱한 테넌트
   * 또는 0행 — 는 아무도 단언하지 않았다. 그래서 두 축을 함께 본다.
   *
   * <ul>
   *   <li><b>NULL 테넌트 미끼</b>: tenant_id 가 NULL 인 감사 행을 하나 심고, 수집 결과에 그 표식이
   *       <b>없어야</b> 한다. GUC 가 비면 audit_log 에서 보이는 행은 NULL 행뿐이고 파이프라인 실행은
   *       0행이 되므로, 이 표식은 피드 첫 페이지에 반드시 올라온다 — 즉 이 단언은 날카롭다.
   *   <li><b>내용의 양수성</b>: 기본 테넌트의 데이터셋·활동 건수가 0 보다 크다. 엉뚱한(비어 있는)
   *       테넌트로 돌면 두 값이 0 이 되고, 컨텍스트가 아예 없으면 "{}" 가 된다.
   * </ul>
   *
   * <p>테넌트 B 를 심어 "B 의 행이 안 보인다" 를 확인하지 않는 이유: GUC 가 비었을 때 B 의 행도
   * 어차피 안 보이므로(정책이 NULL 과만 매칭) 정상·결함을 구분하지 못한다. 구분력은 위 두 축에 있다.
   */
  @Test
  void collectContext_collectsDashboardContentForCurrentTenantOnly() throws Exception {
    // 이번 실행에만 유일한 표식. 이전 실행의 잔여 행이 절대 단언을 오염시키지 않게 매번 새로 만든다.
    String nullTenantMarker = String.valueOf(770_000_000_000L + System.nanoTime() % 1_000_000_000L);

    // tenant_id 가 NULL 인 행은 컨텍스트가 비었을 때만 삽입된다 — 정책 WITH CHECK 이
    // NULL-vs-GUC 를 요구하기 때문이다(V99 의 형태 (b)). 그래서 픽스처도 null 컨텍스트로 넣는다.
    inTenantFixture(null, () -> insertDatasetCreateAudit(nullTenantMarker));
    // 기본 테넌트에 데이터셋을 하나 심는다. 공유 테스트 DB 의 데이터셋 건수는 다른 테스트의 정리에
    // 따라 0 일 수 있어(실측 0건) "양수" 단언이 DB 상태에 의존하면 안 된다 — 픽스처로 보장한다.
    Long ownerUserId =
        inTenantFixture(() -> TenantRlsTestSupport.insertUser(dsl, "proactive_guard_"));
    Long datasetId = inTenantFixture(() -> insertDataset(ownerUserId));
    try {
      String context = contextCollector.collectContext(Map.of(), null);

      assertThat(context)
          .as("수집이 통째로 실패하면 아래 단언들이 공허해진다 — 네 섹션의 존재를 먼저 못박는다")
          .contains("stats", "systemHealth", "attentionItems", "activityFeed");
      assertThat(context)
          .as("NULL 테넌트 감사 행이 컨텍스트에 섞였다 — 병렬 수집에서 테넌트 GUC 가 유실됐다는 뜻")
          .doesNotContain(nullTenantMarker);

      JsonNode root = objectMapper.readTree(context);
      assertThat(root.path("stats").path("totalDatasets").asLong())
          .as("기본 테넌트의 데이터셋 건수 — 엉뚱한(빈) 테넌트로 돌면 0 이 된다")
          .isPositive();
      assertThat(root.path("activityFeed").path("totalCount").asLong())
          .as("기본 테넌트의 활동 건수 — 빈 테넌트·빈 GUC 로 돌면 0 이 된다")
          .isPositive();
    } finally {
      // 정리도 null 컨텍스트여야 한다. 테넌트 1 로 지우면 RLS 가 이 행을 보지 못해 0행 삭제로
      // 끝나고, NULL 행이 공유 테스트 DB 에 영구히 남아 뒤따르는 무컨텍스트 테스트를 오염시킨다.
      inTenantFixture(null, () -> deleteAuditByResourceId(nullTenantMarker));
      inTenantFixture(
          () -> {
            dsl.deleteFrom(DATASET).where(DATASET.ID.eq(datasetId)).execute();
            TenantRlsTestSupport.deleteUser(dsl, ownerUserId); // FK 순서상 데이터셋 뒤
          });
    }
  }

  /** 통계의 데이터셋 건수를 1 이상으로 만드는 최소 픽스처. {@code tenant_id} 는 GUC 파생 DEFAULT 에 맡긴다. */
  private Long insertDataset(Long ownerUserId) {
    long suffix = System.nanoTime();
    return dsl.insertInto(DATASET)
        .set(DATASET.NAME, "proactive_guard_ds_" + suffix)
        .set(DATASET.TABLE_NAME, "proactive_guard_ds_" + suffix)
        .set(DATASET.STORAGE_TYPE, "TABLE")
        .set(DATASET.ORIGIN_TYPE, "SOURCE")
        .set(DATASET.CREATED_BY, ownerUserId)
        .returning(DATASET.ID)
        .fetchOne()
        .getId();
  }

  /**
   * 활동 피드에 뜨는 형태의 감사 행 하나를 심는다({@code CREATE} + {@code dataset}).
   *
   * <p>{@code action_time} 을 현재 시각으로 두는 이유: 피드는 시간 내림차순 500건으로 자르고 JSON 도
   * 5만자에서 잘리므로, 과거 시각으로 심으면 표식이 어느 쪽에서든 탈락해 단언이 조용히 공허해진다.
   */
  private void insertDatasetCreateAudit(String resourceId) {
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USERNAME, "proactive-tenant-guard")
        .set(AUDIT_LOG.ACTION_TYPE, "CREATE")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, resourceId)
        .set(AUDIT_LOG.RESULT, "SUCCESS")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now())
        .execute();
  }

  private void deleteAuditByResourceId(String resourceId) {
    dsl.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.RESOURCE_ID.eq(resourceId)).execute();
  }
}
