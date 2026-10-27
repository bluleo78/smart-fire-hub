package com.smartfirehub.global.execution;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이 JVM이 현재 쥔 실행 ID와 테넌트 ID를 보관한다.
 *
 * <p>하트비트는 테넌트별 RLS 컨텍스트가 필요하고, 정리기는 이 JVM의 살아 있는 실행을 건너뛰어야 한다. 도메인별 빈이 이 클래스를 상속해 등록 상태가 서로 섞이지 않게
 * 한다.
 */
public class ExecutionRegistry {

  private final Map<Long, Long> executions = new ConcurrentHashMap<>();

  /** 실행을 등록한다. */
  public void register(long executionId, long tenantId) {
    executions.put(executionId, tenantId);
  }

  /** 종료된 실행을 등록부에서 뺀다. */
  public void unregister(long executionId) {
    executions.remove(executionId);
  }

  /**
   * 이 JVM 이 그 실행을 쥐고 있는가. 정리기가 행마다 묻는다 — 실행 ID 는 테이블에서 유일하고 정리기의 조회가 이미 테넌트로 걸러 오므로 테넌트까지 비교할 필요가
   * 없고, 등록부 전체를 복사할 필요도 없다.
   */
  public boolean isRegistered(long executionId) {
    return executions.containsKey(executionId);
  }

  /** 순회 중 등록·해제와 경합하지 않도록 복사본을 돌려준다(하트비트가 테넌트별로 묶을 때 쓴다). */
  public Map<Long, Long> snapshot() {
    return new HashMap<>(executions);
  }
}
