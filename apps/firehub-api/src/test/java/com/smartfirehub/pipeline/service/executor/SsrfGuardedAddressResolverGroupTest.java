package com.smartfirehub.pipeline.service.executor;

import static com.smartfirehub.pipeline.service.executor.FakeDnsResolverGroup.ip;
import static com.smartfirehub.pipeline.service.executor.FakeDnsResolverGroup.ips;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 접속 시점 SSRF 해석기(WD-613) 단위 테스트 — 가짜 DNS 로 재바인딩(첫 질의 공인 IP, 다음 질의 내부 IP)을 재현해, 접속에 쓰일 해석 결과가 정책으로
 * 걸러지는지 본다. 정책은 운영과 같은 {@link SsrfProtectionService#validateResolvedAddress} 다.
 */
class SsrfGuardedAddressResolverGroupTest {

  private static final String BLOCKED_PREFIX = "내부망 주소는 호출할 수 없습니다";

  private final SsrfProtectionService guard = new SsrfProtectionService();

  private AddressResolver<InetSocketAddress> resolver(
      FakeDnsResolverGroup dns, SsrfGuardedAddressResolverGroup.AddressPolicy policy) {
    // GlobalEventExecutor 는 종료되지 않아 그룹 캐시가 테스트 중 해석기를 닫지 않는다.
    return new SsrfGuardedAddressResolverGroup(dns, policy)
        .getResolver(GlobalEventExecutor.INSTANCE);
  }

  private AddressResolver<InetSocketAddress> resolver(FakeDnsResolverGroup dns) {
    return resolver(dns, guard::validateResolvedAddress);
  }

  private static InetSocketAddress unresolved(String host) {
    return InetSocketAddress.createUnresolved(host, 80);
  }

  @Test
  void rebinding_publicThenLoopback_secondResolutionRejected() {
    FakeDnsResolverGroup dns =
        new FakeDnsResolverGroup().answer("rebind.test", ips("8.8.8.8"), ips("127.0.0.1"));
    AddressResolver<InetSocketAddress> r = resolver(dns);

    // 첫 질의(검사 시점 상당) — 공인 IP 라 통과한다.
    Future<List<InetSocketAddress>> first =
        r.resolveAll(unresolved("rebind.test")).awaitUninterruptibly();
    assertThat(first.isSuccess()).isTrue();
    assertThat(first.getNow())
        .extracting(a -> a.getAddress().getHostAddress())
        .containsExactly("8.8.8.8");

    // 두 번째 질의(접속 시점) — 루프백으로 바뀌었으므로 해석 자체가 실패해 접속할 주소가 남지 않는다.
    Future<List<InetSocketAddress>> second =
        r.resolveAll(unresolved("rebind.test")).awaitUninterruptibly();
    assertThat(second.isSuccess()).isFalse();
    assertThat(second.cause())
        .isInstanceOf(SsrfException.class)
        .hasMessage("내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1");
    assertThat(dns.queries()).isEqualTo(2);
  }

  @Test
  void rebinding_publicThenMetadata_rejectedOnSingleResolveToo() {
    FakeDnsResolverGroup dns =
        new FakeDnsResolverGroup().answer("rebind.test", ips("8.8.8.8"), ips("169.254.169.254"));
    AddressResolver<InetSocketAddress> r = resolver(dns);

    assertThat(r.resolve(unresolved("rebind.test")).awaitUninterruptibly().isSuccess()).isTrue();
    Future<InetSocketAddress> second = r.resolve(unresolved("rebind.test")).awaitUninterruptibly();
    assertThat(second.cause())
        .isInstanceOf(SsrfException.class)
        .hasMessage("내부망 주소는 호출할 수 없습니다(링크 로컬): 169.254.169.254");
  }

  @Test
  void publicAddress_passesWithHostnameAndPortKept() {
    FakeDnsResolverGroup dns =
        new FakeDnsResolverGroup().answer("api.example", ips("8.8.8.8", "1.1.1.1"));

    Future<List<InetSocketAddress>> f =
        resolver(dns)
            .resolveAll(InetSocketAddress.createUnresolved("api.example", 8443))
            .awaitUninterruptibly();

    assertThat(f.isSuccess()).isTrue();
    assertThat(f.getNow()).hasSize(2).allSatisfy(a -> assertThat(a.getPort()).isEqualTo(8443));
  }

  /** 답 중 하나라도 내부 주소면 전체를 거부한다 — 사전 검사(validateUrl)와 같은 기준. */
  @Test
  void mixedAnswer_anyBlockedAddressRejectsAll() {
    FakeDnsResolverGroup dns =
        new FakeDnsResolverGroup().answer("mixed.test", ips("8.8.8.8", "10.0.0.5"));

    Future<List<InetSocketAddress>> f =
        resolver(dns).resolveAll(unresolved("mixed.test")).awaitUninterruptibly();

    assertThat(f.cause())
        .isInstanceOf(SsrfException.class)
        .hasMessage("내부망 주소는 호출할 수 없습니다(사설망): 10.0.0.5");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"::1", "fd00:ec2::254", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "0.0.0.0"})
  void ipv6LoopbackUlaAndMapped_rejected(String literal) {
    FakeDnsResolverGroup dns = new FakeDnsResolverGroup().answer("v6.test", ips(literal));

    Future<List<InetSocketAddress>> f =
        resolver(dns).resolveAll(unresolved("v6.test")).awaitUninterruptibly();

    assertThat(f.cause()).isInstanceOf(SsrfException.class).hasMessageStartingWith(BLOCKED_PREFIX);
  }

  /**
   * JDK 가 Inet4Address 로 바꿔 주지 않은 IPv4 사상 IPv6(::ffff:169.254.169.254 를 Inet6Address 로 직접 만든 것)도 품은
   * IPv4 로 판정해 막는다.
   */
  @Test
  void ipv4MappedIpv6AsInet6Address_rejected() throws UnknownHostException {
    byte[] mapped = new byte[16];
    mapped[10] = (byte) 0xFF;
    mapped[11] = (byte) 0xFF;
    mapped[12] = (byte) 169;
    mapped[13] = (byte) 254;
    mapped[14] = (byte) 169;
    mapped[15] = (byte) 254;
    InetAddress v6 = Inet6Address.getByAddress(null, mapped, -1);
    assertThat(v6).isInstanceOf(Inet6Address.class);
    FakeDnsResolverGroup dns =
        new FakeDnsResolverGroup().answer("mapped.test", new InetAddress[] {v6});

    Future<List<InetSocketAddress>> f =
        resolver(dns).resolveAll(unresolved("mapped.test")).awaitUninterruptibly();

    assertThat(f.cause()).isInstanceOf(SsrfException.class).hasMessageStartingWith(BLOCKED_PREFIX);
  }

  /**
   * IP 리터럴 URL 은 Reactor Netty 가 이미 해석된 주소로 넘긴다 — 「해석됨」이라 답하면 해석기를 건너뛰므로 「해석 안 됨」으로 답하고, DNS 질의 없이
   * 그 주소를 그대로 검사한다.
   */
  @Test
  void alreadyResolvedLiteral_checkedWithoutDnsQuery() {
    FakeDnsResolverGroup dns = new FakeDnsResolverGroup();
    AddressResolver<InetSocketAddress> r = resolver(dns);
    InetSocketAddress literal = new InetSocketAddress(ip("127.0.0.1"), 8080);

    assertThat(r.isResolved(literal)).isFalse();
    Future<List<InetSocketAddress>> f = r.resolveAll(literal).awaitUninterruptibly();

    assertThat(f.cause())
        .isInstanceOf(SsrfException.class)
        .hasMessage("내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1");
    assertThat(dns.queries()).isZero();

    InetSocketAddress publicLiteral = new InetSocketAddress(ip("8.8.8.8"), 443);
    assertThat(r.resolve(publicLiteral).awaitUninterruptibly().getNow()).isEqualTo(publicLiteral);
  }

  /**
   * 정책이 허용하면 내부 주소도 통과한다 — 테스트가 SsrfProtectionService 를 목(아무것도 안 함)으로 바꿔 로컬 목 서버를 허용하면 접속 검사도 같은
   * 정책을 따라야 한다(사전 검사와 접속 검사가 갈리지 않게).
   */
  @Test
  void permissivePolicy_honoured() {
    FakeDnsResolverGroup dns = new FakeDnsResolverGroup().answer("local.test", ips("127.0.0.1"));

    Future<List<InetSocketAddress>> f =
        resolver(dns, address -> {}).resolveAll(unresolved("local.test")).awaitUninterruptibly();

    assertThat(f.isSuccess()).isTrue();
    assertThat(f.getNow().get(0).getAddress().getHostAddress()).isEqualTo("127.0.0.1");
  }

  /** 이름을 못 풀면 DNS 실패 그대로 올린다 — 차단(SsrfException)과 섞지 않는다(「호스트를 찾을 수 없습니다」 문구 유지). */
  @Test
  void dnsFailure_propagatedAsIs() {
    Future<List<InetSocketAddress>> f =
        resolver(new FakeDnsResolverGroup())
            .resolveAll(unresolved("nx.test"))
            .awaitUninterruptibly();

    assertThat(f.cause()).isInstanceOf(UnknownHostException.class);
  }

  /**
   * 느린 비동기 delegate(WD-613 리뷰) — 가드는 delegate 의 완료를 기다리며 막지 않는다. resolveAll 은 해석이 끝나기 전에 미완료 Future
   * 로 바로 돌아오고(이벤트 루프 스레드를 붙잡지 않는다), 다른 스레드에서 해석이 끝난 뒤에야 같은 정책으로 거부된다.
   */
  @Test
  void slowAsyncDelegate_guardDoesNotBlockAndStillRejects() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService dnsThread = Executors.newSingleThreadExecutor();
    try {
      AddressResolverGroup<InetSocketAddress> slowDns =
          new AddressResolverGroup<>() {
            @Override
            protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
              return new AbstractAddressResolver<InetSocketAddress>(
                  executor, InetSocketAddress.class) {
                @Override
                protected boolean doIsResolved(InetSocketAddress address) {
                  return !address.isUnresolved();
                }

                @Override
                protected void doResolve(
                    InetSocketAddress address, Promise<InetSocketAddress> promise) {
                  dnsThread.submit(
                      () -> {
                        release.await();
                        return promise.setSuccess(
                            new InetSocketAddress(ip("127.0.0.1"), address.getPort()));
                      });
                }

                @Override
                protected void doResolveAll(
                    InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
                  dnsThread.submit(
                      () -> {
                        release.await();
                        return promise.setSuccess(
                            List.of(new InetSocketAddress(ip("127.0.0.1"), address.getPort())));
                      });
                }
              };
            }
          };
      AddressResolver<InetSocketAddress> r =
          new SsrfGuardedAddressResolverGroup(slowDns, guard::validateResolvedAddress)
              .getResolver(GlobalEventExecutor.INSTANCE);

      Future<List<InetSocketAddress>> f = r.resolveAll(unresolved("slow.test"));
      // 해석이 끝나지 않았는데도 호출이 바로 돌아왔다 — 가드가 기다리며 스레드를 막지 않는다.
      assertThat(f.isDone()).isFalse();

      release.countDown();
      assertThat(f.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(f.cause())
          .isInstanceOf(SsrfException.class)
          .hasMessage("내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1");
    } finally {
      dnsThread.shutdownNow();
    }
  }
}
