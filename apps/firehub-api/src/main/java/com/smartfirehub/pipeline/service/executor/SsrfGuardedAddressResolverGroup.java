package com.smartfirehub.pipeline.service.executor;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/**
 * 접속 시점 SSRF 차단용 Netty 주소 해석기 그룹(WD-613).
 *
 * <p><b>왜 필요한가.</b> {@link SsrfProtectionService#validateUrl} 은 호출 전에 호스트를 한 번 해석해 사설·예약 주소를 막지만,
 * 실제 접속은 WebClient(Reactor Netty)가 같은 호스트를 <b>다시</b> 해석해서 한다. TTL 0 인 공격자 DNS 가 검사 때는 공인 IP, 접속 때는
 * 127.0.0.1·169.254.169.254 를 돌려주면(DNS 재바인딩, TOCTOU) 검사를 통과한 채 내부로 접속됐다.
 *
 * <p><b>어떻게 막는가.</b> 이 그룹을 Reactor Netty {@code HttpClient.resolver(...)} 로 넣으면, Netty 는 접속할 주소를 오직
 * 이 해석기가 돌려준 값에서만 고른다. 이 해석기는 실제 해석기(기본: Reactor Netty 기본값과 같은 비동기 Netty DNS 해석기 — 이벤트 루프를 막지 않는다)의
 * 결과를 받아 정책({@link AddressPolicy})으로 모든 주소를 검사하고, 하나라도 막힌 주소면 해석 자체를 실패시킨다 — 검사한 주소와 접속하는 주소가 같으므로
 * 둘이 어긋날 수 없다. URL 의 호스트 이름은 그대로라 Host 헤더·TLS SNI 는 바뀌지 않는다.
 *
 * <p>IP 리터럴 URL(예: {@code http://127.0.0.1/}) 은 Reactor Netty 가 이미 해석된 주소로 넘기는데, 기본 해석기라면 해석을 건너뛴다.
 * 이 해석기는 「해석되지 않음」으로 답해 그 주소도 같은 정책으로 검사한다(사전 검사·리다이렉트 검사가 빠진 경로에 대한 이중 방어).
 */
public final class SsrfGuardedAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

  /**
   * 해석된 주소 하나를 허용할지 판정한다. 막을 주소면 사용자 문구를 담은 {@link SsrfException} 을 던진다 — 접속 실패 원인으로 그대로 올라가, 호출부가
   * 사전 검사(validateUrl)와 같은 문구로 안내할 수 있게 한다.
   */
  @FunctionalInterface
  public interface AddressPolicy {
    void check(InetAddress address);
  }

  private final AddressResolverGroup<InetSocketAddress> delegate;
  private final AddressPolicy policy;

  /**
   * @param delegate 실제 이름 해석을 맡길 그룹(운영: {@code DefaultAddressResolverGroup.INSTANCE}, 테스트: 가짜 DNS)
   * @param policy 해석된 모든 주소에 적용할 차단 정책
   */
  public SsrfGuardedAddressResolverGroup(
      AddressResolverGroup<InetSocketAddress> delegate, AddressPolicy policy) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  /** 실제 이름 해석을 맡긴 그룹(테스트 확인용). */
  AddressResolverGroup<InetSocketAddress> delegate() {
    return delegate;
  }

  /** 이벤트 루프마다 하나씩 만들어진다(상위 클래스가 캐시). 실제 해석은 같은 이벤트 루프의 delegate 해석기에 맡긴다. */
  @Override
  protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
    return new GuardedResolver(executor, delegate.getResolver(executor), policy);
  }

  /** delegate 해석 결과를 정책으로 검사한 뒤에만 넘겨주는 해석기. */
  static final class GuardedResolver extends AbstractAddressResolver<InetSocketAddress> {

    private final AddressResolver<InetSocketAddress> delegate;
    private final AddressPolicy policy;

    GuardedResolver(
        EventExecutor executor, AddressResolver<InetSocketAddress> delegate, AddressPolicy policy) {
      super(executor, InetSocketAddress.class);
      this.delegate = delegate;
      this.policy = policy;
    }

    /**
     * 항상 「해석되지 않음」으로 답한다 — true 면 Reactor Netty 가 이 해석기를 건너뛰고 그 주소로 바로 접속하므로(IP 리터럴), 검사를 빠뜨리지 않으려고
     * 모든 주소를 아래 doResolve*로 보낸다.
     */
    @Override
    protected boolean doIsResolved(InetSocketAddress address) {
      return false;
    }

    @Override
    protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
      if (!address.isUnresolved()) {
        // 이미 IP 인 주소 — delegate 에 넘기면 역방향 조회를 할 수 있어 직접 검사만 한다.
        completeChecked(promise, address);
        return;
      }
      delegate
          .resolve(address)
          .addListener(
              (Future<InetSocketAddress> f) -> {
                if (!f.isSuccess()) {
                  promise.tryFailure(f.cause());
                  return;
                }
                completeChecked(promise, f.getNow());
              });
    }

    @Override
    protected void doResolveAll(
        InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
      if (!address.isUnresolved()) {
        completeAllChecked(promise, List.of(address));
        return;
      }
      delegate
          .resolveAll(address)
          .addListener(
              (Future<List<InetSocketAddress>> f) -> {
                if (!f.isSuccess()) {
                  promise.tryFailure(f.cause());
                  return;
                }
                completeAllChecked(promise, f.getNow());
              });
    }

    /** 주소 하나를 검사해 통과면 그 주소로, 막히면 정책 예외로 끝낸다. */
    private void completeChecked(Promise<InetSocketAddress> promise, InetSocketAddress resolved) {
      try {
        policy.check(resolved.getAddress());
      } catch (RuntimeException blocked) {
        promise.tryFailure(blocked);
        return;
      }
      promise.trySuccess(resolved);
    }

    /**
     * 해석된 주소를 <b>모두</b> 검사한다 — 하나라도 막히면 전체를 실패시킨다. 사전 검사(validateUrl)가 「해석된 주소 중 하나라도 내부망이면 거부」라 같은
     * 기준을 쓴다(일부만 걸러 공인 주소로 접속하게 두면 두 판정이 갈린다).
     */
    private void completeAllChecked(
        Promise<List<InetSocketAddress>> promise, List<InetSocketAddress> resolved) {
      try {
        for (InetSocketAddress each : resolved) {
          policy.check(each.getAddress());
        }
      } catch (RuntimeException blocked) {
        promise.tryFailure(blocked);
        return;
      }
      promise.trySuccess(resolved);
    }

    /** delegate 해석기는 delegate 그룹이 소유·정리하므로(공유 싱글턴일 수 있다) 여기서 닫지 않는다. */
    @Override
    public void close() {
      // no-op
    }
  }
}
