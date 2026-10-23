package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * ai-agent 방언 문자열 매핑(WD-79)과 백엔드별 기능 지원 판정(WD-80) 검증. 채팅·프로액티브 요청이 같은 헬퍼를 쓰므로 매핑 규칙을 여기서 한 번 고정한다 —
 * 에이전트는 "TIBERO"·"POSTGRESQL" 두 값만 알며, Tibero 가 아닌 백엔드는 모두 PG 지침을 받는다.
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

  /** 행 검색·GEOMETRY 는 PostgreSQL 만 지원한다(WD-80) — 관문·기능 응답·배경 스윕이 이 판정을 함께 쓴다. */
  @Test
  void supports_onlyPostgresql() {
    assertThat(DataStoreBackend.POSTGRESQL.supportsRowSearch()).isTrue();
    assertThat(DataStoreBackend.POSTGRESQL.supportsGeometry()).isTrue();
    assertThat(DataStoreBackend.TIBERO.supportsRowSearch()).isFalse();
    assertThat(DataStoreBackend.TIBERO.supportsGeometry()).isFalse();
    assertThat(DataStoreBackend.MYSQL.supportsRowSearch()).isFalse();
    assertThat(DataStoreBackend.MYSQL.supportsGeometry()).isFalse();
  }
}
