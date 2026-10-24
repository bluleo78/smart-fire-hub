package com.smartfirehub.dbexecutor.config;

import com.smartfirehub.dbexecutor.store.HikariPoolFactory;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.TenantPools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 실제 Hikari 팩토리를 {@link TenantPools} 에 연결한다. 계정별로 보관소를 둘로 나눈다 — 파이프라인(OWNER)과 분석 조회(읽기 전용)가 서로의 풀
 * 한도·LRU 를 잠식하지 않게 하고, 한쪽 비밀번호 회전이 다른 쪽 풀을 건드리지 않게 하기 위해서다.
 */
@Configuration
public class DbExecutorConfig {

  /** OWNER 계정 풀(/execute/sql·/execute/probe). 기존 빈 이름을 유지한다. */
  @Bean
  public TenantPools tenantPools(DbExecutorProperties props) {
    return new TenantPools(
        new HikariPoolFactory(props.pool().maxSize(), StoreDescriptor::owner),
        StoreDescriptor::owner,
        props.pool().maxPools());
  }

  /** 슬롯 계정 풀(/execute/query + readSlot 스트리밍, WD-78) — 키 (테넌트, 슬롯), 풀당 커넥션 1. */
  @Bean("slotPools")
  public TenantPools slotPools(DbExecutorProperties props) {
    return TenantPools.forSlots(HikariPoolFactory.forSlots(), props.pool().slotMaxPools());
  }

  /** 읽기 전용 계정 풀(/execute/query). */
  @Bean("readOnlyPools")
  public TenantPools readOnlyPools(DbExecutorProperties props) {
    return new TenantPools(
        new HikariPoolFactory(props.pool().readOnlyMaxSize(), StoreDescriptor::ro),
        StoreDescriptor::ro,
        props.pool().maxPools());
  }
}
