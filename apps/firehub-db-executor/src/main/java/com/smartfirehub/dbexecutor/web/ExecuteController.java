package com.smartfirehub.dbexecutor.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.security.InternalTokenFilter;
import com.smartfirehub.dbexecutor.security.ScriptToken;
import com.smartfirehub.dbexecutor.sql.Deadline;
import com.smartfirehub.dbexecutor.sql.LoadRunner;
import com.smartfirehub.dbexecutor.sql.ProbeRunner;
import com.smartfirehub.dbexecutor.sql.QueryRunner;
import com.smartfirehub.dbexecutor.sql.SlotQueryRunner;
import com.smartfirehub.dbexecutor.sql.SqlRunner;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.StoreUnavailableException;
import com.smartfirehub.dbexecutor.web.dto.LoadRequest;
import com.smartfirehub.dbexecutor.web.dto.LoadResponse;
import com.smartfirehub.dbexecutor.web.dto.ProbeRequest;
import com.smartfirehub.dbexecutor.web.dto.ProbeResponse;
import com.smartfirehub.dbexecutor.web.dto.QueryRequest;
import com.smartfirehub.dbexecutor.web.dto.QueryResponse;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteRequest;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * api 가 부르는 실행 엔드포인트(스펙 §4.2). 인증은 {@code InternalTokenFilter} 가 먼저 한다(실패만 401).
 *
 * <p>Python executor 관례대로 오류는 HTTP 200 + {@code success=false} 로 싣는다. 저장소 기술자를 못 얻으면 실행하지 않고(풀도 만들지
 * 않고) 그 한국어 사유를 돌려준다.
 *
 * <p>요청 전체 기한({@link Deadline})은 <b>요청이 여기 도착한 순간</b> 잡는다(/execute/sql 기본 55초,
 * /execute/probe·/execute/query 기본 30초). 기술자 조회는 남은 시간만큼만 기다리고, 풀 대여·실행도 같은 기한 안에서 한다 — 기술자 조회(최대
 * 10초)·풀 대여(최대 10초) 뒤에 기한을 새로 잡으면 api 의 호출 제한(60초·35초)을 넘겨, api 가 먼저 포기해 단계를 FAILED 로 적은 뒤에 실행기가
 * 커밋할 수 있다.
 */
@RestController
public class ExecuteController {

  static final String BAD_REQUEST = "요청 형식이 올바르지 않습니다.";
  static final String BAD_TIMEOUT = "timeout_seconds 는 1 이상이어야 합니다.";
  static final String READ_ONLY_REQUIRED = "읽기 전용 조회만 실행할 수 있습니다.";
  static final String SLOT_REQUIRES_STREAM = "슬롯 조회(readSlot)는 stream:true 로만 실행할 수 있습니다.";
  static final String STREAM_REQUIRES_SLOT = "스트리밍 조회(stream)는 슬롯 조회(readSlot)에서만 쓸 수 있습니다.";
  static final String BAD_SLOT = "readSlot 은 1~10 이어야 합니다.";

  /** NDJSON 응답 형식(두 실행기 같은 값, 계약 벡터 {@code query.streaming.contentType}). */
  static final String NDJSON = "application/x-ndjson";

  /** 스크립트 요청이 쓰는 기술자의 최대 나이(Ruling 16) — 등급 변경이 올린 토큰 세대를 캐시 TTL(60초) 동안 놓치지 않게. */
  static final Duration SCRIPT_DESCRIPTOR_MAX_AGE = Duration.ofSeconds(5);

  private static final int MAX_SLOT = 10;

  private final StoreDescriptorClient descriptors;
  private final SqlRunner sqlRunner;
  private final ProbeRunner probeRunner;
  private final QueryRunner queryRunner;
  private final LoadRunner loadRunner;
  private final SlotQueryRunner slotQueryRunner;
  private final ExecutionTimeouts timeouts;
  private final ObjectMapper mapper;

  public ExecuteController(
      StoreDescriptorClient descriptors,
      SqlRunner sqlRunner,
      ProbeRunner probeRunner,
      QueryRunner queryRunner,
      LoadRunner loadRunner,
      SlotQueryRunner slotQueryRunner,
      ExecutionTimeouts timeouts,
      ObjectMapper mapper) {
    this.descriptors = descriptors;
    this.sqlRunner = sqlRunner;
    this.probeRunner = probeRunner;
    this.queryRunner = queryRunner;
    this.loadRunner = loadRunner;
    this.slotQueryRunner = slotQueryRunner;
    this.timeouts = timeouts;
    this.mapper = mapper;
  }

  /**
   * 선행 문장 + 본문 한 문장을 한 트랜잭션으로 실행한다.
   *
   * <p>{@code timeout_seconds}(선택)가 있으면 {@code min(요청값, app.load.timeout-seconds)} 가 본문의 전체 기한이자 문장
   * 제한이다(긴 SQL 용). 0 이하는 거부한다. 없으면 기존 50/55초 그대로다.
   *
   * <p>선행 문장(REPLACE 비우기)이 있으면 도착 시점 기한을 {@code 선행 상한 + 본문 전체 기한} 으로 잡는다 — 기술자 조회·대여·선행 문장을 덮고, 본문
   * 기한은 실행기가 본문 시작 시점에 따로 잡는다(스펙 §6, WD-236). 선행 문장이 없으면 기존 그대로 본문 전체 기한 하나다.
   */
  @PostMapping("/execute/sql")
  public SqlExecuteResponse executeSql(@RequestBody SqlExecuteRequest req) {
    if (req.tenantId() == null || req.query() == null) {
      return SqlExecuteResponse.failure(BAD_REQUEST, null, "요청 검증 실패");
    }
    if (req.timeoutSeconds() != null && req.timeoutSeconds() <= 0) {
      return SqlExecuteResponse.failure(BAD_TIMEOUT, null, "요청 검증 실패");
    }
    // 실행기에는 자른 요청값을 그대로(없으면 null) 넘긴다 — null 이어야 문장 제한이 기존 50초로 남는다(전체 기한만 55초).
    Integer requested = capped(req.timeoutSeconds());
    int bodySeconds = effectiveSeconds(requested, timeouts.sql().totalTimeoutSeconds());
    boolean hasPreStatements = req.preStatements() != null && !req.preStatements().isEmpty();
    // 기한은 도착 즉시 — 기술자 조회·풀 대여·실행이 모두 이 안이다(선행 문장이 있으면 선행 상한만큼 더 길다).
    try (Deadline deadline =
        new Deadline(timeouts.sql().requestSeconds(bodySeconds, hasPreStatements))) {
      return withDescriptor(
          req.tenantId(),
          deadline,
          null,
          reason -> SqlExecuteResponse.failure(reason, null, "저장소 정보 조회 실패로 실행하지 않았습니다."),
          d -> sqlRunner.run(d, req.query(), req.preStatements(), deadline, requested));
    }
  }

  /** 요청별 시간 제한을 {@code app.load.timeout-seconds} 로 자른다(없으면 null). */
  private Integer capped(Integer requestedSeconds) {
    return requestedSeconds == null
        ? null
        : Math.min(requestedSeconds, timeouts.load().timeoutSeconds());
  }

  /** 요청 전체 기한(초): 자른 요청값이 있으면 그 값, 없으면 창구 기본값. /execute/sql·/execute/load 가 같은 규칙을 쓴다. */
  private static int effectiveSeconds(Integer cappedSeconds, int defaultSeconds) {
    return cappedSeconds != null ? cappedSeconds : defaultSeconds;
  }

  /** 기술자를 얻은 뒤 할 일. 스트리밍 경로는 응답에 직접 쓰므로 {@link IOException} 을 던질 수 있다. */
  @FunctionalInterface
  private interface DescriptorWork<R, E extends Exception> {
    R run(StoreDescriptor d) throws E;
  }

  /** 기술자를 못 얻었을 때의 응답(사유는 한국어 문구). */
  @FunctionalInterface
  private interface Unavailable<R, E extends Exception> {
    R respond(String reason) throws E;
  }

  /**
   * 기한 안에서 기술자를 얻어 {@code work} 를 하고, 못 얻으면(StoreUnavailableException) 실행하지 않고 {@code unavailable}
   * 응답을 돌려준다. 여섯 창구가 같은 try/catch 를 되풀이하지 않게 한 곳에 둔다. 예외 타입 E 는 람다가 던지는 것으로 추론된다(JSON 창구는
   * RuntimeException — 검사 예외가 새지 않는다).
   *
   * @param maxAge 캐시 최대 나이 — 스크립트 토큰 요청만 준다(Ruling 16). null 이면 기존 TTL 조회({@code get(id, budget)})를
   *     그대로 부른다.
   */
  private <R, E extends Exception> R withDescriptor(
      long tenantId,
      Deadline deadline,
      Duration maxAge,
      Unavailable<R, E> unavailable,
      DescriptorWork<R, E> work)
      throws E {
    StoreDescriptor d;
    try {
      d =
          maxAge == null
              ? descriptors.get(tenantId, deadline.remaining())
              : descriptors.get(tenantId, deadline.remaining(), maxAge);
    } catch (StoreUnavailableException e) {
      return unavailable.respond(e.getMessage());
    }
    return work.run(d);
  }

  /** 행 목록을 스테이징 테이블에 한 트랜잭션으로 적재한다(스펙 §3.3). 기한은 {@code app.load.timeout-seconds} 이하다. */
  @PostMapping("/execute/load")
  public LoadResponse load(@RequestBody LoadRequest req) {
    if (req.tenantId() == null || req.table() == null || req.rows() == null) {
      return LoadResponse.failure(BAD_REQUEST, null);
    }
    if (req.timeoutSeconds() != null && req.timeoutSeconds() <= 0) {
      return LoadResponse.failure(BAD_TIMEOUT, null);
    }
    int total = effectiveSeconds(capped(req.timeoutSeconds()), timeouts.load().timeoutSeconds());
    try (Deadline deadline = new Deadline(total)) {
      return withDescriptor(
          req.tenantId(),
          deadline,
          null,
          reason -> LoadResponse.failure(reason, null),
          d -> loadRunner.load(d, req.table(), req.rows(), req.columnTypeMap(), deadline));
    }
  }

  /** SELECT 의 결과 컬럼 메타데이터만 돌려준다. */
  @PostMapping("/execute/probe")
  public ProbeResponse probe(@RequestBody ProbeRequest req) {
    if (req.tenantId() == null || req.query() == null) {
      return ProbeResponse.failure(BAD_REQUEST, null);
    }
    try (Deadline deadline = new Deadline(timeouts.probe().timeoutSeconds())) {
      return withDescriptor(
          req.tenantId(),
          deadline,
          null,
          reason -> ProbeResponse.failure(reason, null),
          d -> probeRunner.probe(d, req.query(), deadline));
    }
  }

  /**
   * {@code /execute/query} — 두 갈래다.
   *
   * <ul>
   *   <li>분석 조회(기존): 읽기 전용 계정으로 최대 {@code maxRows} 행을 JSON 으로 돌려준다.
   *   <li>슬롯 스트리밍(WD-78): {@code readSlot} + {@code stream:true} 면 슬롯 계정으로 끝까지 읽어 NDJSON 으로
   *       흘려보낸다({@link SlotQueryRunner}). 둘 중 하나만 있으면 거부한다 — 슬롯 조회의 길을 하나로 두기 위해서다(내부 토큰도 같다).
   * </ul>
   *
   * <p>스크립트 토큰 요청({@code Authorization: Script}, 필터가 서명·만료를 확인해 속성으로 넘김)은 슬롯 스트리밍만 할 수 있다. 본문에
   * readSlot·stream:true 가 없거나 본문의 테넌트·슬롯이 토큰과 다르면 401, 기술자의 스크립트 토큰 세대보다 낮은 토큰도 401 이다. 이 확인은 다른
   * 어떤 분기보다 먼저 한다 — 스크립트 토큰이 RO 분석 조회로 새지 않게.
   *
   * <p>스트리밍은 응답 스트림에 직접 쓴다(요청 스레드, 결과를 모으지 않음). 비동기 MVC 를 쓰지 않아 컨테이너의 비동기 시간 제한(30초)·공유 실행기에 묶이지
   * 않고, 기한은 {@link Deadline}(스크립트 토큰은 만료까지, 내부 토큰은 {@code app.load.timeout-seconds})이 지킨다.
   *
   * @return 분석 조회·요청 형식 거부는 {@link QueryResponse}(JSON), 스트리밍·401 은 응답에 이미 썼으므로 null
   */
  @PostMapping("/execute/query")
  public Object query(
      @RequestBody QueryRequest req, HttpServletRequest http, HttpServletResponse res)
      throws IOException {
    if (http.getAttribute(ScriptToken.ATTRIBUTE) instanceof ScriptToken.Claims claims) {
      return scriptQuery(req, claims, res);
    }
    boolean slotRequest = req.readSlot() != null || Boolean.TRUE.equals(req.stream());
    // stream:true 요청의 실행 전 거부는 NDJSON 실패 줄 하나로 답한다 — 받는 쪽(스크립트 도우미)이 응답 모양 하나만 읽으면 되게(Minor 3).
    boolean streamRequest = Boolean.TRUE.equals(req.stream());
    if (req.tenantId() == null || req.query() == null || (!slotRequest && req.maxRows() == null)) {
      return reject(streamRequest, BAD_REQUEST, res);
    }
    // Python 실행기와 같은 요청 모양을 받되, 이 창구는 읽기 전용 조회만 한다 — true 가 아니면(false·누락) 거부한다.
    if (req.readOnly() != Boolean.TRUE) {
      return reject(streamRequest, READ_ONLY_REQUIRED, res);
    }
    if (slotRequest) {
      String invalid = slotRequestError(req);
      if (invalid != null) {
        return reject(streamRequest, invalid, res);
      }
      // 내부 토큰(api 대칭용): 본문 값을 쓰고, 기한은 긴 작업 상한이다.
      try (Deadline deadline = new Deadline(timeouts.load().timeoutSeconds())) {
        return withDescriptor(
            req.tenantId(),
            deadline,
            null,
            reason -> reject(true, reason, res),
            d -> {
              stream(d, req.readSlot(), req.query(), deadline, res);
              return null;
            });
      }
    }
    try (Deadline deadline = new Deadline(timeouts.query().timeoutSeconds())) {
      return withDescriptor(
          req.tenantId(),
          deadline,
          null,
          reason -> QueryResponse.failure(reason, null),
          d -> queryRunner.run(d, req.query(), req.maxRows(), deadline));
    }
  }

  /** 실행 전 거부. 스트리밍 요청이면 NDJSON 실패 줄 하나를 응답에 쓰고 null, 아니면 기존 JSON {@link QueryResponse} 를 돌려준다. */
  private Object reject(boolean streamRequest, String error, HttpServletResponse res)
      throws IOException {
    if (streamRequest) {
      writeNdjsonFailure(res, error);
      return null;
    }
    return QueryResponse.failure(error, null);
  }

  /** readSlot·stream 조합 검사: 둘 다 있어야 하고 슬롯은 1~10. 문제 없으면 null. */
  private static String slotRequestError(QueryRequest req) {
    if (req.readSlot() == null) {
      return STREAM_REQUIRES_SLOT;
    }
    if (!Boolean.TRUE.equals(req.stream())) {
      return SLOT_REQUIRES_STREAM;
    }
    if (req.readSlot() < 1 || req.readSlot() > MAX_SLOT) {
      return BAD_SLOT;
    }
    return null;
  }

  /**
   * 스크립트 토큰 요청(WD-78, 스펙 §3.4). 토큰의 테넌트·슬롯이 이긴다 — 본문 값은 같아야만 받는다. 순서: 본문 확인(401) → 형식 확인 → 신선한
   * 기술자(5초 이내) → 세대 확인(401) → 스트리밍. 기한은 토큰 만료까지다.
   */
  private Object scriptQuery(QueryRequest req, ScriptToken.Claims claims, HttpServletResponse res)
      throws IOException {
    if (req.readSlot() == null
        || !Boolean.TRUE.equals(req.stream())
        || req.tenantId() == null
        || req.tenantId() != claims.tenantId()
        || req.readSlot() != claims.slot()) {
      InternalTokenFilter.unauthorized(res);
      return null;
    }
    // 인증을 통과한 스크립트 요청은 언제나 stream:true 다 — 형식 거부도 NDJSON 실패 줄 하나.
    if (req.query() == null) {
      return reject(true, BAD_REQUEST, res);
    }
    if (req.readOnly() != Boolean.TRUE) {
      return reject(true, READ_ONLY_REQUIRED, res);
    }
    try (Deadline deadline = new Deadline(secondsUntil(claims.expiresAt()))) {
      return withDescriptor(
          claims.tenantId(),
          deadline,
          SCRIPT_DESCRIPTOR_MAX_AGE,
          reason -> reject(true, reason, res),
          d -> {
            // 등급 변경이 세대를 올린 뒤의 옛 토큰은 거부한다(Ruling 15·16). 세대를 모르면(null) 막는다(fail-closed).
            if (d.scriptTokenEpoch() == null || claims.epoch() < d.scriptTokenEpoch()) {
              InternalTokenFilter.unauthorized(res);
              return null;
            }
            stream(d, claims.slot(), req.query(), deadline, res);
            return null;
          });
    }
  }

  /** 토큰 만료까지 남은 초(최소 1). int 범위로 자른다. */
  private static int secondsUntil(long expiresAtEpochSeconds) {
    long left = expiresAtEpochSeconds - System.currentTimeMillis() / 1000L;
    return (int) Math.max(1, Math.min(Integer.MAX_VALUE, left));
  }

  /** NDJSON 응답을 시작하고 슬롯 스트리밍을 쓴다. 마지막 줄을 못 쓴 끊김은 무시한다(커넥션은 이미 반납됐다). */
  private void stream(
      StoreDescriptor d, int slot, String query, Deadline deadline, HttpServletResponse res)
      throws IOException {
    res.setStatus(HttpServletResponse.SC_OK);
    res.setContentType(NDJSON);
    OutputStream out = res.getOutputStream();
    try {
      slotQueryRunner.stream(d, slot, query, deadline, out);
    } catch (IOException clientGone) {
      // 받는 쪽이 끊었다 — 더 보낼 곳이 없다.
    }
  }

  /** 실행 전 실패를 NDJSON 실패 줄 하나로 쓴다(스트리밍 요청의 응답 모양 유지). */
  private void writeNdjsonFailure(HttpServletResponse res, String error) throws IOException {
    // 줄 모양은 스트리밍 실행기의 실패 마지막 줄과 한 곳(SlotQueryRunner.failure)에서 만든다.
    Map<String, Object> line = SlotQueryRunner.failure(error, null);
    res.setStatus(HttpServletResponse.SC_OK);
    res.setContentType(NDJSON);
    OutputStream out = res.getOutputStream();
    out.write(mapper.writeValueAsBytes(line));
    out.write('\n');
    out.flush();
  }
}
