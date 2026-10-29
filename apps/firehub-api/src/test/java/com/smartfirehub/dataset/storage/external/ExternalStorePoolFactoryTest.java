package com.smartfirehub.dataset.storage.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

/** 테넌트 소유 계정 풀 설정 확인. 풀 생성 시점에는 접속하지 않으므로 DB 없이 확인할 수 있다. */
class ExternalStorePoolFactoryTest {

  /**
   * 최소 유휴 0(WD-449) — 기본값(=최대 크기)이면 정지 후 계정이 지워진 테넌트의 풀이 유휴 커넥션 4개를 채우려 몇 초마다 끝없이 재접속해 외부 DB 에
   * 로그인 실패를 쌓았다.
   */
  @Test
  void ownerPoolKeepsNoIdleConnections() {
    ExternalStorePoolFactory factory = new ExternalStorePoolFactory(mock(ExternalDbConnector.class));
    ExternalDbTarget target =
        new ExternalDbTarget(DataStoreBackend.TIBERO, "localhost", 8629, "tibero");

    try (HikariDataSource ds = (HikariDataSource) factory.create(7L, target, "GENIA_T7", "x")) {
      assertThat(ds.getMinimumIdle()).isZero();
      assertThat(ds.getMaximumPoolSize()).isEqualTo(4);
    }
  }
}
