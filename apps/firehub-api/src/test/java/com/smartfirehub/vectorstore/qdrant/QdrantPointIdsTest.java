package com.smartfirehub.vectorstore.qdrant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 포인트 id 규칙 고정 테스트(ADR 0002 결정 7). 기대값은 Python {@code uuid.uuid5(NAMESPACE, name)} 으로 뽑았다 — 바뀌면 기존
 * 포인트를 찾지 못한다.
 */
class QdrantPointIdsTest {

  @Test
  void idsMatchPythonUuid5() {
    assertThat(QdrantPointIds.dataset(7, 42))
        .isEqualTo(UUID.fromString("a9c35268-53a2-5c9d-b4b1-5c3ef16c13da"));
    assertThat(QdrantPointIds.chunk(7, 9001))
        .isEqualTo(UUID.fromString("aceffa55-4f9d-5698-8fb2-d555ec2b9d18"));
    assertThat(QdrantPointIds.row(7, 42, 3, 0, 2))
        .isEqualTo(UUID.fromString("26ff2926-569e-533b-bd75-66ec05a125fd"));
  }

  @Test
  void idsAreVersion5AndDistinguishKindTenantAndGeneration() {
    UUID id = QdrantPointIds.dataset(7, 42);
    assertThat(id.version()).isEqualTo(5);
    assertThat(id.variant()).isEqualTo(2);
    // 종류·테넌트·세대가 다르면 다른 포인트
    assertThat(QdrantPointIds.chunk(7, 42)).isNotEqualTo(id);
    assertThat(QdrantPointIds.dataset(8, 42)).isNotEqualTo(id);
    assertThat(QdrantPointIds.row(7, 42, 3, 0, 3)).isNotEqualTo(QdrantPointIds.row(7, 42, 3, 0, 2));
    // 같은 대상은 늘 같은 id(덮어쓰기)
    assertThat(QdrantPointIds.dataset(7, 42)).isEqualTo(id);
  }
}
