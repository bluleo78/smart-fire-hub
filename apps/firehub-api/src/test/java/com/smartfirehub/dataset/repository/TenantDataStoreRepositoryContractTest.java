package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantDataStoreRepository} 계약 테스트(WD-73, WD-485, WD-707) — 전역 테이블 왕복·순서·세대 번호·문장 시간 제한을 실제
 * DB 로 검증한다. 구현마다 하위 클래스로 실행한다(Jooq·MyBatis PG·MyBatis Tibero).
 *
 * <p>리뷰 포인트 4(다른 테넌트 차단): 이 두 테이블은 RLS 정책이 없는 전역 테이블이라 DB 가 행을 걸러 주지 않는다(테넌트 컨텍스트 없이도 읽힌다 — 백엔드 해석이
 * 그렇게 쓴다). 그래서 보장은 「인자로 준 테넌트 id 의 행만 읽고 바꾼다」이다. 두 테넌트 A·B 를 서로 다른 값으로 채우고, 모든 테넌트 키 메서드를 A(또는 행이
 * 없는 C) 로 불러도 B 의 행이 읽히지도 바뀌지도 않음을 소유자 시점으로 확인한다.
 *
 * <p>PG 전용 시험(relrowsecurity 카탈로그·PG 동기화·FK 캐스케이드)은 {@code TenantDataStorePgOnlyTest} 로 분리했다.
 */
abstract class TenantDataStoreRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract TenantDataStoreRepository repository();

  /** 저장소 행이 없는 테넌트. */
  private long tenantC;

  @BeforeEach
  void setUpExtra() {
    tenantC = fx.createTenant();
  }

  private static TenantDataStoreRow row(long id) {
    return row(id, "tibero.example");
  }

  private static TenantDataStoreRow row(long id, String host) {
    return new TenantDataStoreRow(
        id,
        DataStoreBackend.TIBERO,
        host,
        8629,
        "tibero",
        "GENIA_T" + id,
        "TS_T" + id,
        DataStoreProvisionMode.SCRIPT,
        TenantDataStoreStatus.PENDING);
  }

  private static List<StoredAccount> accounts(long id, String passwordPrefix) {
    return DataStoreAccountRole.all().stream()
        .map(r -> new StoredAccount(r, r.usernameFor(id), passwordPrefix + r.name()))
        .toList();
  }

  @Test
  @DisplayName("insert 후 find·findBackend·findAccounts — 행과 계정이 왕복하고 계정은 역할 선언 순서")
  void insert_thenFind_roundTripsRowAndAccountsInRoleOrder() {
    repository().insert(row(tenantA), accounts(tenantA, "enc-"));

    assertThat(repository().find(tenantA)).contains(row(tenantA));
    assertThat(repository().findBackend(tenantA)).contains(DataStoreBackend.TIBERO);
    List<StoredAccount> found = repository().findAccounts(tenantA);
    assertThat(found).isEqualTo(accounts(tenantA, "enc-"));
    assertThat(found).extracting(StoredAccount::role).isEqualTo(DataStoreAccountRole.all());
  }

  @Test
  @DisplayName("tablespace 가 null 이어도 왕복한다")
  void insert_nullTablespace_roundTrips() {
    TenantDataStoreRow noTs =
        new TenantDataStoreRow(
            tenantA,
            DataStoreBackend.MYSQL,
            "mysql.example",
            3306,
            "db",
            "schema_a",
            null,
            DataStoreProvisionMode.DBA_AUTO,
            TenantDataStoreStatus.PENDING);
    repository().insert(noTs, List.of());

    assertThat(repository().find(tenantA)).contains(noTs);
    assertThat(repository().findAccounts(tenantA)).isEmpty();
  }

  @Test
  @DisplayName("findReadyTenantIds — 그 백엔드의 READY 저장소만(PENDING·다른 백엔드 제외)")
  void findReadyTenantIds_onlyReadyStoresOfThatBackend() {
    repository().insert(row(tenantA), accounts(tenantA, "enc-"));
    // PENDING 은 풀을 만들 수 없으므로 배경 작업 대상이 아니다.
    assertThat(repository().findReadyTenantIds(DataStoreBackend.TIBERO)).doesNotContain(tenantA);

    repository().updateStatus(tenantA, TenantDataStoreStatus.READY);
    List<Long> ready = repository().findReadyTenantIds(DataStoreBackend.TIBERO);
    assertThat(ready).contains(tenantA);
    assertThat(ready).isSorted();
    assertThat(repository().findReadyTenantIds(DataStoreBackend.MYSQL)).doesNotContain(tenantA);
  }

  /** 정지된 테넌트는 저장소가 READY 여도 빠진다(WD-449). */
  @Test
  @DisplayName("findReadyTenantIds — 정지된 테넌트는 READY 여도 제외")
  void findReadyTenantIds_excludesSuspendedTenant() {
    repository().insert(row(tenantA), accounts(tenantA, "enc-"));
    repository().updateStatus(tenantA, TenantDataStoreStatus.READY);
    assertThat(repository().findReadyTenantIds(DataStoreBackend.TIBERO)).contains(tenantA);

    fx.setTenantStatus(tenantA, "SUSPENDED");

    assertThat(repository().findReadyTenantIds(DataStoreBackend.TIBERO)).doesNotContain(tenantA);
  }

  @Test
  @DisplayName("행이 없으면 findBackend·find 는 빈 값, findAccounts 는 빈 목록")
  void findBackend_returnsEmptyWhenNoRow() {
    assertThat(repository().findBackend(tenantC)).isEmpty();
    assertThat(repository().find(tenantC)).isEmpty();
    assertThat(repository().findAccounts(tenantC)).isEmpty();
  }

  @Test
  @DisplayName("updateStatus — 상태를 바꾸고 updated_at 을 앞으로 옮긴다")
  void updateStatus_changesStatusAndUpdatedAt() {
    repository().insert(row(tenantA), accounts(tenantA, "enc-"));
    // updated_at 을 과거로 돌려 두면 갱신 여부가 시계 해상도와 무관하게 드러난다.
    LocalDateTime past = LocalDateTime.of(2020, 1, 1, 0, 0);
    fx.setDataStoreUpdatedAt(tenantA, past);

    repository().updateStatus(tenantA, TenantDataStoreStatus.READY);

    assertThat(repository().find(tenantA).orElseThrow().status())
        .isEqualTo(TenantDataStoreStatus.READY);
    LocalDateTime updatedAt = fx.dataStoreUpdatedAt(tenantA);
    assertThat(updatedAt).isAfter(past.plusYears(1));
    // 앱 시계 LocalDateTime.now() 를 그대로 저장한다(두 구현 같음) — JVM 시간대와 무관하게 지금 근처.
    assertThat(Duration.between(updatedAt, LocalDateTime.now()).abs())
        .isLessThan(Duration.ofHours(1));
  }

  @Test
  @DisplayName("replacePasswords — 암호화된 비밀번호만 바꾸고 username 은 유지")
  void replacePasswords_overwritesEncryptedPasswordsOnly() {
    repository().insert(row(tenantA), accounts(tenantA, "old-"));

    repository().replacePasswords(tenantA, accounts(tenantA, "new-"));

    assertThat(repository().findAccounts(tenantA)).isEqualTo(accounts(tenantA, "new-"));
  }

  /** 갱신 대상 행이 없는 역할이 있으면 조용히 넘어가지 않고 예외로 알린다(호출자 트랜잭션이 롤백된다). */
  @Test
  @DisplayName("replacePasswords — 계정 행이 없는 역할이 있으면 IllegalStateException(비밀번호 비노출)")
  void replacePasswords_missingAccountRow_throws() {
    // S10 행 없이 저장한다.
    repository().insert(row(tenantA), accounts(tenantA, "old-").subList(0, 11));

    assertThatThrownBy(() -> repository().replacePasswords(tenantA, accounts(tenantA, "new-")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("S10")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("new-"));
  }

  /** 행 잠금은 트랜잭션 안에서 그 테넌트의 행만 실제로 잠그고, 행이 없어도 오류가 아니다. */
  @Test
  @DisplayName("lockForUpdate — 트랜잭션 안에서 A 의 행만 실제로 잠그고(B 는 안 잠김) 행이 없어도 오류 없음")
  void lockForUpdate_locksOnlyThatTenantsRow() {
    repository().insert(row(tenantA, "a.example"), accounts(tenantA, "a-"));
    repository().insert(row(tenantB, "b.example"), accounts(tenantB, "b-"));
    assertThat(fx.isDataStoreRowLockedByOther(tenantA)).isFalse();

    fx.inTenant(
        tenantA,
        () -> {
          repository().lockForUpdate(tenantA);
          repository().lockForUpdate(99_999_999L);
          // 별도 커넥션이 A 의 행을 못 잡고(잠김), B 의 행은 잡는다(안 잠김).
          assertThat(fx.isDataStoreRowLockedByOther(tenantA)).isTrue();
          assertThat(fx.isDataStoreRowLockedByOther(tenantB)).isFalse();
          return null;
        });

    // 트랜잭션이 끝나면 풀린다.
    assertThat(fx.isDataStoreRowLockedByOther(tenantA)).isFalse();
  }

  /** 세대 번호는 0 에서 시작해 올릴 때마다 1 씩 늘고, 행이 없으면 empty 다(V147). */
  @Test
  @DisplayName("스크립트 토큰 세대 번호 — 0 에서 시작해 1 씩 늘고 행이 없으면 empty(잠금 대기 판 포함)")
  void scriptTokenEpoch_startsAtZero_andIncrementsByOne() {
    assertThat(repository().findScriptTokenEpoch(tenantA)).isEmpty();
    assertThat(repository().incrementScriptTokenEpoch(tenantA)).isEmpty();

    repository().insert(row(tenantA), accounts(tenantA, "enc-"));
    assertThat(repository().findScriptTokenEpoch(tenantA)).contains(0L);
    assertThat(repository().incrementScriptTokenEpoch(tenantA)).contains(1L);
    assertThat(repository().incrementScriptTokenEpoch(tenantA)).contains(2L);
    assertThat(repository().findScriptTokenEpoch(tenantA)).contains(2L);
    // 잠금 대기 상한이 있는 판(WD-664 4b)도 다투는 쪽이 없으면 똑같이 1 올린다.
    assertThat(repository().incrementScriptTokenEpoch(tenantA, Duration.ofSeconds(3))).contains(3L);
    assertThat(repository().incrementScriptTokenEpoch(tenantC, Duration.ofSeconds(3))).isEmpty();
  }

  /** 증가는 updated_at 도 앞으로 옮긴다(두 판 모두). */
  @Test
  @DisplayName("세대 번호 올리기는 updated_at 도 갱신한다")
  void incrementScriptTokenEpoch_bumpsUpdatedAt() {
    repository().insert(row(tenantA), accounts(tenantA, "enc-"));
    LocalDateTime past = LocalDateTime.of(2020, 1, 1, 0, 0);
    fx.setDataStoreUpdatedAt(tenantA, past);
    repository().incrementScriptTokenEpoch(tenantA);
    assertThat(fx.dataStoreUpdatedAt(tenantA)).isAfter(past.plusYears(1));

    fx.setDataStoreUpdatedAt(tenantA, past);
    repository().incrementScriptTokenEpoch(tenantA, Duration.ofSeconds(2));
    assertThat(fx.dataStoreUpdatedAt(tenantA)).isAfter(past.plusYears(1));
  }

  /**
   * 잠금 대기 상한(WD-664 4b): 다른 커넥션이 행을 쥐고 있으면 올림 1초 안팎에 문장이 취소되고 예외가 난다 — 무한정 기다리지 않는다. 취소된 문장은 값을 바꾸지
   * 않는다.
   */
  @Test
  @DisplayName("incrementScriptTokenEpoch(lockWait) — 행 잠금 대기가 시간 제한을 넘으면 예외, 값은 그대로")
  void incrementScriptTokenEpoch_withLockWait_timesOutWhenRowLocked() {
    repository().insert(row(tenantA), accounts(tenantA, "enc-"));

    long[] elapsedMs = new long[1];
    Throwable[] observed = new Throwable[1];
    fx.withDataStoreRowLocked(
        tenantA,
        () -> {
          long start = System.nanoTime();
          try {
            fx.inTenant(
                tenantA,
                () -> {
                  // 시간 초과 예외는 호출 지점에서 잡아 종류를 확인한다 — Tibero 는 문장 취소 때 커넥션을 닫아 바깥 트랜잭션의 롤백이
                  // 다시 실패하고(TransactionSystemException) 그 예외가 원래 예외를 덮기 때문이다.
                  try {
                    repository().incrementScriptTokenEpoch(tenantA, Duration.ofMillis(1200));
                  } catch (RuntimeException e) {
                    observed[0] = e;
                    throw e;
                  }
                  return null;
                });
          } catch (RuntimeException outer) {
            // 바깥 트랜잭션 정리 실패는 이 시험의 관심사가 아니다.
          }
          elapsedMs[0] = (System.nanoTime() - start) / 1_000_000L;
          return null;
        });

    // 스프링 번역 결과가 DB 마다 다르다(실측): PG 는 SQLState 57014 가 자원 실패로, Tibero 는 SQLTimeoutException 이
    // QueryTimeoutException 으로 번역된다.
    assertThat(observed[0])
        .isInstanceOf(
            emptyStringIsNull()
                ? org.springframework.dao.QueryTimeoutException.class
                : org.springframework.dao.DataAccessResourceFailureException.class);
    // 1200ms 는 2초로 올림한다 — 제한이 적용돼 잠금을 무한정 기다리지 않아야 하고(상한), 즉시 실패하지도 않아야 한다(하한).
    assertThat(elapsedMs[0]).isBetween(1_000L, 15_000L);
    assertThat(repository().findScriptTokenEpoch(tenantA)).contains(0L);
  }

  /** 정책이 없는 전역 테이블 — 테넌트 컨텍스트 없이도 읽힌다(백엔드 해석이 그렇게 쓴다). */
  @Test
  @DisplayName("tenant_data_store 는 테넌트 컨텍스트 없이도 읽힌다(정책 없는 전역 테이블)")
  void tenantDataStore_isReadableWithoutTenantContext() {
    repository().insert(row(tenantA), accounts(tenantA, "enc-"));

    assertThat(fx.noTenant(() -> repository().findBackend(tenantA)))
        .contains(DataStoreBackend.TIBERO);
    assertThat(fx.noTenant(() -> repository().findAccounts(tenantA))).hasSize(12);
  }

  // ── 리뷰 포인트 4: 정책이 없는 테이블에서 다른 테넌트 차단 ─────────────────────────

  private void seedAAndB() {
    repository().insert(row(tenantA, "a.example"), accounts(tenantA, "a-"));
    repository().insert(row(tenantB, "b.example"), accounts(tenantB, "b-"));
    // B 의 updated_at 을 과거로 고정해 A 로 부른 호출이 B 를 건드렸는지 드러나게 한다.
    fx.setDataStoreUpdatedAt(tenantB, LocalDateTime.of(2020, 1, 1, 0, 0));
  }

  private void assertBUntouched() {
    assertThat(repository().find(tenantB)).contains(row(tenantB, "b.example"));
    assertThat(fx.dataStoreHost(tenantB)).isEqualTo("b.example");
    assertThat(repository().findAccounts(tenantB)).isEqualTo(accounts(tenantB, "b-"));
    assertThat(repository().findScriptTokenEpoch(tenantB)).contains(0L);
    assertThat(fx.dataStoreUpdatedAt(tenantB)).isEqualTo(LocalDateTime.of(2020, 1, 1, 0, 0));
  }

  @Test
  @DisplayName("다른 테넌트 차단 — A 로 읽으면 A 의 설정·계정·번호만 나오고 B 의 것은 섞이지 않는다")
  void crossTenant_readsReturnOnlyTheRequestedTenant() {
    seedAAndB();

    assertThat(repository().find(tenantA).orElseThrow().host()).isEqualTo("a.example");
    assertThat(repository().findBackend(tenantA)).contains(DataStoreBackend.TIBERO);
    assertThat(repository().findAccounts(tenantA)).isEqualTo(accounts(tenantA, "a-"));
    assertThat(repository().findAccounts(tenantA))
        .extracting(StoredAccount::username)
        .noneMatch(u -> u.equals(DataStoreAccountRole.OWNER.usernameFor(tenantB)));
    // 저장소 행이 없는 C 는 B 의 행이 새어 보이지 않는다.
    assertThat(repository().find(tenantC)).isEmpty();
    assertThat(repository().findAccounts(tenantC)).isEmpty();
    assertThat(repository().findScriptTokenEpoch(tenantC)).isEmpty();
  }

  @Test
  @DisplayName("다른 테넌트 차단 — A 의 updateStatus·replacePasswords·세대 올리기는 B 를 바꾸지 않는다")
  void crossTenant_writesForAneverTouchB() {
    seedAAndB();

    repository().updateStatus(tenantA, TenantDataStoreStatus.READY);
    // A 의 id 로 B 의 계정 값(username 포함)을 실어 보내도 바뀌는 것은 A 의 비밀번호뿐이다.
    repository().replacePasswords(tenantA, accounts(tenantB, "hacked-"));
    assertThat(repository().incrementScriptTokenEpoch(tenantA)).contains(1L);
    assertThat(repository().incrementScriptTokenEpoch(tenantA, Duration.ofSeconds(3))).contains(2L);
    assertThat(repository().find(tenantA).orElseThrow().status())
        .isEqualTo(TenantDataStoreStatus.READY);
    assertThat(repository().findAccounts(tenantA))
        .extracting(StoredAccount::encryptedPassword)
        .allMatch(p -> p.startsWith("hacked-"));
    assertThat(repository().findAccounts(tenantA))
        .extracting(StoredAccount::username)
        .isEqualTo(accounts(tenantA, "x").stream().map(StoredAccount::username).toList());
    assertBUntouched();
    assertThat(repository().find(tenantB).orElseThrow().status())
        .isEqualTo(TenantDataStoreStatus.PENDING);
  }

  @Test
  @DisplayName("다른 테넌트 차단 — 저장소 행이 없는 C 로 쓰면 아무 행도 안 바뀌고(없음 표시·예외) B 는 그대로")
  void crossTenant_writesForRowlessTenantNeverTouchB() {
    seedAAndB();

    repository().updateStatus(tenantC, TenantDataStoreStatus.READY);
    assertThat(repository().incrementScriptTokenEpoch(tenantC)).isEmpty();
    assertThat(repository().incrementScriptTokenEpoch(tenantC, Duration.ofSeconds(3))).isEmpty();
    assertThatThrownBy(() -> repository().replacePasswords(tenantC, accounts(tenantB, "hacked-")))
        .isInstanceOf(IllegalStateException.class);

    assertBUntouched();
    assertThat(repository().find(tenantB).orElseThrow().status())
        .isEqualTo(TenantDataStoreStatus.PENDING);
    assertThat(repository().findAccounts(tenantA)).isEqualTo(accounts(tenantA, "a-"));
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 의 행이 잠겨 있어도 A 의 세대 올리기는 막히지 않는다(B 를 잠그지 않음)")
  void crossTenant_incrementForADoesNotWaitOnBRowLock() {
    seedAAndB();

    Long epoch =
        fx.withDataStoreRowLocked(
            tenantB,
            () ->
                fx.inTenant(
                        tenantA,
                        () ->
                            repository().incrementScriptTokenEpoch(tenantA, Duration.ofSeconds(3)))
                    .orElseThrow());

    assertThat(epoch).isEqualTo(1L);
    assertBUntouched();
  }
}
