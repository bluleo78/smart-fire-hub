package com.smartfirehub.dbexecutor.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dbexecutor.config.DbExecutorProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 슬롯 풀 크기 설정이 실제 Hikari 풀 상한까지 전달되는지, 소유 풀이 유휴 커넥션을 미리 채우지 않는지 확인한다. */
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

  /**
   * 소유 풀은 최소 유휴 0 이다(WD-449) — 기본값(=최대 크기)이면 자격이 무효가 된 테넌트(정지 후 계정 삭제) 풀이 몇 초마다 재접속해 외부 DB 에
   * 로그인 실패를 쌓는다. 생성 시점에는 접속하지 않으므로 DB 없이 확인할 수 있다.
   */
  @Test
  void ownerPoolKeepsNoIdleConnections() {
    StoreDescriptor descriptor = new StoreDescriptor(
        7L, "TIBERO", "localhost", 8629, "tb", "GENIA_T7",
        new StoreDescriptor.Account("GENIA_T7", "unused"), null, List.of(), 0L);

    try (HikariDataSource dataSource =
        (HikariDataSource) new HikariPoolFactory(4, StoreDescriptor::owner).apply(descriptor)) {
      assertThat(dataSource.getMinimumIdle()).isZero();
      assertThat(dataSource.getMaximumPoolSize()).isEqualTo(4);
    }
  }
}
