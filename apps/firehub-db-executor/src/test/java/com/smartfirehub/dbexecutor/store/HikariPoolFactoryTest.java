package com.smartfirehub.dbexecutor.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dbexecutor.config.DbExecutorProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 슬롯 풀 크기 설정이 실제 Hikari 풀 상한까지 전달되는지 확인한다. */
class HikariPoolFactoryTest {

  @Test
  void slotPoolUsesConfiguredMaximumSize() {
    DbExecutorProperties.Pool pool = new DbExecutorProperties.Pool(8, 4, 2, 8, 5);
    StoreDescriptor descriptor = new StoreDescriptor(
        7L, "TIBERO", "localhost", 8629, "tb", "GENIA_T7",
        new StoreDescriptor.Account("GENIA_T7", "unused"), null,
        List.of(new StoreDescriptor.SlotAccount(3, "GENIA_T7_S3", "unused")), 0L);

    try (HikariDataSource dataSource = (HikariDataSource) HikariPoolFactory
        .forSlots(pool.slotMaxSize()).apply(descriptor, 3)) {
      assertThat(dataSource.getMaximumPoolSize()).isEqualTo(5);
    }
  }
}
