package com.smartfirehub.apiconnection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.apiconnection.repository.ApiConnectionRepository.ApiConnectionRow;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository.HealthCheckTarget;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository.ReferencingPipeline;
import com.smartfirehub.user.repository.AccessFixtures.ApiConnectionStored;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * ApiConnectionRepository 계약 테스트 — 공개 메서드 12 개 전부를 구현마다 하위 클래스로 실행한다(WD-485·WD-708). jOOQ·MyBatis 는
 * PG, TiberoMyBatis 는 Tibero 실서버(두 계정 RLS)다.
 *
 * <p>시험마다 새 테넌트 둘(A·B)을 만들고, 리포지토리 호출은 테넌트 범위(inA/inB)로 감싼다. 저장 상태는 소유자 시점 픽스처({@code
 * apiConnectionStored})로 읽어 리포지토리를 거치지 않은 실제 행을 본다. 같은 연결을 두 호출이 동시에 바꾸는 경합(WD-189), 다른 테넌트 차단, 사용처
 * 목록의 중복 제거, 긴 암호문(VARCHAR2(65532))이 잘리지 않는지를 함께 본다.
 */
abstract class ApiConnectionRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract ApiConnectionRepository repository();

  private long user;

  @BeforeEach
  void setUpUser() {
    user = fx.createUser(prefix + "-u", null);
  }

  private long saveA(String name, String path) {
    return inA(
        () ->
            repository()
                .save(
                    prefix + "-" + name,
                    null,
                    "API_KEY",
                    "enc-" + name,
                    user,
                    "https://" + name + ".example.com",
                    path));
  }

  /** baseUrl/healthCheckPath를 포함한 save 호출 시 모든 필드가 DB에 저장되는지 확인한다. */
  @Test
  @DisplayName("save 는 baseUrl·헬스체크 경로를 포함한 모든 필드를 저장하고 생성 id 를 돌려준다")
  void create_withBaseUrl_persistsAllFields() {
    Long id =
        inA(
            () ->
                repository()
                    .save(
                        prefix + "-Test API",
                        "desc",
                        "API_KEY",
                        "encrypted-config",
                        user,
                        "https://api.example.com",
                        "/health"));

    assertThat(id).isNotNull();
    ApiConnectionStored stored = fx.apiConnectionStored(id);
    assertThat(stored).isNotNull();
    assertThat(stored.baseUrl()).isEqualTo("https://api.example.com");
    assertThat(stored.healthCheckPath()).isEqualTo("/health");
    assertThat(stored.authConfig()).isEqualTo("encrypted-config");
  }

  /** 삽입 시각은 DB 시계(저장 기준 벽시계)로 채워진다 — JVM 시간대와 무관하게 DB 시계와 같은 순간이다. */
  @Test
  @DisplayName("save 의 생성·수정 시각은 DB 시계 기준이고 점검 전에는 점검 시각이 비어 있다")
  void save_writesCreatedAndUpdatedFromDbClock() {
    long id = saveA("clock", "/h");

    ApiConnectionStored stored = fx.apiConnectionStored(id);
    LocalDateTime now = fx.dbWallClockNow();
    assertThat(Duration.between(stored.createdAt(), now).abs()).isLessThan(Duration.ofSeconds(60));
    assertThat(Duration.between(stored.updatedAt(), now).abs()).isLessThan(Duration.ofSeconds(60));
    assertThat(stored.lastCheckedAt()).isNull();
  }

  /**
   * baseUrl 이 null 이면 빈 문자열로 넣는다 — Tibero 는 빈 문자열이 NULL 이라 NOT NULL 열이 거절한다(emptyStringIsNull 훅).
   */
  @Test
  @DisplayName("baseUrl 이 null 이면 PG 는 빈 문자열로 저장하고 Tibero(빈 문자열=NULL)는 NOT NULL 열이 거절한다")
  void save_nullBaseUrl_perDatabase() {
    if (emptyStringIsNull()) {
      assertThatThrownBy(
              () ->
                  inA(
                      () ->
                          repository()
                              .save(prefix + "-nb", null, "API_KEY", "enc", user, null, null)))
          .isInstanceOf(DataAccessException.class);
    } else {
      long id =
          inA(() -> repository().save(prefix + "-nb", null, "API_KEY", "enc", user, null, null));
      assertThat(fx.apiConnectionStored(id).baseUrl()).isEmpty();
    }
  }

  /** 인증 설정은 암호문이라 자르지 않는다 — 6만 글자도 그대로 왕복한다(Tibero VARCHAR2(65532)). */
  @Test
  @DisplayName("긴 인증 설정(6만 글자)도 자르지 않고 저장·조회한다")
  void save_longAuthConfig_roundTrips() {
    String longConfig = "x".repeat(60_000);
    long id =
        inA(
            () ->
                repository()
                    .save(
                        prefix + "-long",
                        null,
                        "BEARER",
                        longConfig,
                        user,
                        "https://l.example.com",
                        null));

    assertThat(fx.apiConnectionStored(id).authConfig()).isEqualTo(longConfig);
    assertThat(inA(() -> repository().findById(id)).orElseThrow().authConfig())
        .isEqualTo(longConfig);
  }

  /** updateHealthStatus 호출 시 last_status/last_latency_ms/last_error_message가 올바르게 갱신되는지 확인한다. */
  @Test
  @DisplayName("updateHealthStatus 는 상태·지연·오류 메시지·점검 시각을 갱신한다")
  void updateHealthStatus_setsLastStatusFields() {
    long id =
        inA(
            () ->
                repository()
                    .save(
                        prefix + "-Health API",
                        null,
                        "BEARER",
                        "encrypted-config",
                        user,
                        "https://health.example.com",
                        "/ping"));

    inA(() -> repository().updateHealthStatus(id, "UP", 123L, null));

    ApiConnectionStored up = fx.apiConnectionStored(id);
    assertThat(up.lastStatus()).isEqualTo("UP");
    assertThat(up.lastLatencyMs()).isEqualTo(123L);
    assertThat(up.lastErrorMessage()).isNull();
    assertThat(up.lastCheckedAt()).isNotNull();
    // 점검 시각은 jOOQ 판처럼 자바 시각이다.
    assertThat(Duration.between(up.lastCheckedAt(), LocalDateTime.now()).abs())
        .isLessThan(Duration.ofSeconds(60));

    // 오류 상태로 갱신
    inA(() -> repository().updateHealthStatus(id, "DOWN", null, "Connection refused"));

    ApiConnectionStored down = fx.apiConnectionStored(id);
    assertThat(down.lastStatus()).isEqualTo("DOWN");
    assertThat(down.lastLatencyMs()).isNull();
    assertThat(down.lastErrorMessage()).isEqualTo("Connection refused");
  }

  /** findHealthCheckable은 health_check_path가 NOT NULL이고 비어 있지 않은 레코드만 반환해야 한다. */
  @Test
  @DisplayName("findHealthCheckable 은 헬스체크 경로가 있는 연결만 돌려준다(null·빈 문자열 제외)")
  void findHealthCheckable_returnsOnlyRecordsWithHealthCheckPath() {
    long id1 = saveA("hc1", "/health");
    long id2 = saveA("hc2", "/ping");
    long id3 = saveA("nohc", null);
    long id4 = saveA("emptyhc", "");

    List<Long> ids =
        inA(() -> repository().findHealthCheckable()).stream().map(HealthCheckTarget::id).toList();

    assertThat(ids).contains(id1, id2);
    assertThat(ids).doesNotContain(id3, id4);
  }

  /** findHealthCheckable 은 대상의 id·이름을 돌려준다(WD-485 — 주기 점검이 읽는 값). */
  @Test
  @DisplayName("findHealthCheckable 은 대상의 id·이름을 돌려준다")
  void findHealthCheckable_returnsIdAndName() {
    long id = saveA("hcname", "/h");

    assertThat(inA(() -> repository().findHealthCheckable()))
        .contains(new HealthCheckTarget(id, prefix + "-hcname"));
  }

  /** 다른 테넌트의 연결은 점검 대상에 섞이지 않는다. */
  @Test
  @DisplayName("다른 테넌트의 연결은 findHealthCheckable·findAll·findById 에 보이지 않는다")
  void otherTenantRowsAreInvisible() {
    long a = saveA("iso", "/h");
    long b =
        inB(
            () ->
                repository()
                    .save(
                        prefix + "-iso-b",
                        null,
                        "API_KEY",
                        "enc",
                        user,
                        "https://b.example.com",
                        "/h"));

    assertThat(inA(() -> repository().findHealthCheckable()))
        .extracting(HealthCheckTarget::id)
        .contains(a)
        .doesNotContain(b);
    assertThat(inA(() -> repository().findAll()))
        .extracting(ApiConnectionRow::id)
        .doesNotContain(b);
    assertThat(inA(() -> repository().findById(b))).isEmpty();
    assertThat(inA(() -> repository().existsById(b))).isFalse();
    assertThat(inA(() -> repository().existsByName(prefix + "-iso-b"))).isFalse();
    assertThat(inB(() -> repository().existsById(b))).isTrue();
    // 다른 테넌트의 행은 갱신·삭제도 못 한다.
    assertThat(inA(() -> repository().deleteById(b))).isZero();
    assertThat(inA(() -> repository().updateHealthStatus(b, "UP", 1L, null))).isNull();
    assertThat(fx.apiConnectionStored(b)).isNotNull();
    assertThat(fx.apiConnectionStored(b).lastStatus()).isNull();
  }

  /**
   * findById·findAll·findByCreatedBy 는 서비스가 읽는 열 전부를 도메인 행(ApiConnectionRow)으로 옮긴다(WD-485) — 저장한 값과
   * 헬스체크 결과까지 한 칸도 어긋나지 않아야 한다.
   */
  @Test
  @DisplayName("findById·findAll·findByCreatedBy 는 모든 열을 도메인 행으로 옮긴다")
  void findById_findAll_findByCreatedBy_mapAllColumnsToRow() {
    Long id =
        inA(
            () ->
                repository()
                    .save(
                        prefix + "-Row API",
                        "row desc",
                        "BEARER",
                        "enc-row",
                        user,
                        "https://r.example.com",
                        "/hc"));
    inA(() -> repository().updateHealthStatus(id, "DOWN", 42L, "boom"));

    ApiConnectionRow row = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(row.id()).isEqualTo(id);
    assertThat(row.name()).isEqualTo(prefix + "-Row API");
    assertThat(row.description()).isEqualTo("row desc");
    assertThat(row.authType()).isEqualTo("BEARER");
    assertThat(row.authConfig()).isEqualTo("enc-row");
    assertThat(row.createdBy()).isEqualTo(user);
    assertThat(row.createdAt()).isNotNull();
    assertThat(row.updatedAt()).isNotNull();
    assertThat(row.baseUrl()).isEqualTo("https://r.example.com");
    assertThat(row.healthCheckPath()).isEqualTo("/hc");
    assertThat(row.lastStatus()).isEqualTo("DOWN");
    assertThat(row.lastCheckedAt()).isNotNull();
    assertThat(row.lastLatencyMs()).isEqualTo(42L);
    assertThat(row.lastErrorMessage()).isEqualTo("boom");

    assertThat(inA(() -> repository().findAll())).contains(row);
    assertThat(inA(() -> repository().findByCreatedBy(user))).containsExactly(row);
    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
  }

  /** findAll 은 최신 생성순이다 — 같은 트랜잭션에서 넣어 생성 시각이 같아도 id 내림차순으로 가른다. */
  @Test
  @DisplayName("findAll 은 최신 생성순(같은 시각이면 id 내림차순)이다")
  void findAll_ordersNewestFirst() {
    List<Long> ids =
        inA(
            () -> {
              long x =
                  repository().save(prefix + "-o1", null, "API_KEY", "e", user, "https://1", null);
              long y =
                  repository().save(prefix + "-o2", null, "API_KEY", "e", user, "https://2", null);
              long z =
                  repository().save(prefix + "-o3", null, "API_KEY", "e", user, "https://3", null);
              return repository().findAll().stream()
                  .map(ApiConnectionRow::id)
                  .filter(i -> i == x || i == y || i == z)
                  .toList();
            });

    assertThat(ids).isSortedAccordingTo(java.util.Comparator.reverseOrder()).hasSize(3);
  }

  /** 갱신 직전 상태를 돌려준다 — 첫 점검은 null, 이후엔 직전 값. 없는 연결이면 null(알릴 전환 없음)(WD-189). */
  @Test
  @DisplayName("updateHealthStatus 는 직전 상태를 돌려준다 — 첫 점검·없는 연결은 null")
  void updateHealthStatus_returnsPreviousStatus() {
    long id = saveA("prev", "/ping");

    assertThat(inA(() -> repository().updateHealthStatus(id, "UP", 10L, null))).as("첫 점검").isNull();
    assertThat(inA(() -> repository().updateHealthStatus(id, "DOWN", null, "x"))).isEqualTo("UP");
    assertThat(inA(() -> repository().updateHealthStatus(id, "DOWN", null, "x"))).isEqualTo("DOWN");
    assertThat(inA(() -> repository().updateHealthStatus(Long.MAX_VALUE, "UP", 1L, null))).isNull();
  }

  /**
   * 같은 연결을 두 호출(주기 점검·「테스트」)이 동시에 UP→DOWN 으로 바꾸면 전환을 본 호출은 하나뿐이어야 한다(WD-189). 첫 호출이 커밋 전에 멈춘 사이 두
   * 번째 호출은 행 잠금(FOR UPDATE)에서 기다렸다가 첫 호출이 커밋한 DOWN 을 직전 상태로 받는다.
   *
   * <p>"기다렸다"만으로는 판별이 안 된다 — 잠금 없이 읽고 UPDATE 만 해도 UPDATE 가 같은 행에서 기다린다. 그 경우 두 번째 호출은 기다리기 전에 커밋된
   * UP 을 읽어 UP→DOWN 전환을 한 번 더 보고(= 끊김 알림 두 번) 이 단언이 깨진다. 그래서 돌려받은 직전 상태를 단언한다.
   */
  @Test
  @DisplayName("같은 전환을 동시에 일으키면 두 번째는 커밋된 값을 직전 상태로 받아 전환을 다시 보지 않는다")
  void updateHealthStatus_concurrentSameTransition_secondSeesCommittedPrevious() throws Exception {
    long id = saveA("race", "/ping");
    inA(() -> repository().updateHealthStatus(id, "UP", 10L, null));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch firstDone = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      Future<String> first =
          pool.submit(
              () ->
                  inA(
                      () -> {
                        String previous =
                            repository().updateHealthStatus(id, "DOWN", null, "first");
                        firstDone.countDown();
                        awaitQuietly(release);
                        return previous;
                      }));
      assertThat(firstDone.await(30, TimeUnit.SECONDS)).isTrue();

      Future<String> second =
          pool.submit(() -> inA(() -> repository().updateHealthStatus(id, "DOWN", null, "second")));
      assertThatThrownBy(() -> second.get(1500, TimeUnit.MILLISECONDS))
          .as("두 번째 호출은 첫 호출의 행 잠금에서 기다려야 한다")
          .isInstanceOf(TimeoutException.class);

      release.countDown();
      assertThat(first.get(30, TimeUnit.SECONDS)).as("첫 호출이 UP→DOWN 전환을 본다").isEqualTo("UP");
      assertThat(second.get(30, TimeUnit.SECONDS))
          .as("두 번째 호출은 커밋된 DOWN 을 직전 상태로 받아 전환을 다시 보지 않는다")
          .isEqualTo("DOWN");
      assertThat(fx.apiConnectionStored(id).lastErrorMessage()).isEqualTo("second");
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

  /** update 는 null 인 항목을 건드리지 않고, 수정 시각은 새로 쓴다. 헬스체크 경로는 빈 문자열이면 지운다(#115). */
  @Test
  @DisplayName("update 는 null 항목을 보존하고 빈 헬스체크 경로는 지우며 수정 시각을 새로 쓴다")
  void update_partialAndClearHealthPath() {
    long id =
        inA(
            () ->
                repository()
                    .save(
                        prefix + "-upd",
                        "d0",
                        "API_KEY",
                        "enc0",
                        user,
                        "https://u0.example.com",
                        "/p0"));
    LocalDateTime old = LocalDateTime.now().minusDays(2);
    fx.setApiConnectionUpdatedAt(id, old);

    // 이름만 바꾸면 나머지는 그대로다.
    inA(
        () -> {
          repository().update(id, prefix + "-upd2", null, null, null, null, null);
          return null;
        });
    ApiConnectionStored s1 = fx.apiConnectionStored(id);
    assertThat(s1.baseUrl()).isEqualTo("https://u0.example.com");
    assertThat(s1.healthCheckPath()).isEqualTo("/p0");
    assertThat(s1.authConfig()).isEqualTo("enc0");
    assertThat(s1.updatedAt()).isAfter(old.plusDays(1));
    ApiConnectionRow r1 = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(r1.name()).isEqualTo(prefix + "-upd2");
    assertThat(r1.description()).isEqualTo("d0");
    assertThat(r1.authType()).isEqualTo("API_KEY");

    // 나머지 항목 전부 바꾸기.
    inA(
        () -> {
          repository().update(id, null, "d1", "BEARER", "enc1", "https://u1.example.com", "/p1");
          return null;
        });
    ApiConnectionRow r2 = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(r2.name()).isEqualTo(prefix + "-upd2");
    assertThat(r2.description()).isEqualTo("d1");
    assertThat(r2.authType()).isEqualTo("BEARER");
    assertThat(r2.authConfig()).isEqualTo("enc1");
    assertThat(r2.baseUrl()).isEqualTo("https://u1.example.com");
    assertThat(r2.healthCheckPath()).isEqualTo("/p1");

    // 빈 헬스체크 경로는 NULL 로 지운다.
    inA(
        () -> {
          repository().update(id, null, null, null, null, null, "");
          return null;
        });
    assertThat(fx.apiConnectionStored(id).healthCheckPath()).isNull();
    assertThat(inA(() -> repository().findHealthCheckable()))
        .extracting(HealthCheckTarget::id)
        .doesNotContain(id);
  }

  /** 이름 중복 확인은 현재 테넌트 안에서만 하고(RLS), 자기 자신은 제외할 수 있다. 같은 이름을 두 테넌트가 따로 쓸 수 있다. */
  @Test
  @DisplayName("existsByName·existsByNameExcludingId·existsById 는 현재 테넌트 안에서만 판정한다")
  void exists_methods() {
    long id = saveA("ex", "/h");
    String name = prefix + "-ex";

    assertThat(inA(() -> repository().existsByName(name))).isTrue();
    assertThat(inA(() -> repository().existsByName(name + "-none"))).isFalse();
    assertThat(inA(() -> repository().existsByNameExcludingId(name, id))).isFalse();
    assertThat(inA(() -> repository().existsByNameExcludingId(name, id + 1))).isTrue();
    assertThat(inA(() -> repository().existsById(id))).isTrue();
    assertThat(inA(() -> repository().existsById(-1L))).isFalse();

    // 다른 테넌트에는 보이지 않고, 같은 이름도 따로 쓸 수 있다.
    assertThat(inB(() -> repository().existsByName(name))).isFalse();
    long b =
        inB(
            () ->
                repository()
                    .save(name, null, "API_KEY", "enc", user, "https://b.example.com", null));
    assertThat(inB(() -> repository().existsByName(name))).isTrue();
    assertThat(inB(() -> repository().existsByNameExcludingId(name, b))).isFalse();
  }

  /** deleteById 는 지운 행 수를 돌려준다 — 두 번째 삭제(동시 삭제의 패자)는 0. */
  @Test
  @DisplayName("deleteById 는 지운 행 수를 돌려준다 — 이미 지워졌으면 0")
  void deleteById_returnsDeletedCount() {
    long id = saveA("del", "/h");

    assertThat(inA(() -> repository().deleteById(id))).isEqualTo(1);
    assertThat(inA(() -> repository().deleteById(id))).isZero();
    assertThat(fx.apiConnectionStored(id)).isNull();
    assertThat(inA(() -> repository().findById(id))).isEmpty();
  }

  /** 사용처 목록은 같은 파이프라인의 여러 API_CALL 스텝이 같은 연결을 써도 한 번만 나온다(DISTINCT, #605). */
  @Test
  @DisplayName("findReferencingPipelines 는 연결을 쓰는 파이프라인을 중복 없이 돌려준다")
  void findReferencingPipelines_distinct() {
    long conn = saveA("ref", "/h");
    long other = saveA("ref-other", "/h");
    long p1 = fx.createPipeline(tenantA, prefix + "-p1", true);
    long p2 = fx.createPipeline(tenantA, prefix + "-p2", true);
    long p3 = fx.createPipeline(tenantA, prefix + "-p3", true);
    fx.createApiCallStep(p1, "s1", conn);
    fx.createApiCallStep(p1, "s2", conn);
    fx.createApiCallStep(p2, "s1", conn);
    fx.createApiCallStep(p3, "s1", other);

    List<ReferencingPipeline> refs = inA(() -> repository().findReferencingPipelines(conn));

    assertThat(refs)
        .containsExactlyInAnyOrder(
            new ReferencingPipeline(p1, prefix + "-p1"),
            new ReferencingPipeline(p2, prefix + "-p2"));
    assertThat(inA(() -> repository().findReferencingPipelines(-1L))).isEmpty();
    // 다른 테넌트에서는 그 연결 id 의 사용처가 보이지 않는다(파이프라인·스텝 정책).
    assertThat(inB(() -> repository().findReferencingPipelines(conn))).isEmpty();
  }
}
