package com.smartfirehub.dbexecutor.web;

import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.sql.Deadline;
import com.smartfirehub.dbexecutor.sql.LoadRunner;
import com.smartfirehub.dbexecutor.sql.ProbeRunner;
import com.smartfirehub.dbexecutor.sql.QueryRunner;
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
  static final String READ_SLOT_UNSUPPORTED = "슬롯 조회는 아직 지원하지 않습니다.";

  private final StoreDescriptorClient descriptors;
  private final SqlRunner sqlRunner;
  private final ProbeRunner probeRunner;
  private final QueryRunner queryRunner;
  private final LoadRunner loadRunner;
  private final ExecutionTimeouts timeouts;

  public ExecuteController(
      StoreDescriptorClient descriptors,
      SqlRunner sqlRunner,
      ProbeRunner probeRunner,
      QueryRunner queryRunner,
      LoadRunner loadRunner,
      ExecutionTimeouts timeouts) {
    this.descriptors = descriptors;
    this.sqlRunner = sqlRunner;
    this.probeRunner = probeRunner;
    this.queryRunner = queryRunner;
    this.loadRunner = loadRunner;
    this.timeouts = timeouts;
  }

  /**
   * 선행 문장 + 본문 한 문장을 한 트랜잭션으로 실행한다.
   *
   * <p>{@code timeout_seconds}(선택)가 있으면 {@code min(요청값, app.load.timeout-seconds)} 가 전체 기한이자 문장
   * 제한이다(긴 SQL 용). 0 이하는 거부한다. 없으면 기존 50/55초 그대로다.
   */
  @PostMapping("/execute/sql")
  public SqlExecuteResponse executeSql(@RequestBody SqlExecuteRequest req) {
    if (req.tenantId() == null || req.query() == null) {
      return SqlExecuteResponse.failure(BAD_REQUEST, null, "요청 검증 실패");
    }
    if (req.timeoutSeconds() != null && req.timeoutSeconds() <= 0) {
      return SqlExecuteResponse.failure(BAD_TIMEOUT, null, "요청 검증 실패");
    }
    Integer requested = capped(req.timeoutSeconds());
    int total = requested != null ? requested : timeouts.sql().totalTimeoutSeconds();
    // 기한은 도착 즉시 — 기술자 조회·풀 대여·실행이 모두 이 안이다.
    try (Deadline deadline = new Deadline(total)) {
      StoreDescriptor d;
      try {
        d = descriptors.get(req.tenantId(), deadline.remaining());
      } catch (StoreUnavailableException e) {
        return SqlExecuteResponse.failure(e.getMessage(), null, "저장소 정보 조회 실패로 실행하지 않았습니다.");
      }
      return sqlRunner.run(d, req.query(), req.preStatements(), deadline, requested);
    }
  }

  /** 요청별 시간 제한을 {@code app.load.timeout-seconds} 로 자른다(없으면 null). */
  private Integer capped(Integer requestedSeconds) {
    return requestedSeconds == null
        ? null
        : Math.min(requestedSeconds, timeouts.load().timeoutSeconds());
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
    Integer requested = capped(req.timeoutSeconds());
    int total = requested != null ? requested : timeouts.load().timeoutSeconds();
    try (Deadline deadline = new Deadline(total)) {
      StoreDescriptor d;
      try {
        d = descriptors.get(req.tenantId(), deadline.remaining());
      } catch (StoreUnavailableException e) {
        return LoadResponse.failure(e.getMessage(), null);
      }
      return loadRunner.load(d, req.table(), req.rows(), req.columnTypeMap(), deadline);
    }
  }

  /** SELECT 의 결과 컬럼 메타데이터만 돌려준다. */
  @PostMapping("/execute/probe")
  public ProbeResponse probe(@RequestBody ProbeRequest req) {
    if (req.tenantId() == null || req.query() == null) {
      return ProbeResponse.failure(BAD_REQUEST, null);
    }
    try (Deadline deadline = new Deadline(timeouts.probe().timeoutSeconds())) {
      StoreDescriptor d;
      try {
        d = descriptors.get(req.tenantId(), deadline.remaining());
      } catch (StoreUnavailableException e) {
        return ProbeResponse.failure(e.getMessage(), null);
      }
      return probeRunner.probe(d, req.query(), deadline);
    }
  }

  /** 분석 SQL 을 읽기 전용 계정으로 실행해 행을 돌려준다(최대 {@code maxRows} 행). */
  @PostMapping("/execute/query")
  public QueryResponse query(@RequestBody QueryRequest req) {
    if (req.tenantId() == null || req.query() == null || req.maxRows() == null) {
      return QueryResponse.failure(BAD_REQUEST, null);
    }
    // Python 실행기와 같은 요청 모양을 받되, 이 창구는 읽기 전용 계정으로만 돈다 — true 가 아니면(false·누락) 거부한다.
    if (req.readOnly() != Boolean.TRUE) {
      return QueryResponse.failure(READ_ONLY_REQUIRED, null);
    }
    if (req.readSlot() != null) {
      return QueryResponse.failure(READ_SLOT_UNSUPPORTED, null);
    }
    try (Deadline deadline = new Deadline(timeouts.query().timeoutSeconds())) {
      StoreDescriptor d;
      try {
        d = descriptors.get(req.tenantId(), deadline.remaining());
      } catch (StoreUnavailableException e) {
        return QueryResponse.failure(e.getMessage(), null);
      }
      return queryRunner.run(d, req.query(), req.maxRows(), deadline);
    }
  }
}
