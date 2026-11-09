package com.smartfirehub.pipeline.service.executor;

import io.netty.resolver.AddressResolverGroup;
import java.net.InetSocketAddress;
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
 */
@Component
public class SsrfSafeHttpClientFactory {

  private final SsrfGuardedAddressResolverGroup resolverGroup;

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
   * 인스턴스를 하나 둔다(앱 전체에서 하나, 요청마다 만들지 않는다).
   */
  private static final class DefaultDns {
    static final AddressResolverGroup<InetSocketAddress> INSTANCE =
        NameResolverProvider.builder()
            .build()
            .newNameResolverGroup(HttpResources.get(), LoopResources.DEFAULT_NATIVE);
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
    this(DefaultDns.INSTANCE, policy);
  }

  /** 테스트 전용 — 실제 DNS 대신 가짜 해석기(재바인딩 재현)를 끼운다. */
  SsrfSafeHttpClientFactory(
      AddressResolverGroup<InetSocketAddress> dns,
      SsrfGuardedAddressResolverGroup.AddressPolicy policy) {
    this.resolverGroup = new SsrfGuardedAddressResolverGroup(dns, policy);
  }

  /** 테스트 확인용 — 실제 이름 해석을 맡긴 delegate 그룹. */
  AddressResolverGroup<InetSocketAddress> dnsDelegate() {
    return resolverGroup.delegate();
  }

  /**
   * 접속 시점 주소 검사가 걸린 새 {@link HttpClient}. {@code HttpClient.create()} 와 같은 기본값(전역 연결 풀·이벤트 루프)에
   * 해석기만 바꾼 것이라, 호출부는 예전처럼 리다이렉트·타임아웃·압축 등을 이어서 설정한다.
   */
  public HttpClient httpClient() {
    return HttpClient.create().resolver(resolverGroup);
  }
}
