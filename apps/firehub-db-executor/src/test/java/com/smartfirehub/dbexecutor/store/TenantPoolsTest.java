package com.smartfirehub.dbexecutor.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 테넌트 풀: 재사용·접속값 변경·LRU·대여(Lease) 중인 풀은 닫지 않음. 가짜 DataSource 로 Hikari 를 대신한다. */
class TenantPoolsTest {

  /** close 여부를 기록하는 가짜 풀. */
  static class FakePool implements DataSource, AutoCloseable {
    final StoreDescriptor d;
    boolean closed;

    FakePool(StoreDescriptor d) {
      this.d = d;
    }

    @Override
    public Connection getConnection() {
      return (Connection)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> method.getName().equals("isClosed") ? Boolean.FALSE : null);
    }

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public Connection getConnection(String u, String p) {
      return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      return null;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }

  private final List<FakePool> created = new ArrayList<>();

  private TenantPools pools(int max) {
    return pools(max, StoreDescriptor::owner);
  }

  private TenantPools pools(int max, Function<StoreDescriptor, StoreDescriptor.Account> account) {
    return new TenantPools(
        d -> {
          FakePool p = new FakePool(d);
          created.add(p);
          return p;
        },
        account,
        max);
  }

  private static StoreDescriptor desc(long tenant, String password) {
    return new StoreDescriptor(
        tenant,
        "TIBERO",
        "h",
        8629,
        "tb",
        "GENIA_T" + tenant,
        new StoreDescriptor.Account("GENIA_T" + tenant, password),
        new StoreDescriptor.Account("GENIA_T" + tenant + "_RO", "ro-" + password));
  }

  /** RO 풀의 지문은 RO 계정으로 계산한다 — RO 비밀번호만 바뀌면 RO 풀만 교체되고 OWNER 풀은 그대로다. */
  @Test
  void readOnlySelector_usesRoAccountForFingerprint() throws Exception {
    TenantPools owner = pools(8);
    TenantPools ro = pools(8, StoreDescriptor::ro);
    StoreDescriptor before = desc(1, "a");
    StoreDescriptor roRotated =
        new StoreDescriptor(
            1,
            "TIBERO",
            "h",
            8629,
            "tb",
            "GENIA_T1",
            before.owner(),
            new StoreDescriptor.Account("GENIA_T1_RO", "rotated"));

    owner.borrow(before).close();
    ro.borrow(before).close();
    assertThat(created).hasSize(2);

    owner.borrow(roRotated).close(); // OWNER 값은 그대로 → 재사용
    assertThat(created).hasSize(2);
    ro.borrow(roRotated).close(); // RO 비밀번호가 바뀜 → RO 풀 교체
    assertThat(created).hasSize(3);
    assertThat(created.get(1).closed).isTrue();
    assertThat(created.get(0).closed).isFalse();
  }

  @Test
  void selectorReturningNull_failsWithClearMessage() {
    TenantPools ro = pools(8, StoreDescriptor::ro);
    StoreDescriptor noRo =
        new StoreDescriptor(1, "TIBERO", "h", 8629, "tb", "GENIA_T1", desc(1, "a").owner(), null);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ro.borrow(noRo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("접속 계정이 기술자에 없습니다");
    assertThat(created).isEmpty();
  }

  @Test
  void sameConnectionValues_reusePool() throws Exception {
    TenantPools p = pools(8);
    p.borrow(desc(1, "a")).close();
    p.borrow(desc(1, "a")).close();
    assertThat(created).hasSize(1);
  }

  @Test
  void passwordChange_idle_closesOldImmediately() throws Exception {
    TenantPools p = pools(8);
    p.borrow(desc(1, "a")).close();
    p.borrow(desc(1, "b")).close();
    assertThat(created).hasSize(2);
    assertThat(created.get(0).closed).isTrue();
    assertThat(created.get(1).closed).isFalse();
  }

  @Test
  void passwordChange_whileLeased_oldClosesAfterLastLease() throws Exception {
    TenantPools p = pools(8);
    TenantPools.Lease l1 = p.borrow(desc(1, "a"));
    TenantPools.Lease l2 = p.borrow(desc(1, "a"));
    TenantPools.Lease n = p.borrow(desc(1, "b")); // 새 접속값 → 새 풀
    assertThat(created).hasSize(2);
    assertThat(created.get(0).closed).isFalse();
    p.borrow(desc(1, "b")).close(); // 새 대여는 새 풀을 재사용
    assertThat(created).hasSize(2);
    l1.close();
    assertThat(created.get(0).closed).isFalse();
    l2.close();
    l2.close(); // 중복 close 는 무시
    assertThat(created.get(0).closed).isTrue();
    assertThat(created.get(1).closed).isFalse();
    n.close();
  }

  @Test
  void ninthTenant_evictsLeastRecentlyUsedIdlePool() throws Exception {
    TenantPools p = pools(8);
    for (long t = 1; t <= 8; t++) {
      p.borrow(desc(t, "a")).close();
    }
    p.borrow(desc(1, "a")).close(); // 1번을 최근 사용으로 → 가장 오래된 것은 2번
    p.borrow(desc(9, "a")).close();
    assertThat(created.stream().filter(x -> x.closed).count()).isEqualTo(1);
    assertThat(created.get(1).closed).isTrue(); // tenant 2
  }

  @Test
  void evictingLeasedPool_doesNotCloseUntilLeaseClosed() throws Exception {
    TenantPools p = pools(8);
    TenantPools.Lease held = p.borrow(desc(1, "a")); // 가장 오래된 풀을 대여 중
    for (long t = 2; t <= 9; t++) {
      p.borrow(desc(t, "a")).close();
    }
    assertThat(created.get(0).closed).isFalse(); // 퇴역했지만 닫히지 않음
    held.close();
    assertThat(created.get(0).closed).isTrue();
  }

  @Test
  void allLeased_overflowAllowed_noneClosed() throws Exception {
    TenantPools p = pools(2);
    List<TenantPools.Lease> leases = new ArrayList<>();
    for (long t = 1; t <= 5; t++) {
      leases.add(p.borrow(desc(t, "a")));
    }
    assertThat(created).hasSize(5);
    assertThat(created).noneMatch(x -> x.closed);
    leases.forEach(TenantPools.Lease::close);
    // 맵에 남은 마지막 2개를 뺀 퇴역 풀 3개는 반납과 함께 닫힌다.
    assertThat(created.stream().filter(x -> x.closed).count()).isEqualTo(3);
  }

  @Test
  void closeAll_closesIdle_defersLeased() throws Exception {
    TenantPools p = pools(8);
    p.borrow(desc(1, "a")).close();
    TenantPools.Lease held = p.borrow(desc(2, "a"));
    p.closeAll();
    assertThat(created.get(0).closed).isTrue();
    assertThat(created.get(1).closed).isFalse();
    held.close();
    assertThat(created.get(1).closed).isTrue();
  }

  // ---- 슬롯 풀(WD-78): 키는 (테넌트, 슬롯), 계정은 기술자 slots[k] ----

  /** 슬롯 계정 S1..S3 을 가진 기술자(슬롯 비밀번호는 "s{k}-" + password). */
  private static StoreDescriptor withSlots(long tenant, String password) {
    StoreDescriptor base = desc(tenant, password);
    List<StoreDescriptor.SlotAccount> slots = new ArrayList<>();
    for (int k = 1; k <= 3; k++) {
      slots.add(
          new StoreDescriptor.SlotAccount(
              k, "GENIA_T" + tenant + "_S" + k, "s" + k + "-" + password));
    }
    return new StoreDescriptor(
        tenant, "TIBERO", "h", 8629, "tb", "GENIA_T" + tenant, base.owner(), base.ro(), slots, 0L);
  }

  private final List<Integer> slotFactoryCalls = new ArrayList<>();

  private TenantPools slotPools(int max) {
    return TenantPools.forSlots(
        (d, slot) -> {
          slotFactoryCalls.add(slot);
          FakePool p = new FakePool(d);
          created.add(p);
          return p;
        },
        max);
  }

  @Test
  void slotPools_keyedByTenantAndSlot() throws Exception {
    TenantPools p = slotPools(8);
    StoreDescriptor d = withSlots(1, "a");
    p.borrow(d, 1).close();
    p.borrow(d, 2).close();
    p.borrow(d, 1).close(); // 같은 (테넌트, 슬롯) → 재사용
    p.borrow(withSlots(2, "a"), 1).close(); // 다른 테넌트의 같은 슬롯 → 다른 풀
    assertThat(created).hasSize(3);
    assertThat(slotFactoryCalls).containsExactly(1, 2, 1);
  }

  /** 슬롯 k 의 비밀번호만 바뀌면 그 슬롯 풀만 갈아 끼운다(다른 슬롯 풀은 그대로). */
  @Test
  void slotPools_slotPasswordRotation_replacesOnlyThatSlot() throws Exception {
    TenantPools p = slotPools(8);
    StoreDescriptor before = withSlots(1, "a");
    p.borrow(before, 1).close();
    p.borrow(before, 2).close();
    List<StoreDescriptor.SlotAccount> rotated = new ArrayList<>(before.slots());
    rotated.set(1, new StoreDescriptor.SlotAccount(2, "GENIA_T1_S2", "rotated"));
    StoreDescriptor after =
        new StoreDescriptor(
            1, "TIBERO", "h", 8629, "tb", "GENIA_T1", before.owner(), before.ro(), rotated, 0L);

    p.borrow(after, 1).close();
    assertThat(created).hasSize(2);
    p.borrow(after, 2).close();
    assertThat(created).hasSize(3);
    assertThat(created.get(1).closed).isTrue(); // 옛 슬롯 2 풀
    assertThat(created.get(0).closed).isFalse(); // 슬롯 1 풀
  }

  @Test
  void slotPools_missingSlotAccount_failsBeforeCreatingPool() {
    TenantPools p = slotPools(8);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> p.borrow(withSlots(1, "a"), 4))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("접속 계정이 기술자에 없습니다");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> p.borrow(desc(1, "a"), 1))
        .isInstanceOf(IllegalStateException.class);
    assertThat(created).isEmpty();
  }

  @Test
  void slotPools_lruAcrossTenantSlotKeys() throws Exception {
    TenantPools p = slotPools(2);
    StoreDescriptor d = withSlots(1, "a");
    p.borrow(d, 1).close();
    p.borrow(d, 2).close();
    p.borrow(d, 3).close(); // 상한 2 → (1, 1) 퇴역
    assertThat(created.get(0).closed).isTrue();
    assertThat(created.get(1).closed).isFalse();
  }

  @Test
  void descriptor_slotLookupAndMaskedToString() {
    StoreDescriptor d = withSlots(1, "pw");
    assertThat(d.slot(2).username()).isEqualTo("GENIA_T1_S2");
    assertThat(d.slot(2).password()).isEqualTo("s2-pw");
    assertThat(d.slot(4)).isNull();
    assertThat(desc(1, "pw").slot(1)).isNull();
    assertThat(d.toString()).doesNotContain("s1-pw").doesNotContain("s2-pw");
  }
}
