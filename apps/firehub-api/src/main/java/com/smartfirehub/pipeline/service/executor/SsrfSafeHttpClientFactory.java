package com.smartfirehub.pipeline.service.executor;

import io.netty.resolver.AddressResolverGroup;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.netty.http.HttpResources;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.NameResolverProvider;

/**
 * 사용자가 정한 주소로 나가는 HTTP 호출용 Reactor Netty {@link HttpClient} 를 만드는 공용 팩토리(WD-613).
 *
 * <p>여기서 만든 클라이언트는 접속 직전 해석한 IP 를 {@link SsrfGuardedAddressResolverGroup} 으로 검사한다 — {@link
 * SsrfProtectionService#validateUrl} 사전 검사 뒤 DNS 가 내부 주소로 바뀌어도(DNS 재바인딩) 접속하지 않는다. 사전 검사는 그대로 둔다(이른
 * 실패와 친절한 문구용). 이 팩토리가 실제 집행 지점이다.
 *
 * <p>SsrfProtectionService 로 지키는 모든 바깥 호출(API_CALL 실행·미리보기, API 연결 테스트, opencode 프로브)이 이 팩토리의
 * 클라이언트를 쓴다. 차단 정책은 주입된 {@link SsrfProtectionService#validateResolvedAddress} 를 그대로 따른다 — 사전 검사와 접속
 * 검사가 같은 판정을 쓰고, 테스트가 그 빈을 목으로 바꿔 로컬 목 서버를 허용하면 접속 검사도 같이 허용된다.
 *
 * <p>해석기 그룹은 인스턴스당 하나만 만든다 — {@link AddressResolverGroup} 은 이벤트 루프마다 해석기를 캐시하고 종료 리스너를 걸어 두므로, 요청마다
 * 새로 만들면 쌓인다. 반환하는 {@link HttpClient} 는 매번 새로 만들어도 기본 연결 풀·이벤트 루프(전역 자원)를 공유한다.
 *
 * <p><b>전역 루프 자원이 바뀌면 해석기도 다시 만든다(WD-791).</b> Spring 컨텍스트가 닫히면 {@code ReactorResourceFactory} 가 전역
 * {@link HttpResources} 를 폐기하고, 다음 {@link HttpResources#get()} 이 새 자원을 만든다. 기본 해석기 그룹은 만들 때 그 자원의
 * 이벤트 루프 하나를 빌더에 넣어 두고, Boot 3.5 의 Reactor Netty 1.2.18·Netty 조합은 해석에 그 루프를 쓴다. 그래서 옛 자원으로 만든 그룹을
 * 계속 쓰면 이름 해석이 종료된 이벤트 루프에 걸려 접속도 차단도 못 하고 시간 초과로 끝난다(전체 시험에서 컨텍스트가 하나 닫힌 뒤 opencode 프로브·API 호출 시험이
 * 줄줄이 실패했다). 운영에서도 컨텍스트 재시작(lifecycle stop·start) 뒤 같은 일이 난다. 그래서 기본 해석기를 「지금 전역 자원」에 묶어 두고, 자원이
 * 바뀌었으면 새로 만든다.
 */
@Component
public class SsrfSafeHttpClientFactory {

  /** 고정 해석기(테스트의 가짜 DNS) — null 이면 지금 전역 자원의 기본 해석기({@link DefaultDns#current()})를 쓴다. */
  private final AddressResolverGroup<InetSocketAddress> fixedDns;

  private final SsrfGuardedAddressResolverGroup.AddressPolicy policy;

  /** 마지막으로 만든 검사 해석기 그룹 — delegate 가 바뀌면(전역 자원 교체) {@link #resolverGroup()} 이 새로 만든다. */
  private SsrfGuardedAddressResolverGroup resolverGroup;

  /**
   * 실제 이름 해석을 맡길 비동기 DNS 해석기 그룹 — {@code HttpClient.create()} 의 기본 해석기와 같은 방식으로 만든다(WD-613 리뷰).
   *
   * <p><b>왜 Netty 의 DefaultAddressResolverGroup(JDK 해석기)을 쓰지 않는가.</b> 그것은 {@code
   * InetAddress.getAllByName} 을 이벤트 루프 스레드에서 동기로 부른다. 사용자가 응답이 느리거나 막힌 DNS 이름을 넣으면 공유 이벤트 루프가 조회마다
   * 수 초씩 멈춰 같은 루프를 쓰는 다른 WebClient(AI 에이전트 SSE 프록시 등)까지 멈춘다. Reactor Netty 기본값은 비동기 Netty DNS 해석기라
   * 그런 일이 없었다.
   *
   * <p><b>어떻게 기본값과 맞추는가.</b> Reactor Netty 는 기본 해석기를 {@code NameResolverProvider.builder().build()
   * .newNameResolverGroup(전역 루프 자원, LoopResources.DEFAULT_NATIVE)} 로 만든다(TcpResources). 같은 호출을 그대로
   * 쓴다 — 전역 루프 자원({@link HttpResources})이 epoll/nio 에 맞는 UDP·TCP 채널 종류를 고르고, /etc/hosts·시스템
   * resolv.conf·캐시 TTL 등 설정도 기본값과 같다. Reactor Netty 내부의 기본 해석기 인스턴스는 공개 API 로 꺼낼 수 없어 같은 설정의 별도
   * 인스턴스를 둔다(전역 자원 하나에 하나, 요청마다 만들지 않는다).
   */
  static final class DefaultDns {

    /** 어느 전역 자원으로 만든 해석기 그룹인지 함께 둔다 — 자원이 바뀌었는지 같은 객체인지로 가른다. */
    private record Bound(HttpResources resources, AddressResolverGroup<InetSocketAddress> group) {}

    private static final AtomicReference<Bound> CURRENT = new AtomicReference<>();

    private DefaultDns() {}

    /**
     * 지금 전역 자원({@link HttpResources#get()})으로 만든 기본 해석기 그룹. 자원이 그대로면 같은 그룹을 돌려주고(앱 전체에서 하나), 폐기 뒤 새
     * 자원이 생겼으면 새로 만들고 옛 그룹을 닫는다 — 옛 그룹의 해석기는 이미 종료된 이벤트 루프에 묶여 쓸 수 없다.
     *
     * <p>WD-804: 옛 그룹을 닫아도 진행 중인 해석을 끊지 않는다는 것은 「새 전역 자원은 옛 자원의 루프가 종료된 뒤에야 생긴다」는 가정에 기댄다. 이 가정을
     * 지키는 경로는 Spring 컨텍스트 종료의 {@code ReactorResourceFactory.stop()}(전역 자원 폐기를 {@code block()} 으로
     * 기다려 루프가 종료된 뒤에야 다음 {@code HttpResources.get()} 이 새 자원을 만든다)뿐이다. {@code HttpResources.reset()}
     * 이나 {@code HttpResources.disposeLoopsAndConnectionsLater()} 를 기다리지 않고 부르는 경로로 자원을 바꾸면 옛 루프가 아직
     * 도는 동안 새 자원이 생겨 여기서 옛 그룹을 닫을 수 있고, 그때 옛 루프에서 진행 중이던 해석은 실패할 수 있다. 지금 앱·시험 코드는 그런 경로를 부르지
     * 않는다(전역 자원을 폐기하는 시험도 {@code ReactorResourceFactory.stop()} 을 쓴다).
     */
    static AddressResolverGroup<InetSocketAddress> current() {
      HttpResources resources = HttpResources.get();
      Bound bound = CURRENT.get();
      if (bound != null && bound.resources() == resources) {
        return bound.group();
      }
      synchronized (DefaultDns.class) {
        bound = CURRENT.get();
        if (bound != null && bound.resources() == resources) {
          return bound.group();
        }
        Bound fresh =
            new Bound(
                resources,
                NameResolverProvider.builder()
                    .build()
                    .newNameResolverGroup(resources, LoopResources.DEFAULT_NATIVE));
        CURRENT.set(fresh);
        if (bound != null) {
          bound.group().close();
        }
        return fresh.group();
      }
    }
  }

  /** 운영 경로 — 사전 검사와 같은 {@link SsrfProtectionService} 판정으로 접속 주소를 검사한다. */
  @Autowired
  public SsrfSafeHttpClientFactory(SsrfProtectionService ssrfProtectionService) {
    this(ssrfProtectionService::validateResolvedAddress);
  }

  /**
   * 정책을 직접 주는 생성자 — 차단 문구를 자기 것으로 바꿔야 하는 호출부(opencode 프로브: 해석 IP 를 노출하지 않는다)와 테스트용.
   *
   * @param policy 해석된 주소마다 부르는 차단 정책. 막을 때는 {@link SsrfException} 을 던진다.
   */
  public SsrfSafeHttpClientFactory(SsrfGuardedAddressResolverGroup.AddressPolicy policy) {
    this.fixedDns = null;
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  /** 테스트 전용 — 실제 DNS 대신 가짜 해석기(재바인딩 재현)를 끼운다. */
  SsrfSafeHttpClientFactory(
      AddressResolverGroup<InetSocketAddress> dns,
      SsrfGuardedAddressResolverGroup.AddressPolicy policy) {
    this.fixedDns = Objects.requireNonNull(dns, "dns");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  /**
   * 지금 쓸 검사 해석기 그룹. delegate(기본이면 지금 전역 자원의 해석기)가 마지막 그룹과 같으면 그대로 쓰고, 바뀌었으면 새로 만들고 옛 그룹을 닫는다 —
   * 인스턴스당 그룹 하나를 유지하되(요청마다 만들면 이벤트 루프별 해석기·종료 리스너가 쌓인다) 전역 자원 교체는 따라간다.
   *
   * <p>WD-804: 여기서 옛 검사 그룹을 {@code close()} 해도 진행 중인 해석은 영향이 없다. 그룹 close 는 캐시한 해석기를 비우고 닫을 뿐이고,
   * 캐시된 해석기({@code GuardedResolver})의 close 는 아무 일도 하지 않는다(진행 중 해석은 이미 해석기·delegate 참조를 쥐고 있다). 실제
   * delegate 를 닫는 곳은 {@link DefaultDns#current()} 뿐이다.
   */
  private synchronized SsrfGuardedAddressResolverGroup resolverGroup() {
    AddressResolverGroup<InetSocketAddress> dns =
        fixedDns != null ? fixedDns : DefaultDns.current();
    SsrfGuardedAddressResolverGroup group = resolverGroup;
    if (group == null || group.delegate() != dns) {
      if (group != null) {
        group.close();
      }
      group = new SsrfGuardedAddressResolverGroup(dns, policy);
      resolverGroup = group;
    }
    return group;
  }

  /** 테스트 확인용 — 실제 이름 해석을 맡긴 delegate 그룹. */
  AddressResolverGroup<InetSocketAddress> dnsDelegate() {
    return resolverGroup().delegate();
  }

  /**
   * 접속 시점 주소 검사가 걸린 새 {@link HttpClient}. {@code HttpClient.create()} 와 같은 기본값(전역 연결 풀·이벤트 루프)에
   * 해석기만 바꾼 것이라, 호출부는 예전처럼 리다이렉트·타임아웃·압축 등을 이어서 설정한다.
   *
   * <p><b>돌려준 클라이언트를 필드에 오래 두지 않는다(WD-797).</b> 클라이언트에는 부른 시점의 검사 그룹이 들어가므로, 전역 루프 자원이 바뀐 뒤(컨텍스트
   * 재시작)에는 종료된 루프의 해석기에 걸린다. 호출부는 요청마다 이 메서드로 새로 만든다(예: {@code ApiCallPreviewService.client()}). 들고
   * 있는 클라이언트도 따라가게 그룹을 고정하고 delegate 를 실행기마다 찾는 방식은 리뷰에서 실행기 재사용 시 닫힌 delegate 고착·이벤트 루프 스레드에서의 전역
   * 자원 생성 위험이 나와 하지 않았다.
   *
   * <p>WD-804: 지금 호출처({@code ApiConnectionService.healthCheckClient}, {@code
   * ApiCallPreviewService.client}, {@code ApiCallExecutor.noRedirectClient}, {@code
   * OpencodeProbeService.doRequest})는 모두 요청마다 이 메서드를 불러 새로 만들므로 낡은 그룹에 묶일 위험은 없다. 다만 이것은 「낡은 그룹」에
   * 대한 말일 뿐 호출처가 스레드 안전하다는 뜻은 아니다 — {@code OpencodeProbeService.doRequest} 는 받은 클라이언트를 주입받은 공유
   * {@code WebClient.Builder} 필드에 {@code clone()} 없이 써 넣고 build 하므로, 동시에 도는 프로브끼리 서로의
   * 커넥터·exchangeStrategies 로 만들어질 수 있다(같은 설정이라 지금은 드러나지 않지만 요청마다 다른 값을 넣으면 깨진다, {@link
   * RedirectFollower#withoutAutoRedirect} 는 그래서 복제한다). 별도 이슈(WD-808)로 다룬다.
   *
   * <p>근본 해결(기본 해석기 + {@code resolvedAddressesSelector})은 설계(WD-797)에서 미루기로 했던 것으로, reactor-netty
   * 1.2.18 에서 IP 리터럴 경로의 selector 예외가 채널을 닫지 않아(소켓 누수 가능) 하지 않았다. WD-792(Boot 4 / reactor-netty
   * 1.3)에서 다시 검토한다.
   */
  public HttpClient httpClient() {
    return HttpClient.create().resolver(resolverGroup());
  }
}
