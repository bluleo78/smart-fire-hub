package com.smartfirehub.pipeline.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 이 api 인스턴스(JVM)가 지금 쥐고 있는 파이프라인 실행의 등록부(WD-230).
 *
 * <p>하트비트 서비스는 여기 등록된 실행만 갱신하고, 정리기는 여기 등록된 실행을 「살아 있음」으로 보고 건너뛴다. 그래서 주인이 죽은 실행(재시작으로 등록부가 비어 버린
 * 뒤에도 DB 에 RUNNING 으로 남은 행)만 하트비트가 멈춰 정리 대상이 된다. 제출 시점에 등록하고 러너가 끝나면 해제한다.
 */
@Component
public class PipelineExecutionRegistry {

  /** executionId → tenantId. 하트비트가 테넌트별로 묶어 갱신해야 해서(RLS) 테넌트를 함께 둔다. */
  private final Map<Long, Long> executions = new ConcurrentHashMap<>();

  /** 실행을 등록한다. */
  public void register(long executionId, long tenantId) {
    executions.put(executionId, tenantId);
  }

  /** 실행을 해제한다. 등록되지 않은 실행이면 아무것도 하지 않는다. */
  public void unregister(long executionId) {
    executions.remove(executionId);
  }

  /** 현재 등록 상태의 복사본 — 순회 중 등록·해제가 일어나도 안전하다. */
  public Map<Long, Long> snapshot() {
    return new HashMap<>(executions);
  }
}
