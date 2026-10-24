package com.smartfirehub.dbexecutor.store;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * 테넌트별 Hikari 풀 보관소.
 *
 * <p>키는 tenantId 이고, 접속값(host·port·database·username·password)의 SHA-256 지문이 바뀌면 옛 풀을 새 풀로 갈아 끼운다 —
 * 비밀번호 회전을 따라가기 위해서다. 지문만 들고 비밀번호는 따로 보관·로그하지 않는다. 풀이 {@code maxPools} 를 넘으면 가장 오래 안 쓴 풀부터 맵에서
 * 뺀다(LRU).
 *
 * <p>사용 중인 풀은 닫지 않는다(api {@code ExternalStoreConnections} 의 사용 횟수 규칙). HikariCP 는 닫을 때 사용 중인 커넥션을
 * abort 해 다른 요청의 트랜잭션을 죽이기 때문이다. 그래서 {@link #borrow} 는 {@link Lease} 를 돌려주고, 맵에서 빠진(퇴역한) 풀은 마지막 대여가
 * 반납될 때 닫는다. 대여 중인 풀이 퇴역하면 살아 있는 풀 수가 잠시 {@code maxPools} 를 넘을 수 있다(허용).
 *
 * <p>같은 테넌트라도 접속 계정(OWNER·읽기 전용)마다 보관소를 따로 둔다 — 어느 계정을 쓸지는 생성자로 받은 선택 함수가 정하고, 지문도 선택된 계정으로 계산한다.
 * 그래서 RO 비밀번호만 바뀌면 RO 풀만 교체되고 OWNER 풀은 그대로다.
 *
 * <p>슬롯 풀(WD-78, {@link #forSlots})은 키가 (테넌트, 슬롯)이다 — 같은 테넌트라도 슬롯 계정 S1..S10 마다 풀이 따로이고, 계정은 기술자의
 * {@code slots[k]} 다. 지문도 그 슬롯 계정으로 계산하므로 슬롯 하나의 비밀번호가 바뀌면 그 슬롯 풀만 교체된다. OWNER·RO 보관소는 슬롯 번호 0 하나만
 * 쓰는 같은 구조다.
 *
 * <p>풀 생성은 생성자로 주입한 팩토리가 맡는다(테스트에서는 가짜 DataSource). 풀 닫기는 잠금 밖에서 한다.
 */
public class TenantPools {

  /** 풀 키. OWNER·RO 보관소는 {@code slot=0}, 슬롯 보관소는 1..10. */
  private record PoolKey(long tenantId, int slot) {}

  /** 풀 하나와 상태. {@code leases}·{@code retired} 는 {@code TenantPools} 모니터 아래에서만 바꾼다. */
  private static final class Entry {
    final DataSource dataSource;
    final String fingerprint;
    int leases;
    boolean retired;

    Entry(DataSource dataSource, String fingerprint) {
      this.dataSource = dataSource;
      this.fingerprint = fingerprint;
    }
  }

  /** 빌린 커넥션. {@link #close()} 는 커넥션을 돌려주고 풀의 대여 수를 줄인다(여러 번 불러도 한 번만 반영). */
  public final class Lease implements AutoCloseable {
    private final Entry entry;
    private final Connection connection;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Lease(Entry entry, Connection connection) {
      this.entry = entry;
      this.connection = connection;
    }

    /** 빌린 커넥션(autoCommit=false). 직접 close 하지 말고 이 Lease 를 닫는다. */
    public Connection connection() {
      return connection;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      try {
        connection.close();
      } catch (SQLException ignored) {
        // 반납 실패는 호출 흐름에 영향 주지 않는다.
      }
      release(entry);
    }
  }

  private final BiFunction<StoreDescriptor, Integer, DataSource> factory;
  private final BiFunction<StoreDescriptor, Integer, StoreDescriptor.Account> account;
  private final int maxPools;
  // accessOrder=true: get/put 때마다 맨 뒤로 이동 → 맨 앞이 가장 오래 안 쓴 풀.
  private final LinkedHashMap<PoolKey, Entry> pools = new LinkedHashMap<>(16, 0.75f, true);

  /**
   * @param factory 풀 생성기(같은 선택 함수를 쓰는 {@link HikariPoolFactory})
   * @param account 기술자에서 이 보관소가 쓸 계정을 고르는 함수(OWNER 면 {@code StoreDescriptor::owner})
   * @param maxPools 유지할 풀 수 상한
   */
  public TenantPools(
      Function<StoreDescriptor, DataSource> factory,
      Function<StoreDescriptor, StoreDescriptor.Account> account,
      int maxPools) {
    this((d, slot) -> factory.apply(d), (d, slot) -> account.apply(d), maxPools);
  }

  private TenantPools(
      BiFunction<StoreDescriptor, Integer, DataSource> factory,
      BiFunction<StoreDescriptor, Integer, StoreDescriptor.Account> account,
      int maxPools) {
    this.factory = factory;
    this.account = account;
    this.maxPools = maxPools;
  }

  /**
   * 슬롯 풀 보관소(WD-78): 키 (테넌트, 슬롯), 계정 = 기술자 {@code slots[k]}.
   *
   * @param factory (기술자, 슬롯) → 풀 생성기({@link HikariPoolFactory#forSlots})
   * @param maxPools 유지할 슬롯 풀 수 상한(전 테넌트·전 슬롯 합계)
   */
  public static TenantPools forSlots(
      BiFunction<StoreDescriptor, Integer, DataSource> factory, int maxPools) {
    return new TenantPools(factory, StoreDescriptor::slot, maxPools);
  }

  /** 테넌트 풀에서 커넥션 하나를 빌린다. 반드시 {@link Lease#close()} 로 반납한다. */
  public Lease borrow(StoreDescriptor d) throws SQLException {
    return borrow(d, 0);
  }

  /**
   * (테넌트, 슬롯) 풀에서 커넥션 하나를 빌린다. 슬롯 보관소({@link #forSlots})는 1..10, OWNER·RO 보관소는 {@link
   * #borrow(StoreDescriptor)} 를 쓴다. 반드시 {@link Lease#close()} 로 반납한다.
   */
  public Lease borrow(StoreDescriptor d, int slot) throws SQLException {
    List<DataSource> toClose = new ArrayList<>();
    Entry entry;
    PoolKey key = new PoolKey(d.tenantId(), slot);
    synchronized (this) {
      String fp = fingerprint(d, slot);
      entry = pools.get(key);
      if (entry != null && !entry.fingerprint.equals(fp)) {
        pools.remove(key);
        retire(entry, toClose);
        entry = null;
      }
      if (entry == null) {
        entry = new Entry(factory.apply(d, slot), fp);
        pools.put(key, entry);
        // 방금 넣은 것은 맨 뒤라 제거 대상에서 자연히 제외된다.
        Iterator<Map.Entry<PoolKey, Entry>> it = pools.entrySet().iterator();
        while (pools.size() > maxPools && it.hasNext()) {
          Entry victim = it.next().getValue();
          it.remove();
          retire(victim, toClose);
        }
      }
      // 대여 수를 잠금 안에서 올려 두어야, 이어지는 getConnection 중에 다른 스레드가 이 풀을 닫지 못한다.
      entry.leases++;
    }
    toClose.forEach(TenantPools::closeQuietly);
    Connection c;
    try {
      c = entry.dataSource.getConnection();
    } catch (SQLException | RuntimeException e) {
      release(entry);
      throw e;
    }
    return new Lease(entry, c);
  }

  /** 맵에서 뺀 풀을 퇴역시킨다. 대여가 없으면 바로 닫을 목록에 넣고, 있으면 마지막 반납 때 닫는다. 모니터 안에서 호출. */
  private static void retire(Entry e, List<DataSource> toClose) {
    e.retired = true;
    if (e.leases == 0) {
      toClose.add(e.dataSource);
    }
  }

  /** 대여 하나를 반납한다. 퇴역한 풀의 마지막 대여면 닫는다. */
  private void release(Entry e) {
    boolean close;
    synchronized (this) {
      e.leases--;
      close = e.retired && e.leases == 0;
    }
    if (close) {
      closeQuietly(e.dataSource);
    }
  }

  /** 종료 시 풀을 닫는다. 대여 중인 풀은 퇴역만 시키고 마지막 반납 때 닫는다. */
  @PreDestroy
  public void closeAll() {
    List<DataSource> toClose = new ArrayList<>();
    synchronized (this) {
      for (Entry e : pools.values()) {
        retire(e, toClose);
      }
      pools.clear();
    }
    toClose.forEach(TenantPools::closeQuietly);
  }

  private static void closeQuietly(DataSource ds) {
    if (ds instanceof AutoCloseable c) {
      try {
        c.close();
      } catch (Exception ignored) {
        // 닫기 실패는 호출 흐름에 영향 주지 않는다.
      }
    }
  }

  /** 선택된 계정의 접속값 SHA-256 지문. 비밀번호를 그대로 들고 있지 않기 위해 해시만 비교한다. */
  private String fingerprint(StoreDescriptor d, int slot) {
    StoreDescriptor.Account o = account.apply(d, slot);
    if (o == null) {
      // 기술자에 이 보관소가 쓸 계정(예: ro)이 없다 — NPE 대신 원인을 알 수 있게 던진다.
      throw new IllegalStateException("테넌트 " + d.tenantId() + " 의 접속 계정이 기술자에 없습니다.");
    }
    String raw =
        d.host() + "|" + d.port() + "|" + d.database() + "|" + o.username() + "|" + o.password();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
