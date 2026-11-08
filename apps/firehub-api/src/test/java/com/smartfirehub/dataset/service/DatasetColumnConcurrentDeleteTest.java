package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.support.ConcurrentCalls;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.TestUsers;
import java.util.List;
import java.util.Objects;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * WD-616 — 같은 데이터셋 필드를 동시에 여러 번 지울 때 실제로 지운 1건만 성공하고, 진 쪽은 순차 두 번째 삭제와 같은 응답(400 「필드를 찾을 수 없습니다」),
 * 「필드 삭제」 감사는 1건인지 본다(WD-584 후속).
 *
 * <p>필드 삭제({@code DatasetService#deleteColumn})는 데이터셋을 잠금 없이 한 번 조회(findById)한 뒤 {@code
 * MappingReferenceService#lockForColumnChange} 로 잠금을 잡는다 — 이 메서드가 데이터셋 행 잠금({@code
 * DatasetRepository#lockForColumnChange}, FOR NO KEY UPDATE, WD-186·WD-531)에 위임해 같은 데이터셋의 필드 변경을 줄
 * 세운다. 필드 행은 그 잠금 <b>뒤에</b> 읽으므로, 진 쪽은 먼저 지운 쪽 커밋을 기다린 뒤 필드 행이 없어 순차 두 번째 삭제와 같은 400 으로 끝난다 — QA 가
 * 본 「204×1·400×5」가 이 결과이며 순차 두 번째 삭제와 일치한다. 이 계약을 고정한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 각 요청이 실제처럼 자기 트랜잭션으로 커밋해야 서로를 기다린다.
 */
class DatasetColumnConcurrentDeleteTest extends IntegrationTestBase {

  @Autowired private DatasetService datasetService;
  @Autowired private DatasetColumnRepository columnRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  private Long userId;
  private Long datasetId;

  @BeforeEach
  void setUp() {
    // 트랜잭션 롤백이 없어 정리 전에 실패하면 사용자가 남을 수 있다 — 이름이 겹치지 않게 시각을 붙인다(DatasetColumnConcurrentDdlTest 와 같은
    // 방식).
    String suffix = Long.toString(System.nanoTime());
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd616_" + suffix,
                "wd616_" + suffix + "@example.com",
                "Password123",
                "WD616",
                DEFAULT_TEST_TENANT_ID)
            .id();
    DatasetDetailResponse ds =
        inTenantFixture(
            () ->
                datasetService.createDataset(
                    new CreateDatasetRequest(
                        "WD616 col " + suffix,
                        "wd616_col_" + suffix,
                        null,
                        null,
                        "TABLE",
                        "SOURCE",
                        List.of(
                            new DatasetColumnRequest("a", "A", "TEXT", null, true, false, null),
                            new DatasetColumnRequest("b", "B", "TEXT", null, true, false, null),
                            new DatasetColumnRequest("c", "C", "TEXT", null, true, false, null)),
                        null),
                    userId));
    datasetId = ds.id();
  }

  @AfterEach
  void tearDown() {
    if (datasetId != null) {
      inTenantFixture(() -> datasetService.deleteDataset(datasetId));
    }
    // 비트랜잭션 테스트라 setUp 이 만든 사용자·역할·멤버십·감사 행이 남는다 — 데이터셋(created_by FK)을 지운 뒤 FK 순서대로 정리한다.
    if (userId != null) {
      TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID);
    }
  }

  /** 이름이 {@code name} 인 필드의 id. */
  private Long columnId(String name) {
    return inTenantFixture(
        () ->
            columnRepository.findByDatasetId(datasetId).stream()
                .filter(c -> c.columnName().equals(name))
                .findFirst()
                .orElseThrow()
                .id());
  }

  private int fieldDeleteAudits() {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE action_type = 'DELETE' AND resource ="
                        + " 'dataset' AND resource_id = ? AND metadata->>'change' = 'FIELD_DELETE'",
                    String.valueOf(datasetId))
                .get(0, Integer.class));
  }

  private int columnCount() {
    return inTenantFixture(() -> columnRepository.findByDatasetId(datasetId).size());
  }

  @Test
  void 같은_필드를_6건_동시에_지우면_1건만_성공하고_나머지는_순차_두_번째와_같은_400이다() throws Exception {
    Long col = columnId("c");

    List<Throwable> results =
        ConcurrentCalls.run(
            6, DEFAULT_TEST_TENANT_ID, () -> datasetService.deleteColumn(datasetId, col, userId));

    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(results.stream().filter(Objects::nonNull).toList())
        .hasSize(5)
        .allMatch(IllegalArgumentException.class::isInstance)
        .allMatch(e -> ("필드를 찾을 수 없습니다: " + col).equals(e.getMessage()));
    assertThat(fieldDeleteAudits()).isEqualTo(1);
    assertThat(columnCount()).isEqualTo(2);
  }

  @Test
  void 지운_트랜잭션이_커밋되기_전_같은_삭제는_기다렸다가_400이고_감사는_1건이다() throws Exception {
    Long col = columnId("c");

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> datasetService.deleteColumn(datasetId, col, userId),
            () -> {
              datasetService.deleteColumn(datasetId, col, userId);
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("필드를 찾을 수 없습니다: " + col);
    assertThat(fieldDeleteAudits()).isEqualTo(1);
  }

  @Test
  void 순차로_두_번_지우면_두_번째는_같은_400이다() {
    Long col = columnId("c");

    datasetService.deleteColumn(datasetId, col, userId);

    assertThatThrownBy(() -> datasetService.deleteColumn(datasetId, col, userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("필드를 찾을 수 없습니다: " + col);
    assertThat(fieldDeleteAudits()).isEqualTo(1);
  }
}
