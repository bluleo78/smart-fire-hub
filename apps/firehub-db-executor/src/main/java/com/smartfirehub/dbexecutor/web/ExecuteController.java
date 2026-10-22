package com.smartfirehub.dbexecutor.web;

import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.sql.Deadline;
import com.smartfirehub.dbexecutor.sql.ProbeRunner;
import com.smartfirehub.dbexecutor.sql.SqlRunner;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.StoreUnavailableException;
import com.smartfirehub.dbexecutor.web.dto.ProbeRequest;
import com.smartfirehub.dbexecutor.web.dto.ProbeResponse;
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
 * <p>요청 전체 기한({@link Deadline})은 <b>요청이 여기 도착한 순간</b> 잡는다(/execute/sql 기본 55초, /execute/probe 기본
 * 30초). 기술자 조회는 남은 시간만큼만 기다리고, 풀 대여·실행도 같은 기한 안에서 한다 — 기술자 조회(최대 10초)·풀 대여(최대 10초) 뒤에 기한을 새로 잡으면
 * api 의 호출 제한(60초·35초)을 넘겨, api 가 먼저 포기해 단계를 FAILED 로 적은 뒤에 실행기가 커밋할 수 있다.
 */
@RestController
public class ExecuteController {

  static final String BAD_REQUEST = "요청 형식이 올바르지 않습니다.";

  private final StoreDescriptorClient descriptors;
  private final SqlRunner sqlRunner;
  private final ProbeRunner probeRunner;
  private final ExecutionTimeouts timeouts;

  public ExecuteController(
      StoreDescriptorClient descriptors,
      SqlRunner sqlRunner,
      ProbeRunner probeRunner,
      ExecutionTimeouts timeouts) {
    this.descriptors = descriptors;
    this.sqlRunner = sqlRunner;
    this.probeRunner = probeRunner;
    this.timeouts = timeouts;
  }

  /** 선행 문장 + 본문 한 문장을 한 트랜잭션으로 실행한다. */
  @PostMapping("/execute/sql")
  public SqlExecuteResponse executeSql(@RequestBody SqlExecuteRequest req) {
    if (req.tenantId() == null || req.query() == null) {
      return SqlExecuteResponse.failure(BAD_REQUEST, null, "요청 검증 실패");
    }
    // 기한은 도착 즉시 — 기술자 조회·풀 대여·실행이 모두 이 안이다.
    try (Deadline deadline = new Deadline(timeouts.sql().totalTimeoutSeconds())) {
      StoreDescriptor d;
      try {
        d = descriptors.get(req.tenantId(), deadline.remaining());
      } catch (StoreUnavailableException e) {
        return SqlExecuteResponse.failure(e.getMessage(), null, "저장소 정보 조회 실패로 실행하지 않았습니다.");
      }
      return sqlRunner.run(d, req.query(), req.preStatements(), deadline);
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
}
