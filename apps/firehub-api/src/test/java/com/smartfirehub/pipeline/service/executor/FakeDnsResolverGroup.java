package com.smartfirehub.pipeline.service.executor;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 가짜 DNS(WD-613) — 호스트마다 「호출 순서대로 돌려줄 답」을 심어 둔다. 공격자 DNS(TTL 0)가 첫 질의엔 공인 IP, 다음 질의엔 루프백·메타데이터
 * IP 를 주는 재바인딩을 실제 네트워크 없이 재현한다. 마지막 답은 이후 질의에도 계속 쓴다.
 */
final class FakeDnsResolverGroup extends AddressResolverGroup<InetSocketAddress> {

  private final Map<String, Deque<InetAddress[]>> answers = new HashMap<>();
  private final AtomicInteger queries = new AtomicInteger();

  /** {@code host} 에 대한 답을 질의 순서대로 심는다. 각 인자는 한 번의 질의에 돌려줄 주소 목록이다. */
  synchronized FakeDnsResolverGroup answer(String host, InetAddress[]... inOrder) {
    answers.put(host, new ArrayDeque<>(Arrays.asList(inOrder)));
    return this;
  }

  /** 지금까지 받은 이름 질의 수 — 해석기를 실제로 거쳤는지 확인용. */
  int queries() {
    return queries.get();
  }

  private synchronized InetAddress[] next(String host) throws UnknownHostException {
    queries.incrementAndGet();
    Deque<InetAddress[]> q = answers.get(host);
    if (q == null || q.isEmpty()) {
      throw new UnknownHostException(host);
    }
    return q.size() > 1 ? q.pollFirst() : q.peekFirst();
  }

  @Override
  protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
    InetNameResolver names =
        new InetNameResolver(executor) {
          @Override
          protected void doResolve(String host, Promise<InetAddress> promise) {
            try {
              promise.setSuccess(next(host)[0]);
            } catch (UnknownHostException e) {
              promise.setFailure(e);
            }
          }

          @Override
          protected void doResolveAll(String host, Promise<List<InetAddress>> promise) {
            try {
              promise.setSuccess(List.of(next(host)));
            } catch (UnknownHostException e) {
              promise.setFailure(e);
            }
          }
        };
    return new InetSocketAddressResolver(executor, names);
  }

  /** 숫자 리터럴로 주소를 만든다(DNS 조회 없음). */
  static InetAddress ip(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(literal, e);
    }
  }

  static InetAddress[] ips(String... literals) {
    return Arrays.stream(literals).map(FakeDnsResolverGroup::ip).toArray(InetAddress[]::new);
  }
}
