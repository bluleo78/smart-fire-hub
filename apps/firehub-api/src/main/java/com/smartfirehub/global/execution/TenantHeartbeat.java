package com.smartfirehub.global.execution;

import com.smartfirehub.global.tenant.TenantContext;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 파이프라인·프로액티브 실행의 하트비트를 테넌트별 RLS 컨텍스트 안에서 갱신하는 공통 루프. */
public final class TenantHeartbeat {

  private TenantHeartbeat() {}

  /** 한 테넌트의 실행 ID와 UTC 현재 시각을 받는 저장소 갱신 함수. */
  @FunctionalInterface
  public interface Touch {
    void touch(Collection<Long> ids, LocalDateTime now);
  }

  /** 어느 테넌트의 어느 실행이 실패했는지 호출자의 도메인 로그에 전달한다. */
  @FunctionalInterface
  public interface Failure {
    void accept(long tenantId, Collection<Long> ids, Exception error);
  }

  /** 한 테넌트가 실패해도 나머지 테넌트는 갱신하고, 실패는 호출자 로그에 넘긴다. */
  public static void beat(ExecutionRegistry registry, Touch touch, Failure onFailure) {
    Map<Long, Long> snapshot = registry.snapshot();
    if (snapshot.isEmpty()) return;
    Map<Long, List<Long>> byTenant = new TreeMap<>();
    snapshot.forEach(
        (id, tenantId) -> byTenant.computeIfAbsent(tenantId, ignored -> new ArrayList<>()).add(id));
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    byTenant.forEach(
        (tenantId, ids) -> {
          try {
            TenantContext.runScoped(tenantId, () -> touch.touch(ids, now));
          } catch (Exception e) {
            onFailure.accept(tenantId, ids, e);
          }
        });
  }
}
