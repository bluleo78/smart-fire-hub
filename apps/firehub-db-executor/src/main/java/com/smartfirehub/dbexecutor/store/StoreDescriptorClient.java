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
 * </ul>
 */
@Component
public class StoreDescriptorClient {

  private static final Logger log = LoggerFactory.getLogger(StoreDescriptorClient.class);

  /**
   * 캐시 항목(기술자·세대 공용). {@code fetchedAtNanos} 는 그 값을 받으려고 원격 조회를 <b>시작한</b> 시각이다 — 기술자 캐시는 늦게 끝난 옛
   * 조회가 더 새로 시작한 조회의 결과를 덮지 않게 갱신({@code cache.merge})이 이 시각으로 순서를 가린다.
   */
  private record Cached<T>(T value, long fetchedAtNanos, long expiresAtNanos) {}

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

  /** 진행 중인 테넌트별 기술자 조회 — 같은 테넌트의 동시 요청은 이 future 하나를 각자 남은 시간까지만 기다린다. */
  private final ConcurrentHashMap<Long, CompletableFuture<StoreDescriptor>> inflight =
      new ConcurrentHashMap<>();

  /** 진행 중인 테넌트별 세대 조회 — 같은 테넌트의 동시 요청은 이 future 하나를 각자 남은 시간까지만 기다린다. */
  private final ConcurrentHashMap<Long, CompletableFuture<Long>> epochInflight =
      new ConcurrentHashMap<>();

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

  /** 이 테넌트의 캐시된 기술자를 버린다 — 다음 요청이 api 에서 새로 받아 온다(비밀번호 교체 직후 로그인 실패 대응). */
  public void invalidate(long tenantId) {
    cache.remove(tenantId);
    epochCache.remove(tenantId);
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
      ConcurrentHashMap<Long, CompletableFuture<T>> inflightMap,
      Fetcher<T> fetcher) {
    T hit = fresh(cacheMap, tenantId);
    if (hit != null) {
      return hit;
    }
    long waitNanos = Math.min(budget.toNanos(), MAX_FETCH.toNanos());
    if (waitNanos <= 0) {
      throw new StoreUnavailableException(TIMED_OUT);
    }
    CompletableFuture<T> mine = new CompletableFuture<>();
    CompletableFuture<T> existing = inflightMap.putIfAbsent(tenantId, mine);
    if (existing != null) {
      return await(existing, waitNanos);
    }
    try {
      // 선행 조회가 방금 끝나 캐시를 채웠을 수 있다(경합) — 다시 확인.
      T again = fresh(cacheMap, tenantId);
      T result = again != null ? again : fetcher.fetch(tenantId, waitNanos);
      mine.complete(result);
      return result;
    } catch (RuntimeException | Error e) {
      // Error 까지 잡아 대기자를 반드시 깨운다(안 그러면 join 이 영원히 막힌다).
      mine.completeExceptionally(e);
      throw e;
    } finally {
      inflightMap.remove(tenantId, mine);
    }
  }

  /** 아직 만료되지 않은 캐시 값(없으면 null). */
  private <T> T fresh(ConcurrentHashMap<Long, Cached<T>> cacheMap, long tenantId) {
    Cached<T> c = cacheMap.get(tenantId);
    if (c != null && nanoClock.getAsLong() < c.expiresAtNanos()) {
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
    epochCache.put(tenantId, new Cached<>(value, started, started + EPOCH_TTL.toNanos()));
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

  /** 원격 기술자 조회. 응답을 검증해 캐시에 넣는다(시작 시각이 늦은 조회의 결과를 남긴다). */
  private StoreDescriptor fetch(long tenantId, long waitNanos) {
    // 신선도 기준 시각 — 요청을 보내기 전에 잰다(Cached 설명).
    long startedNanos = nanoClock.getAsLong();
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
        new Cached<>(d, startedNanos, nanoClock.getAsLong() + ttlNanos);
    // 늦게 끝난 옛 조회가 더 새로 시작한 조회의 결과를 덮지 않게 시작 시각이 늦은 쪽을 남긴다.
    cache.merge(
        tenantId, fetched, (old, neu) -> neu.fetchedAtNanos() >= old.fetchedAtNanos() ? neu : old);
    return d;
  }
}
