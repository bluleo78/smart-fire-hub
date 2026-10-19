package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link DataStoreFeatures} 단위 테스트(WD-73) — Spring 없이 플래그별 백엔드 사용 가능 여부를 검증한다. */
class DataStoreFeaturesTest {

  @Test
  void 플래그가_꺼져_있으면_TIBERO는_사용_불가() {
    assertThat(new DataStoreFeatures(false).isAvailable(DataStoreBackend.TIBERO)).isFalse();
  }

  @Test
  void 플래그가_켜져_있으면_TIBERO는_사용_가능() {
    assertThat(new DataStoreFeatures(true).isAvailable(DataStoreBackend.TIBERO)).isTrue();
  }

  @Test
  void MYSQL은_플래그와_무관하게_항상_사용_불가() {
    assertThat(new DataStoreFeatures(false).isAvailable(DataStoreBackend.MYSQL)).isFalse();
    assertThat(new DataStoreFeatures(true).isAvailable(DataStoreBackend.MYSQL)).isFalse();
  }

  @Test
  void POSTGRESQL은_플래그와_무관하게_항상_사용_가능() {
    assertThat(new DataStoreFeatures(false).isAvailable(DataStoreBackend.POSTGRESQL)).isTrue();
    assertThat(new DataStoreFeatures(true).isAvailable(DataStoreBackend.POSTGRESQL)).isTrue();
  }
}
