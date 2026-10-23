package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * ai-agent 방언 문자열 매핑 검증(WD-79). 채팅·프로액티브 요청이 같은 헬퍼를 쓰므로 매핑 규칙을 여기서 한 번 고정한다 — 에이전트는
 * "TIBERO"·"POSTGRESQL" 두 값만 알며, Tibero 가 아닌 백엔드는 모두 PG 지침을 받는다.
 */
class DataStoreBackendTest {

  @Test
  void dialectOf_tibero_isTibero() {
    assertThat(DataStoreBackend.dialectOf(DataStoreBackend.TIBERO)).isEqualTo("TIBERO");
  }

  @Test
  void dialectOf_otherBackends_arePostgresql() {
    assertThat(DataStoreBackend.dialectOf(DataStoreBackend.POSTGRESQL)).isEqualTo("POSTGRESQL");
    assertThat(DataStoreBackend.dialectOf(DataStoreBackend.MYSQL)).isEqualTo("POSTGRESQL");
    assertThat(DataStoreBackend.dialectOf(null)).isEqualTo("POSTGRESQL");
  }
}
