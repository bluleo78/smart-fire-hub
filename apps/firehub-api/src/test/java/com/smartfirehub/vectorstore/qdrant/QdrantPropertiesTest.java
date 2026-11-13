package com.smartfirehub.vectorstore.qdrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Qdrant 접속 설정 해석 테스트 — 잘못된 값은 컬렉션을 만들기 전에 기동에서 막는다. */
class QdrantPropertiesTest {

  @Test
  void parsesSchemeHostAndPort() {
    assertThat(props("http://qdrant:6334").endpoint())
        .isEqualTo(new QdrantProperties.Endpoint("qdrant", 6334, false));
    assertThat(props("https://vec.internal").endpoint())
        .isEqualTo(new QdrantProperties.Endpoint("vec.internal", 6334, true));
    assertThat(props(" HTTPS://vec:7000 ").endpoint())
        .isEqualTo(new QdrantProperties.Endpoint("vec", 7000, true));
  }

  @Test
  void rejectsBadUrlAndShardNumber() {
    assertThatThrownBy(() -> props("qdrant:6334")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> props("grpc://qdrant")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new QdrantProperties("http://q", null, 0, true, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("shard-number");
  }

  private static QdrantProperties props(String url) {
    return new QdrantProperties(url, null, 6, true, Duration.ofSeconds(1));
  }
}
