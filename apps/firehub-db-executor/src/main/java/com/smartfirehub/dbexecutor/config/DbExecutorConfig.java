package com.smartfirehub.dbexecutor.config;

import com.smartfirehub.dbexecutor.store.HikariPoolFactory;
import com.smartfirehub.dbexecutor.store.TenantPools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 실제 Hikari 팩토리를 {@link TenantPools} 에 연결한다. */
@Configuration
public class DbExecutorConfig {

  @Bean
  public TenantPools tenantPools(DbExecutorProperties props) {
    return new TenantPools(new HikariPoolFactory(props.pool().maxSize()), props.pool().maxPools());
  }
}
