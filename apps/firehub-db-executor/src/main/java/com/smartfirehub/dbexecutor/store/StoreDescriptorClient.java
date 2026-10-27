package com.smartfirehub.dbexecutor.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.config.DbExecutorProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * api 에서 테넌트 저장소 기술자를 조회하고 캐시한다(Python executor {@code StoreClient} 와 같은 동작).
 *
 * <ul>
 *   <li>성공(200 + backend=TIBERO)만 TTL 동안 캐시한다 — 실패를 캐시하면 복구가 늦어진다.
 *   <li>테넌트별 단일 조회(single-flight): 같은 테넌트의 동시 요청은 원격 호출 하나를 같이 기다린다.
 *   <li>fail-closed: 어떤 이유로든 못 얻으면 {@link StoreUnavailableException}. PostgreSQL 테넌트가 여기로 오는 것도
 *       거부한다.
 *   <li>시간 상한: 원격 조회 한 번은 최대 {@link #MAX_FETCH}(접속 포함)이고, 호출자가 준 남은 시간(요청 기한)이 더 짧으면 그만큼만 기다린다 —
 *       기술자 조회도 요청 전체 기한 안에 있어야 api 가 포기하기 전에 실행기가 끝난다.
 *   <li>무효화: 커넥션 대여(로그인)가 실패하면 실행기가 {@link #invalidate} 로 캐시를 지운다 — 비밀번호가 바뀐 뒤 TTL 동안 옛 기술자로 계속
 *       실패하지 않게.
 *   <li>스크립트 토큰 세대는 별도 경량 창구에서 5초 동안만 캐시한다. 접속 기술자는 일반 TTL(60초)을 유지한다.
 *   <li>요청 범위: 언제나 {@code ?include=ro,slots} 로 받는다(RO·슬롯 계정 포함). 한 캐시를 모든 경로가 같이 쓰기 위해서다.
 *   <li>세대(WD-263): 기술자 캐시의 테넌트 칸은 「세대 번호」를 같이 들고 있고 무효화·강제 갱신마다 1 씩 올라간다. 원격 조회는 요청을 보내기 <b>전에</b>
 *       세대를 읽고, 결과를 캐시에 넣을 때 그 세대가 아직 현재 세대일 때만 넣는다 — 무효화 전에 보낸 조회가 늦게 끝나 옛 값으로 캐시를 덮지 않게. 세대는 캐시 칸
 *       안에 있어(무효화는 칸을 지우지 않고 「값 없음 + 다음 세대」 표지로 바꾼다) 테넌트별 자료 구조를 따로 두지 않는다.
 *   <li>강제 갱신({@link #refresh}): 무효화 이후에 시작된 조회의 결과를 돌려준다(새 슬롯 계정 직후 재확인용). 동시 강제 갱신은 원격 조회 하나로
 *       묶는다.
 * </ul>
 */
@Component
public class StoreDescriptorClient {

  private static final Logger log = LoggerFactory.getLogger(StoreDescriptorClient.class);

  /**
   * 캐시 항목(기술자·세대 공용). {@code fetchedAtNanos} 는 그 값을 받으려고 원격 조회를 <b>시작한</b> 시각이다 — 기술자 캐시는 늦게 끝난 옛
   * 조회가 더 새로 시작한 조회의 결과를 덮지 않게 갱신({@code cache.compute})이 이 시각으로 순서를 가린다. {@code generation} 은 기술자
   * 캐시의 세대 번호다(클래스 설명 — 세대 캐시는 늘 0).
   */
  private record Cached<T>(T value, long fetchedAtNanos, long expiresAtNanos, long generation) {

    /**
     * 무효화 표지 — 값은 없고(신선한 값으로 보이지 않음) 세대만 다음 번호로 올린다. 칸을 지우지 않는 이유: 지우면 세대가 0 으로 돌아가 무효화 전에 시작된
     * 조회(세대 0)가 캐시를 다시 채울 수 있다.
     */
    static <T> Cached<T> invalidated(long generation) {
      return new Cached<>(null, Long.MIN_VALUE, Long.MIN_VALUE, generation);
    }
  }

  /**
   * 진행 중인 원격 조회 하나. {@code refresh} 는 강제 갱신이 시작한 조회인지, {@code generation} 은 그 강제 갱신이 올린 세대다(일반 조회는
   * 0 — 강제 갱신의 합류 판단에만 쓴다). 진행 표시를 지울 때는 맵의 {@code remove(key, value)} 로 <b>자기 것</b>만 지운다 — future
   * 는 조회마다 새로 만들고 동일성으로 비교되므로, 갈아 끼워진 뒤의 새 표시를 옛 조회가 지우지 않는다.
   */
  private record Flight<T>(CompletableFuture<T> future, long generation, boolean refresh) {}

  /** 원격 조회 한 번 — 남은 대기 시간({@code waitNanos}) 안에 값을 얻어 자기 캐시에 넣고 돌려준다. */
  @FunctionalInterface
  private interface Fetcher<T> {
    T fetch(long tenantId, long waitNanos);
  }

  /** 세대 캐시 수명. */
  private static final Duration EPOCH_TTL = Duration.ofSeconds(5);

  /** 원격 조회 한 번의 최대 대기(접속·응답 포함). 호출자의 남은 시간이 더 짧으면 그 값을 쓴다. */
  static final Duration MAX_FETCH = Duration.ofSeconds(10);

  /** 남은 시간 안에 기술자를 얻지 못했을 때의 상태 표지. */
  static final String TIMED_OUT = "시간 초과";

  /** 원격 조회가 접속·전송 단계에서 실패했을 때의 상태 표지(원인 메시지에는 주소가 들어 있어 싣지 않는다). */
  static final String CONNECT_FAILED = "연결 실패";

  /** 기술자 조회 로그 이름. */
  private static final String DESCRIPTOR_LABEL = "테넌트 저장소 기술자";

  /** 세대 조회 로그 이름. */
  private static final String EPOCH_LABEL = "스크립트 토큰 세대";

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final String baseUrl;
  private final String token;
  private final long ttlNanos;
  private final LongSupplier nanoClock;
  private final ConcurrentHashMap<Long, Cached<StoreDescriptor>> cache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Cached<Long>> epochCache = new ConcurrentHashMap<>();

  /**
   * 진행 중인 테넌트별 기술자 조회 — 같은 테넌트의 동시 요청은 이 future 하나를 각자 남은 시간까지만 기다린다. 잠금 순서: {@link #refresh} 는 이
   * 맵의 {@code compute} 안에서 {@link #cache} 의 {@code compute} 를 부른다. 반대로 {@link #cache} 칸을 잡은 채 이 맵을
   * 건드리는 코드는 두지 않는다(교착 방지). 두 맵의 람다 안에서는 원격 호출을 하지 않는다.
   */
  private final ConcurrentHashMap<Long, Flight<StoreDescriptor>> inflight =
      new ConcurrentHashMap<>();

  /** 진행 중인 테넌트별 세대 조회 — 같은 테넌트의 동시 요청은 이 future 하나를 각자 남은 시간까지만 기다린다(세대 번호는 쓰지 않는다). */
  private final ConcurrentHashMap<Long, Flight<Long>> epochInflight = new ConcurrentHashMap<>();

  @Autowired
  public StoreDescriptorClient(DbExecutorProperties props, ObjectMapper mapper) {
    this(props, mapper, System::nanoTime);
  }

  /** 테스트용: 시계를 주입해 TTL 만료를 재현한다. */
  StoreDescriptorClient(DbExecutorProperties props, ObjectMapper mapper, LongSupplier nanoClock) {
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.mapper = mapper;
    this.baseUrl = props.apiBaseUrl().replaceAll("/+$", "");
    this.token = props.internalToken();
    this.ttlNanos = Duration.ofSeconds(props.descriptorTtlSeconds()).toNanos();
    this.nanoClock = nanoClock;
  }

  /** 테넌트의 Tibero 접속 기술자를 돌려준다(대기 상한 {@link #MAX_FETCH}). */
  public StoreDescriptor get(long tenantId) {
    return get(tenantId, MAX_FETCH);
  }

  /**
   * 테넌트의 Tibero 접속 기술자를 돌려준다. 캐시에 없으면 원격 조회(또는 진행 중인 같은 테넌트 조회)를 {@code budget} 과 {@link
   * #MAX_FETCH} 중 짧은 시간까지만 기다린다.
   *
   * @param budget 요청 기한까지 남은 시간
   * @throws StoreUnavailableException 못 얻었거나 남은 시간 안에 끝나지 않았을 때(「…(시간 초과).」)
   */
  public StoreDescriptor get(long tenantId, Duration budget) {
    return singleFlight(tenantId, budget, cache, inflight, this::fetch);
  }

  /**
   * 무효화 이후에 시작된 원격 조회 결과를 돌려준다. 그 전에 시작된 진행 중 조회에는 합류하지 않고, 그 결과가 늦게 끝나도 캐시를 덮지 않는다. 동시 호출은 한 번으로
   * 묶는다(WD-263 — 새 슬롯 계정을 만든 직후 캐시된 옛 기술자에 슬롯이 없을 때 한 번 다시 받는 길).
   *
   * <p>무효화를 겸한다 — 호출자가 따로 {@link #invalidate} 를 부르지 않는다. 따로 부르면 동시 요청 A·B 에서 B 의 무효화가 A 의 진행 중 강제
   * 갱신을 「옛 조회」로 만들어 원격 조회가 요청 수만큼 늘어난다. 합류·시작 판단은 {@code inflight.compute} 한 번 안에서 원자적으로 한다.
   *
   * <ul>
   *   <li>현재 세대를 올린 강제 갱신이 진행 중이면 합류한다. 안전한 이유: 호출자가 들고 있는 옛 기술자는 그 세대 증가 전에 캐시에서 읽었거나(합류할 조회는 증가
   *       뒤에 시작됐다), 그 강제 갱신이 갈아 끼운 더 오래된 조회에서 받았다 — 어느 쪽이든 합류할 조회가 더 늦게 시작했다.
   *   <li>아니면(진행 중 조회가 없거나, 일반 조회거나, 그 뒤 다른 무효화로 세대가 바뀜) 세대를 올리고(무효화) 자기 조회를 진행 표시로 갈아 끼운다. 갈아 끼워진
   *       옛 조회는 계속 돌아 자기 대기자에게 결과를 주지만, 세대가 맞지 않아 캐시에는 못 넣는다.
   * </ul>
   *
   * <p>캐시를 다시 확인하지 않는다 — 신선한 캐시 값이 바로 다시 확인하려는 옛 값일 수 있다. 조회가 끝난 뒤 늦게 온 강제 갱신은 원격 조회를 한 번 더 한다(스펙
   * §6.2 가 받아들인 비용, 쿨다운 없음).
   *
   * <p>진행 표시를 건 뒤 {@code fetch} 가 세대를 읽기 전에 다른 {@link #invalidate} 가 끼면, 뒤이은 {@code refresh} 는 그
   * 표시에 합류하지 못하고 원격 조회가 하나 더 생긴다(정확성 문제는 없다 — 비용만 한 번 더 든다).
   *
   * @param wait 요청 기한까지 남은 시간({@link #MAX_FETCH} 와 짧은 쪽까지 기다린다)
   * @throws StoreUnavailableException 못 얻었거나 남은 시간 안에 끝나지 않았을 때 — {@link #get(long, Duration)} 과 같다
   */
  public StoreDescriptor refresh(long tenantId, Duration wait) {
    long waitNanos = Math.min(wait.toNanos(), MAX_FETCH.toNanos());
    if (waitNanos <= 0) {
      // 남은 시간이 없으면 캐시도 건드리지 않고 거부한다(무효화만 하고 못 받아 오면 다음 요청까지 손해).
      throw new StoreUnavailableException(TIMED_OUT);
    }
    CompletableFuture<StoreDescriptor> mine = new CompletableFuture<>();
    Flight<StoreDescriptor> flight =
        inflight.compute(
            tenantId,
            (k, existing) -> {
              if (existing != null
                  && existing.refresh()
                  && existing.generation() == generation(k)) {
                return existing;
              }
              // 람다 안에서는 세대만 올린다(cache 칸 잠금은 짧다) — 원격 호출은 밖에서.
              return new Flight<>(mine, advanceGeneration(k), true);
            });
    if (flight.future() != mine) {
      return await(flight.future(), waitNanos);
    }
    return lead(tenantId, waitNanos, flight, inflight, this::fetch);
  }

  /**
   * 이 테넌트의 캐시된 기술자를 버린다 — 다음 요청이 api 에서 새로 받아 온다(비밀번호 교체 직후 로그인 실패 대응). 세대를 올리므로 지금 진행 중인 조회가 늦게
   * 끝나도 옛 값을 캐시에 다시 넣지 못한다(WD-263).
   */
  public void invalidate(long tenantId) {
    advanceGeneration(tenantId);
    epochCache.remove(tenantId);
  }

  /** 기술자 캐시 칸의 현재 세대(칸이 없으면 0). */
  private long generation(long tenantId) {
    Cached<StoreDescriptor> c = cache.get(tenantId);
    return c == null ? 0L : c.generation();
  }

  /** 세대를 하나 올리고 캐시 값을 버린다(칸 잠금 안에서 원자적으로) — 올린 세대를 돌려준다. */
  private long advanceGeneration(long tenantId) {
    return cache
        .compute(
            tenantId, (k, old) -> Cached.invalidated((old == null ? 0L : old.generation()) + 1))
        .generation();
  }

  /**
   * 스크립트 토큰 세대만 별도 창구에서 확인한다. 실패·null·형식 오류는 캐시하지 않고 예외로 거부한다(만료된 옛 값도 내주지 않는다) — 값이 없는 경우가 없으므로
   * {@code long} 이다.
   *
   * <p>같은 테넌트의 동시 요청은 기술자 경로와 같은 single-flight({@link #singleFlight})로 합친다 — 첫 요청만 원격 조회를 하고, 나머지는
   * 그 future 를 <b>자기 남은 시간까지만</b> 기다린다. 원격 호출은 맵 잠금 밖에서 한다: {@code ConcurrentHashMap.compute} 안에서
   * 기다리면 같은 칸에 해시되는 다른 테넌트와 {@link #invalidate} 까지 막히고, 대기자가 자기 기한을 넘긴다(리뷰 I-3).
   *
   * @throws StoreUnavailableException 못 얻었거나 남은 시간 안에 끝나지 않았을 때 — 호출자는 「기술자를 얻지 못함」 거부로 돌려준다(401
   *     아님)
   */
  public long getScriptTokenEpoch(long tenantId, Duration budget) {
    return singleFlight(tenantId, budget, epochCache, epochInflight, this::fetchEpoch);
  }

  /**
   * 테넌트별 단일 조회(single-flight) 골격 — 기술자·세대 두 경로가 함께 쓴다. 신선한 캐시가 있으면 그것을, 같은 테넌트 조회가 진행 중이면 그 future
   * 를 남은 시간까지만 기다리고, 아니면 이 호출이 원격 조회를 맡는다(선행 조회가 방금 캐시를 채웠으면 그 값을 쓴다). 맡은 쪽은 성공·실패 어느 쪽이든 future 를
   * 반드시 끝내고 진행 표시를 지운다.
   */
  private <T> T singleFlight(
      long tenantId,
      Duration budget,
      ConcurrentHashMap<Long, Cached<T>> cacheMap,
      ConcurrentHashMap<Long, Flight<T>> inflightMap,
      Fetcher<T> fetcher) {
    T hit = fresh(cacheMap, tenantId);
    if (hit != null) {
      return hit;
    }
    long waitNanos = Math.min(budget.toNanos(), MAX_FETCH.toNanos());
    if (waitNanos <= 0) {
      throw new StoreUnavailableException(TIMED_OUT);
    }
    // 일반 조회는 어떤 진행 중 조회에든 합류한다(강제 갱신이 시작한 것 포함) — 기존 동작.
    Flight<T> mine = new Flight<>(new CompletableFuture<>(), 0L, false);
    Flight<T> existing = inflightMap.putIfAbsent(tenantId, mine);
    if (existing != null) {
      return await(existing.future(), waitNanos);
    }
    return lead(
        tenantId,
        waitNanos,
        mine,
        inflightMap,
        (id, wait) -> {
          // 선행 조회가 방금 끝나 캐시를 채웠을 수 있다(경합) — 다시 확인.
          T again = fresh(cacheMap, id);
          return again != null ? again : fetcher.fetch(id, wait);
        });
  }

  /**
   * 진행 표시를 맡은 쪽의 원격 조회. 성공·실패 어느 쪽이든 future 를 반드시 끝내고(대기자가 영원히 막히지 않게) 자기 진행 표시만 지운다 — 강제 갱신이 이미 갈아
   * 끼웠으면 그 새 표시는 남긴다.
   */
  private static <T> T lead(
      long tenantId,
      long waitNanos,
      Flight<T> mine,
      ConcurrentHashMap<Long, Flight<T>> inflightMap,
      Fetcher<T> fetcher) {
    try {
      T result = fetcher.fetch(tenantId, waitNanos);
      mine.future().complete(result);
      return result;
    } catch (RuntimeException | Error e) {
      // Error 까지 잡아 대기자를 반드시 깨운다(안 그러면 join 이 영원히 막힌다).
      mine.future().completeExceptionally(e);
      throw e;
    } finally {
      inflightMap.remove(tenantId, mine);
    }
  }

  /** 아직 만료되지 않은 캐시 값(없으면 null). */
  private <T> T fresh(ConcurrentHashMap<Long, Cached<T>> cacheMap, long tenantId) {
    Cached<T> c = cacheMap.get(tenantId);
    // 무효화 표지(값 없음)는 신선한 값이 아니다.
    if (c != null && c.value() != null && nanoClock.getAsLong() < c.expiresAtNanos()) {
      return c.value();
    }
    return null;
  }

  /** 내부 토큰을 붙여 세대만 읽고 5초 캐시에 넣는다. 비정상 응답 본문은 예외·로그에 싣지 않는다. */
  private Long fetchEpoch(long tenantId, long waitNanos) {
    // 신선도 기준은 요청을 보내기 전 시각이다(기술자 캐시와 같은 보수적 기준).
    long started = nanoClock.getAsLong();
    HttpResponse<String> res =
        send(
            "/internal/executor/tenants/" + tenantId + "/script-token-epoch",
            waitNanos,
            tenantId,
            EPOCH_LABEL);
    long value;
    try {
      JsonNode node = mapper.readTree(res.body()).path("scriptTokenEpoch");
      if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
        throw new IOException("scriptTokenEpoch 형식 오류");
      }
      value = node.longValue();
    } catch (IOException e) {
      // 세대 조회 실패는 스크립트 요청 거부로 이어진다 — 운영자가 원인을 볼 수 있게 사유만 남긴다(리뷰 M-9, 비밀·본문 없음).
      log.warn("{} 조회 실패 — 스크립트 요청을 거부합니다 tenant={} 사유=응답 형식 오류", EPOCH_LABEL, tenantId);
      throw new StoreUnavailableException("응답 형식 오류");
    }
    // 세대 캐시는 세대 번호를 쓰지 않는다(0) — 무효화는 칸을 그냥 지운다.
    epochCache.put(tenantId, new Cached<>(value, started, started + EPOCH_TTL.toNanos(), 0L));
    return value;
  }

  /** 진행 중인 같은 테넌트 조회(기술자·세대)를 남은 시간까지만 기다린다. 선행 조회의 예외는 그대로 다시 던진다. */
  private static <T> T await(CompletableFuture<T> f, long waitNanos) {
    try {
      // 선행 조회의 future 는 그 호출자 몫이라 포기해도 취소하지 않는다.
      return awaitWithin(f, waitNanos, false);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re;
      }
      if (e.getCause() instanceof Error err) {
        throw err;
      }
      throw new StoreUnavailableException(CONNECT_FAILED);
    }
  }

  /**
   * {@code future} 를 {@code waitNanos} 까지만 기다린다. 시간 초과는 {@link #TIMED_OUT}, 인터럽트는 인터럽트 표시를 되살리고
   * {@link #CONNECT_FAILED} 로 바꿔 던진다({@code cancelOnGiveUp} 이면 그 전에 future 를 취소한다). 작업 자체의
   * 실패({@link ExecutionException})는 사유를 가르는 방법이 호출자마다 달라 그대로 넘긴다.
   */
  private static <T> T awaitWithin(Future<T> future, long waitNanos, boolean cancelOnGiveUp)
      throws ExecutionException {
    try {
      return future.get(waitNanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      if (cancelOnGiveUp) {
        future.cancel(true);
      }
      throw new StoreUnavailableException(TIMED_OUT);
    } catch (InterruptedException e) {
      if (cancelOnGiveUp) {
        future.cancel(true);
      }
      Thread.currentThread().interrupt();
      throw new StoreUnavailableException(CONNECT_FAILED);
    }
  }

  /**
   * 내부 토큰을 붙여 api 에 GET 을 보내고 200 응답을 돌려준다 — 기술자·세대 두 경로의 송신·대기·상태 확인 공용. 비동기 전송 결과를 {@code
   * waitNanos} 까지만 기다린다 — 요청 {@code timeout} 은 접속 시간을 포함하지 않아(접속은 HttpClient 의 connectTimeout) 이렇게
   * 해야 접속까지 한 상한에 묶인다. 실패는 원인별로 WARN 한 줄({@code label} 로 어느 조회인지 구분)을 남기고 상태 표지만 실은 {@link
   * StoreUnavailableException} 으로 던진다(URL 쿼리·본문·토큰 없음).
   *
   * @param path {@code baseUrl} 뒤에 붙일 경로(쿼리 포함)
   * @param label 로그에 쓸 조회 이름
   */
  private HttpResponse<String> send(String path, long waitNanos, long tenantId, String label) {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofNanos(waitNanos))
            .header("Authorization", "Internal " + token)
            .GET()
            .build();
    HttpResponse<String> res;
    CompletableFuture<HttpResponse<String>> sent =
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
    try {
      res = awaitWithin(sent, waitNanos, true);
    } catch (StoreUnavailableException e) {
      // 시간 초과·인터럽트 — 이미 상태 표지로 바뀌었다.
      log.warn("{} 조회 실패 tenant={} 사유={}", label, tenantId, e.getMessage());
      throw e;
    } catch (ExecutionException | RuntimeException e) {
      // 요청 timeout 초과(HttpTimeoutException)도 접속 실패와 같이 상태 표지만 싣는다 — 원인 메시지에는 주소가 들어 있다.
      String status = e.getCause() instanceof HttpTimeoutException ? TIMED_OUT : CONNECT_FAILED;
      // 로그에는 원인 클래스·메시지를 남긴다(내부 api 주소는 로그에 괜찮고, 토큰은 헤더에만 있어 메시지에 나오지 않는다).
      // 예외 객체는 넘기지 않는다 — 진단에는 원인 한 단계로 충분하고 스택은 HttpClient 내부라 쓸모가 없다.
      Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
      log.warn(
          "{} 조회 실패 tenant={} 종류={} 원인={}: {}",
          label,
          tenantId,
          status,
          cause.getClass().getName(),
          cause.getMessage());
      throw new StoreUnavailableException(status);
    }
    if (res.statusCode() != 200) {
      // 기본 토큰이면 api 가 503, 토큰이 다르면 401 — 첫 배포 때 가장 흔한 실패라 상태를 남긴다.
      log.warn("{} 조회 실패 tenant={} 응답 상태={}", label, tenantId, res.statusCode());
      throw new StoreUnavailableException(String.valueOf(res.statusCode()));
    }
    return res;
  }

  /**
   * 원격 기술자 조회. 응답을 검증해 캐시에 넣는다 — 시작 세대가 아직 현재 세대일 때만, 그리고 시작 시각이 늦은 조회의 결과를 남긴다. 캐시에 못 넣어도 받은 값은 이
   * 조회를 기다린 호출자에게 돌려준다(그들에게는 충분히 새 값이다).
   */
  private StoreDescriptor fetch(long tenantId, long waitNanos) {
    // 신선도 기준 시각 — 요청을 보내기 전에 잰다(Cached 설명).
    long startedNanos = nanoClock.getAsLong();
    // 시작 세대도 요청을 보내기 전에 읽는다: 그 뒤의 무효화는 모두 세대를 올리므로 아래 쓰기 확인에서 걸러진다(보낸 뒤에 읽으면 그 사이 무효화를
    // 놓친다). 강제 갱신이 올린 세대 다음에 또 무효화가 있었다면 여기서 더 높은 세대를 읽는데, 요청은 그 무효화 뒤에 나가므로 그래도 맞다.
    long startGeneration = generation(tenantId);
    HttpResponse<String> res =
        send(
            "/internal/executor/tenants/" + tenantId + "/data-store?include=ro,slots",
            waitNanos,
            tenantId,
            DESCRIPTOR_LABEL);
    StoreDescriptor d;
    try {
      d = mapper.readValue(res.body(), StoreDescriptor.class);
    } catch (IOException e) {
      throw new StoreUnavailableException("응답 형식 오류");
    }
    if (d == null || d.tenantId() != tenantId) {
      // 본문이 literal null 이거나 다른 테넌트의 기술자면 쓰지 않는다.
      throw new StoreUnavailableException("응답 형식 오류");
    }
    if (!"TIBERO".equals(d.backend())) {
      throw new StoreUnavailableException("TIBERO 저장소가 아님");
    }
    if (!d.isComplete()) {
      throw new StoreUnavailableException("응답 형식 오류");
    }
    Cached<StoreDescriptor> fetched =
        new Cached<>(d, startedNanos, nanoClock.getAsLong() + ttlNanos, startGeneration);
    // 칸 잠금 안에서 확인하고 쓴다 — 확인과 쓰기 사이에 무효화가 끼어들 수 없다.
    cache.compute(
        tenantId,
        (k, old) -> {
          long current = old == null ? 0L : old.generation();
          if (current != startGeneration) {
            // 시작한 뒤 무효화(또는 강제 갱신)가 있었다 — 옛 조회 결과로 캐시를 덮지 않는다.
            return old;
          }
          // 같은 세대 안에서는 늦게 끝난 옛 조회가 더 새로 시작한 조회의 결과를 덮지 않게 시작 시각이 늦은 쪽을 남긴다.
          if (old != null && old.value() != null && old.fetchedAtNanos() > startedNanos) {
            return old;
          }
          return fetched;
        });
    return d;
  }
}
