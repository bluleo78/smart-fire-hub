package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link DataStoreFeatures} 단위 테스트(WD-73) — Spring 없이 플래그별 백엔드 사용 가능 여부와 미지원 기능 예외 메시지를 검증한다. */
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
  void 미지원_기능_예외_메시지는_넘긴_백엔드_이름을_쓴다() {
    // 메시지가 Tibero 로 고정돼 있지 않아야 MySQL 등 다른 외부 백엔드가 같은 예외를 쓸 수 있다.
    assertThat(new DataStoreFeatureUnsupportedException(DataStoreBackend.MYSQL, "공간 데이터"))
        .hasMessage("공간 데이터: MySQL 저장소에서 아직 지원하지 않습니다");
    assertThat(new DataStoreFeatureUnsupportedException(DataStoreBackend.TIBERO, "GEOMETRY 컬럼"))
        .hasMessage("GEOMETRY 컬럼: Tibero 저장소에서 아직 지원하지 않습니다");
  }

  @Test
  void POSTGRESQL은_플래그와_무관하게_항상_사용_가능() {
    assertThat(new DataStoreFeatures(false).isAvailable(DataStoreBackend.POSTGRESQL)).isTrue();
    assertThat(new DataStoreFeatures(true).isAvailable(DataStoreBackend.POSTGRESQL)).isTrue();
  }
}
