package com.smartfirehub.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * WD-583 — 같은 데이터셋에 삭제 요청이 동시에 여러 번 들어오면 모두 204 로 끝나고 감사 로그 DELETE 가 요청 수만큼 남던 문제.
 *
 * <p>예전 {@code deleteDataset} 은 잠금 없이 findById 로 존재만 확인하고 진행해, 앞 요청이 커밋되기 전에 들어온 요청도 행을 본 채 색인
 * 삭제·DROP·감사를 되풀이했다. 서비스가 진입 직후 데이터셋 행을 {@code FOR UPDATE} 로 잠그면 뒤 요청은 앞 요청 커밋을 기다렸다가 행이 없어 {@link
 * DatasetNotFoundException}(404)으로 끝나야 한다 — 성공 1, 404 나머지, 감사 1건.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 각 삭제가 실제처럼 자기 트랜잭션으로 커밋해야 서로를 기다린다. 테스트 커넥션 풀이 2개라
 * 동시에 2건씩 겹치므로, 경합이 한 번에 드러나지 않을 수 있어 여러 데이터셋으로 회차를 되풀이한다.
 */
class DatasetDeleteConcurrencyTest extends IntegrationTestBase {

  /** 한 데이터셋에 동시에 보내는 삭제 요청 수(이슈 재현과 같은 6). */
  private static final int CONCURRENT_DELETES = 6;

  /** 경합 재현 회차 — 회차마다 새 데이터셋을 만든다. */
  private static final int ROUNDS = 3;

  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;
  @Autowired @DataStoreDb private DSLContext dataDsl;
  @Autowired private PasswordEncoder encoder;

  private Long userId;

  @BeforeEach
  void setUp() {
    // 트랜잭션 롤백이 없어 테스트마다 사용자가 남는다 — 이름이 겹치지 않게 시각을 붙인다.
    String suffix = Long.toString(System.nanoTime());
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd583_" + suffix,
                "wd583_" + suffix + "@example.com",
                "Password123",
                "WD583",
                DEFAULT_TEST_TENANT_ID)
            .id();
  }

  /** 테이블형 데이터셋 하나를 만든다(색인·DROP 경로까지 타도록). */
  private DatasetDetailResponse createDataset(String tableName) {
    return inTenantFixture(
        () ->
            datasetService.createDataset(
                new CreateDatasetRequest(
                    "WD583 " + tableName,
                    tableName,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(new DatasetColumnRequest("a", "A", "TEXT", null, true, false, null)),
                    null),
                userId));
  }

  /**
   * 요청 스레드처럼 테넌트 컨텍스트와 인증(principal = Long userId — 감사 로그가 남는 조건)을 세운 뒤 삭제를 부른다. 검증 대상 호출이므로 픽스처
   * 트랜잭션({@code inTenantFixture}) 밖에서 서비스가 스스로 트랜잭션을 연다.
   */
  private void deleteAsUser(Long datasetId) {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    try {
      datasetService.deleteDataset(datasetId);
    } finally {
      SecurityContextHolder.clearContext();
      TenantContext.clear();
    }
  }

  /** 이 데이터셋의 DELETE 감사 행 수. */
  private long deleteAuditCount(Long datasetId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE resource = 'dataset'"
                        + " AND action_type = 'DELETE' AND resource_id = ?",
                    String.valueOf(datasetId))
                .get(0, Long.class));
  }

  /** data 저장소에 물리 테이블이 남아 있는지. */
  private long physicalTableCount(String tableName) {
    return dataDsl
        .selectCount()
        .from("information_schema.tables")
        .where("table_schema = 'data' AND table_name = ?", tableName)
        .fetchOne(0, Long.class);
  }

  @Test
  void 같은_데이터셋을_동시에_여러_번_삭제하면_한_번만_성공하고_나머지는_404_감사도_한_건이다() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      String tableName = "wd583_race_" + round + "_" + (System.nanoTime() % 100000);
      Long datasetId = createDataset(tableName).id();

      // 모든 요청을 장벽으로 같은 순간에 출발시키고, 결과(성공 또는 예외)를 모은다.
      CyclicBarrier start = new CyclicBarrier(CONCURRENT_DELETES);
      List<CompletableFuture<Throwable>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_DELETES; i++) {
        futures.add(
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    start.await(10, TimeUnit.SECONDS);
                    deleteAsUser(datasetId);
                    return null;
                  } catch (Throwable t) {
                    return t;
                  }
                }));
      }
      int success = 0;
      int notFound = 0;
      List<Throwable> others = new ArrayList<>();
      for (CompletableFuture<Throwable> f : futures) {
        Throwable t = f.get(120, TimeUnit.SECONDS);
        if (t == null) {
          success++;
        } else if (t instanceof DatasetNotFoundException) {
          notFound++;
        } else {
          others.add(t);
        }
      }

      assertThat(others).as("round %d: 404 외 예외", round).isEmpty();
      assertThat(success).as("round %d: 성공 수", round).isEqualTo(1);
      assertThat(notFound).as("round %d: 404 수", round).isEqualTo(CONCURRENT_DELETES - 1);
      assertThat(deleteAuditCount(datasetId)).as("round %d: 감사 DELETE 행 수", round).isEqualTo(1);
      assertThat(physicalTableCount(tableName)).as("round %d: 물리 테이블", round).isZero();
    }
  }

  @Test
  void 이미_삭제한_데이터셋을_다시_삭제하면_404_이고_감사는_늘지_않는다() {
    String tableName = "wd583_seq_" + (System.nanoTime() % 100000);
    Long datasetId = createDataset(tableName).id();

    deleteAsUser(datasetId);
    assertThatThrownBy(() -> deleteAsUser(datasetId)).isInstanceOf(DatasetNotFoundException.class);

    assertThat(deleteAuditCount(datasetId)).isEqualTo(1);
  }
}
