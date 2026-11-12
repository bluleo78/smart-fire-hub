package com.smartfirehub.mapping.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.mapping.repository.MappingRepository.LiveMappingRow;
import com.smartfirehub.mapping.repository.MappingRepository.OntologyMappingRow;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.user.repository.AccessFixtures.DatasetMappingStored;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MappingRepository 계약 테스트 — 공개 메서드 8 개 전부를 구현마다 하위 클래스로 실행한다(WD-485·WD-708). jOOQ·MyBatis 는 PG,
 * TiberoMyBatis 는 Tibero 실서버(두 계정 RLS)다.
 *
 * <p>시험마다 새 테넌트 둘(A·B)과 온톨로지를 만들고 리포지토리 호출은 테넌트 범위(inA/inB)로 감싼다. dataset_mapping 은 데이터셋 FK 가 없어 가짜
 * 데이터셋 id 를 쓰고, 사용처 조회(findLiveByOntology)만 실제 데이터셋을 만든다. 저장 상태는 소유자 시점 픽스처로 읽는다. 같은 데이터셋을 두 트랜잭션이
 * 동시에 저장하는 경합(차례로 처리), 다른 테넌트 차단, 안 보이는 등급 데이터셋의 이름 가림을 함께 본다.
 */
abstract class MappingRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract MappingRepository repository();

  private static final String EMPTY_SPEC = "{\"entities\":[],\"relations\":[]}";

  private long ontologyA;
  private long ontologyA2;
  private long ontologyB;
  private long user;

  @BeforeEach
  void setUpOntologies() {
    String s = UUID.randomUUID().toString().substring(0, 8);
    ontologyA = fx.createOntology(tenantA, "mp-a-" + s);
    ontologyA2 = fx.createOntology(tenantA, "mp-a2-" + s);
    ontologyB = fx.createOntology(tenantB, "mp-b-" + s);
    user = fx.createUser(prefix + "-u", null);
  }

  private void upsertA(long datasetId, long ontologyId, String spec, String status, Long by) {
    runA(() -> repository().upsert(datasetId, ontologyId, spec, status, by));
  }

  private static String compact(String json) {
    return json.replaceAll("\\s", "");
  }

  private DatasetVisibility rank(int rank) {
    return new DatasetVisibility(
        new Clearance(user, tenantA, rank, Set.of(), false, Set.of()), null);
  }

  @Test
  @DisplayName("매핑을 저장하면 조회된다 — 수정자·수정 시각(DB 시계)이 함께 남고 tenant_id 는 컨텍스트가 채운다")
  void 매핑_저장후_조회된다() {
    long datasetId = 997001L;
    String spec =
        "{\"entities\":[{\"entityType\":\"Incident\",\"nameColumn\":\"id\",\"properties\":[]}],\"relations\":[]}";
    upsertA(datasetId, ontologyA, spec, "draft", user);

    Optional<StoredMapping> found = inA(() -> repository().findByDataset(datasetId));
    assertThat(found).isPresent();
    assertThat(found.get().ontologyId()).isEqualTo(ontologyA);
    assertThat(found.get().status()).isEqualTo("draft");
    // Postgres jsonb 는 콜론/콤마 뒤 공백을 정규화해 저장하므로 공백 제거 후 비교.
    assertThat(compact(found.get().specJson())).contains("\"entityType\":\"Incident\"");

    List<DatasetMappingStored> rows = fx.datasetMappingRows(tenantA, datasetId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).updatedBy()).isEqualTo(user);
    assertThat(Duration.between(rows.get(0).updatedAt(), fx.dbNow()).abs())
        .isLessThan(Duration.ofSeconds(60));
    // WD-28: 매핑 저장 시점에 지식그래프 출처가 남는다.
    assertThat(fx.countGraphSources(tenantA, ontologyA, datasetId)).isEqualTo(1);
  }

  @Test
  @DisplayName("수정자가 null 이어도 저장된다")
  void upsert_nullUpdatedBy() {
    long datasetId = 997010L;
    upsertA(datasetId, ontologyA, EMPTY_SPEC, "draft", null);

    assertThat(fx.datasetMappingRows(tenantA, datasetId))
        .singleElement()
        .extracting(DatasetMappingStored::updatedBy)
        .isNull();
  }

  @Test
  @DisplayName("재저장은 기존 행을 덮어쓴다 — 한 행만 남고 온톨로지·spec·상태·수정자·수정 시각이 새 값이다")
  void 재저장은_기존행을_덮어쓴다() {
    long datasetId = 997002L;
    upsertA(datasetId, ontologyA, EMPTY_SPEC, "draft", user);
    Instant old = Instant.now().minus(Duration.ofDays(2));
    fx.setDatasetMappingUpdatedAt(tenantA, datasetId, old);

    upsertA(
        datasetId,
        ontologyA2,
        "{\"entities\":[{\"entityType\":\"X\"}],\"relations\":[]}",
        "active",
        null);

    List<DatasetMappingStored> rows = fx.datasetMappingRows(tenantA, datasetId);
    assertThat(rows).hasSize(1); // UNIQUE(tenant_id, dataset_id)
    assertThat(rows.get(0).ontologyId()).isEqualTo(ontologyA2);
    assertThat(rows.get(0).status()).isEqualTo("active");
    assertThat(rows.get(0).updatedBy()).isNull();
    assertThat(rows.get(0).updatedAt()).isAfter(old.plus(Duration.ofDays(1)));
    StoredMapping stored = inA(() -> repository().findByDataset(datasetId)).orElseThrow();
    assertThat(compact(stored.specJson())).contains("\"entityType\":\"X\"");
    assertThat(stored.ontologyId()).isEqualTo(ontologyA2);
    // 두 온톨로지 모두 출처가 남는다(옛 출처는 지우지 않는다).
    assertThat(fx.countGraphSources(tenantA, ontologyA, datasetId)).isEqualTo(1);
    assertThat(fx.countGraphSources(tenantA, ontologyA2, datasetId)).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 데이터셋 id 를 두 테넌트가 각자 저장해도 서로 덮지 않고 서로 보이지 않는다")
  void upsert_sameDatasetIdTwoTenants() {
    long datasetId = 997020L;
    upsertA(datasetId, ontologyA, EMPTY_SPEC, "draft", user);
    inB(
        () -> {
          repository()
              .upsert(
                  datasetId,
                  ontologyB,
                  "{\"entities\":[{\"entityType\":\"B\"}],\"relations\":[]}",
                  "active",
                  user);
          return null;
        });

    assertThat(fx.datasetMappingRows(tenantA, datasetId))
        .singleElement()
        .extracting(DatasetMappingStored::ontologyId)
        .isEqualTo(ontologyA);
    assertThat(fx.datasetMappingRows(tenantB, datasetId))
        .singleElement()
        .extracting(DatasetMappingStored::ontologyId)
        .isEqualTo(ontologyB);
    assertThat(inA(() -> repository().findByDataset(datasetId)).orElseThrow().status())
        .isEqualTo("draft");
    assertThat(inB(() -> repository().findByDataset(datasetId)).orElseThrow().status())
        .isEqualTo("active");
    // 한 테넌트의 삭제·상태 변경이 다른 테넌트를 건드리지 않는다.
    assertThat(inA(() -> repository().deleteByDatasetExceptOntology(datasetId, ontologyA2)))
        .isTrue();
    assertThat(fx.datasetMappingRows(tenantA, datasetId)).isEmpty();
    assertThat(fx.datasetMappingRows(tenantB, datasetId)).hasSize(1);
  }

  /**
   * 같은 데이터셋을 두 트랜잭션이 동시에 처음 저장하면 차례로 처리된다 — 첫 트랜잭션이 커밋 전에 멈춘 사이 두 번째는 유일 색인에서 기다렸다가, 첫 쪽이 커밋하면 오류
   * 없이 덮어쓴다(Tibero 는 중복 키를 한 번 다시 시도). 최종 한 행, 나중에 끝난 쪽의 값이 남는다.
   */
  @Test
  @DisplayName("동시에 같은 데이터셋을 처음 저장하는 두 트랜잭션은 차례로 처리되어 한 행만 남는다")
  void concurrentFirstUpserts_areSerialized() throws Exception {
    long datasetId = 997030L;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch firstDone = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      Future<?> first =
          pool.submit(
              () ->
                  inA(
                      () -> {
                        repository().upsert(datasetId, ontologyA, EMPTY_SPEC, "draft", user);
                        firstDone.countDown();
                        awaitQuietly(release);
                        return null;
                      }));
      assertThat(firstDone.await(30, TimeUnit.SECONDS)).isTrue();

      Future<?> second =
          pool.submit(
              () ->
                  inA(
                      () -> {
                        repository().upsert(datasetId, ontologyA2, EMPTY_SPEC, "active", user);
                        return null;
                      }));
      assertThatThrownBy(() -> second.get(1500, TimeUnit.MILLISECONDS))
          .as("두 번째 저장은 첫 저장이 끝날 때까지 기다린다")
          .isInstanceOf(TimeoutException.class);

      release.countDown();
      first.get(30, TimeUnit.SECONDS);
      second.get(30, TimeUnit.SECONDS);

      List<DatasetMappingStored> rows = fx.datasetMappingRows(tenantA, datasetId);
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).ontologyId()).isEqualTo(ontologyA2);
      assertThat(rows.get(0).status()).isEqualTo("active");
    } finally {
      release.countDown();
      pool.shutdown();
      pool.awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  @DisplayName("updateStatus 는 상태·수정자·수정 시각을 바꾸고 spec 은 그대로 둔다")
  void updateStatus는_상태를_바꾼다() {
    long datasetId = 997003L;
    upsertA(datasetId, ontologyA, EMPTY_SPEC, "draft", user);
    Instant old = Instant.now().minus(Duration.ofDays(2));
    fx.setDatasetMappingUpdatedAt(tenantA, datasetId, old);
    long other = fx.createUser(prefix + "-u2", null);

    runA(() -> repository().updateStatus(datasetId, "active", other));

    assertThat(inA(() -> repository().findByDataset(datasetId)).orElseThrow().status())
        .isEqualTo("active");
    DatasetMappingStored row = fx.datasetMappingRows(tenantA, datasetId).get(0);
    assertThat(row.updatedBy()).isEqualTo(other);
    assertThat(row.updatedAt()).isAfter(old.plus(Duration.ofDays(1)));
    assertThat(compact(inA(() -> repository().findByDataset(datasetId)).orElseThrow().specJson()))
        .isEqualTo(EMPTY_SPEC);
  }

  @Test
  @DisplayName("저장하지 않은 데이터셋은 empty 이고 다른 테넌트의 매핑은 보이지 않는다")
  void 미저장_데이터셋은_empty() {
    assertThat(inA(() -> repository().findByDataset(999998L))).isEmpty();
    assertThat(inA(() -> repository().findByDatasetForUpdate(999998L))).isEmpty();

    upsertA(997040L, ontologyA, EMPTY_SPEC, "draft", user);
    assertThat(inB(() -> repository().findByDataset(997040L))).isEmpty();
    assertThat(inB(() -> repository().findByDatasetForUpdate(997040L))).isEmpty();
  }

  @Test
  @DisplayName("findByDatasetForUpdate 는 findByDataset 과 같은 값을 행 잠금과 함께 돌려준다")
  void findByDatasetForUpdate_returnsStored() {
    long datasetId = 997050L;
    upsertA(
        datasetId,
        ontologyA,
        "{\"entities\":[{\"entityType\":\"L\"}],\"relations\":[]}",
        "active",
        user);

    StoredMapping locked = inA(() -> repository().findByDatasetForUpdate(datasetId)).orElseThrow();

    assertThat(locked.ontologyId()).isEqualTo(ontologyA);
    assertThat(locked.status()).isEqualTo("active");
    assertThat(compact(locked.specJson())).contains("\"entityType\":\"L\"");
    assertThat(locked).isEqualTo(inA(() -> repository().findByDataset(datasetId)).orElseThrow());
  }

  @Test
  @DisplayName("deleteByDatasetExceptOntology 는 다른 온톨로지 기준 매핑만 지우고 지웠으면 true")
  void deleteByDatasetExceptOntology_deletesOnlyOtherOntology() {
    long datasetId = 997060L;
    upsertA(datasetId, ontologyA, EMPTY_SPEC, "draft", user);

    // 같은 온톨로지로 다시 연결하면 맞는 매핑을 지우지 않는다.
    assertThat(inA(() -> repository().deleteByDatasetExceptOntology(datasetId, ontologyA)))
        .isFalse();
    assertThat(inA(() -> repository().findByDataset(datasetId))).isPresent();

    // 다른 온톨로지로 연결하면 정리된다.
    assertThat(inA(() -> repository().deleteByDatasetExceptOntology(datasetId, ontologyA2)))
        .isTrue();
    assertThat(inA(() -> repository().findByDataset(datasetId))).isEmpty();
    // 지울 게 없으면 false.
    assertThat(inA(() -> repository().deleteByDatasetExceptOntology(datasetId, ontologyA2)))
        .isFalse();
  }

  @Test
  @DisplayName("findByOntologyForUpdate 는 그 온톨로지의 매핑을 데이터셋 id 순으로 돌려준다")
  void findByOntologyForUpdate_ordersByDatasetId() {
    upsertA(
        997083L,
        ontologyA,
        "{\"entities\":[{\"entityType\":\"C\"}],\"relations\":[]}",
        "draft",
        user);
    upsertA(
        997081L,
        ontologyA,
        "{\"entities\":[{\"entityType\":\"A\"}],\"relations\":[]}",
        "active",
        user);
    upsertA(997082L, ontologyA, EMPTY_SPEC, "draft", user);
    upsertA(997084L, ontologyA2, EMPTY_SPEC, "draft", user); // 다른 온톨로지
    inB(
        () -> {
          repository().upsert(997085L, ontologyB, EMPTY_SPEC, "draft", user); // 다른 테넌트
          return null;
        });

    List<OntologyMappingRow> rows = inA(() -> repository().findByOntologyForUpdate(ontologyA));

    assertThat(rows)
        .extracting(OntologyMappingRow::datasetId)
        .containsExactly(997081L, 997082L, 997083L);
    assertThat(compact(rows.get(0).specJson())).contains("\"entityType\":\"A\"");
    assertThat(compact(rows.get(2).specJson())).contains("\"entityType\":\"C\"");
    assertThat(inA(() -> repository().findByOntologyForUpdate(ontologyB))).isEmpty();
    assertThat(inA(() -> repository().findByOntologyForUpdate(999_999_999L))).isEmpty();
  }

  @Test
  @DisplayName("findLiveByOntology 는 데이터셋이 남은 매핑만 돌려주고, 안 보이는 등급 데이터셋은 이름만 null 이다")
  void findLiveByOntology_masksInvisibleNames() {
    long secretLevel = fx.createSecurityLevel(tenantA, 5, prefix + "-secret");
    long open = fx.createDataset(tenantA, prefix + "-open");
    long secret = fx.createDataset(tenantA, prefix + "-secret");
    fx.setDatasetField(secret, "security_level_id", secretLevel);
    long gone = fx.createDataset(tenantA, prefix + "-gone");
    long otherOntology = fx.createDataset(tenantA, prefix + "-other");
    upsertA(
        open, ontologyA, "{\"entities\":[{\"entityType\":\"O\"}],\"relations\":[]}", "draft", user);
    upsertA(secret, ontologyA, EMPTY_SPEC, "draft", user);
    upsertA(gone, ontologyA, EMPTY_SPEC, "draft", user);
    upsertA(otherOntology, ontologyA2, EMPTY_SPEC, "draft", user);
    upsertA(997099L, ontologyA, EMPTY_SPEC, "draft", user); // 고아 매핑(데이터셋 없음)
    fx.deleteDataset(gone); // 데이터셋을 지워도 매핑 행은 남지만 사용처에서는 빠진다

    List<LiveMappingRow> low = inA(() -> repository().findLiveByOntology(ontologyA, rank(2)));
    assertThat(low).extracting(LiveMappingRow::datasetId).containsExactlyInAnyOrder(open, secret);
    assertThat(low)
        .isSortedAccordingTo(java.util.Comparator.comparingLong(LiveMappingRow::datasetId));
    assertThat(
            low.stream().filter(r -> r.datasetId() == open).findFirst().orElseThrow().datasetName())
        .isEqualTo(prefix + "-open");
    assertThat(
            low.stream()
                .filter(r -> r.datasetId() == secret)
                .findFirst()
                .orElseThrow()
                .datasetName())
        .as("안 보이는 등급의 이름은 가린다(사용 중이라는 사실은 숨기지 않는다)")
        .isNull();
    assertThat(
            compact(
                low.stream()
                    .filter(r -> r.datasetId() == open)
                    .findFirst()
                    .orElseThrow()
                    .specJson()))
        .contains("\"entityType\":\"O\"");

    List<LiveMappingRow> high = inA(() -> repository().findLiveByOntology(ontologyA, rank(5)));
    assertThat(high)
        .extracting(LiveMappingRow::datasetName)
        .containsExactlyInAnyOrder(prefix + "-open", prefix + "-secret");

    List<LiveMappingRow> all =
        inA(() -> repository().findLiveByOntology(ontologyA, DatasetVisibility.UNRESTRICTED));
    assertThat(all)
        .extracting(LiveMappingRow::datasetName)
        .containsExactlyInAnyOrder(prefix + "-open", prefix + "-secret");

    // 등급 없는 자격은 어떤 이름도 못 보지만 행은 그대로다.
    List<LiveMappingRow> none =
        inA(
            () ->
                repository()
                    .findLiveByOntology(
                        ontologyA, new DatasetVisibility(Clearance.none(user, tenantA), null)));
    assertThat(none).extracting(LiveMappingRow::datasetId).containsExactlyInAnyOrder(open, secret);
    assertThat(none).extracting(LiveMappingRow::datasetName).containsOnlyNulls();

    // 다른 테넌트에서는 보이지 않는다.
    assertThat(
            inB(() -> repository().findLiveByOntology(ontologyA, DatasetVisibility.UNRESTRICTED)))
        .isEmpty();
  }

  @Test
  @DisplayName("updateSpec 은 spec 과 수정 시각만 바꾸고 상태·수정자·출처는 그대로 둔다")
  void updateSpec_replacesSpecOnly() {
    long datasetId = 997070L;
    upsertA(datasetId, ontologyA, EMPTY_SPEC, "active", user);
    Instant old = Instant.now().minus(Duration.ofDays(2));
    fx.setDatasetMappingUpdatedAt(tenantA, datasetId, old);

    runA(
        () ->
            repository()
                .updateSpec(datasetId, "{\"entities\":[{\"entityType\":\"R\"}],\"relations\":[]}"));

    StoredMapping stored = inA(() -> repository().findByDataset(datasetId)).orElseThrow();
    assertThat(compact(stored.specJson())).contains("\"entityType\":\"R\"");
    assertThat(stored.status()).as("이름만 맞추는 갱신이 active 를 draft 로 떨어뜨리면 안 된다").isEqualTo("active");
    assertThat(stored.ontologyId()).isEqualTo(ontologyA);
    DatasetMappingStored row = fx.datasetMappingRows(tenantA, datasetId).get(0);
    assertThat(row.updatedBy()).isEqualTo(user);
    assertThat(row.updatedAt()).isAfter(old.plus(Duration.ofDays(1)));
    // 없는 데이터셋·다른 테넌트의 갱신은 아무것도 만들지 않는다.
    runA(() -> repository().updateSpec(999_990L, EMPTY_SPEC));
    inB(
        () -> {
          repository().updateSpec(datasetId, EMPTY_SPEC);
          return null;
        });
    assertThat(fx.datasetMappingRows(tenantA, 999_990L)).isEmpty();
    assertThat(compact(inA(() -> repository().findByDataset(datasetId)).orElseThrow().specJson()))
        .contains("\"entityType\":\"R\"");
  }
}
