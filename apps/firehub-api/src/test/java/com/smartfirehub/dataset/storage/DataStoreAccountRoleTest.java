package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import org.junit.jupiter.api.Test;

/** {@link DataStoreAccountRole} 단위 테스트(WD-73) — 역할 목록·슬롯 수·계정 이름 규칙을 고정한다. */
class DataStoreAccountRoleTest {

  @Test
  void all은_OWNER_RO_S1부터_S10까지_12개() {
    assertThat(DataStoreAccountRole.all()).hasSize(12);
    assertThat(DataStoreAccountRole.all().get(0)).isEqualTo(DataStoreAccountRole.OWNER);
    assertThat(DataStoreAccountRole.all().get(1)).isEqualTo(DataStoreAccountRole.RO);
    assertThat(DataStoreAccountRole.all().get(11)).isEqualTo(DataStoreAccountRole.S10);
  }

  @Test
  void 슬롯_수는_PG_파이프라인_읽기_슬롯_수와_같다() {
    assertThat(DataStoreAccountRole.all().size() - 2)
        .isEqualTo(TenantPipelineRole.PYTHON_READ_SLOTS);
  }

  @Test
  void usernameFor_규칙() {
    assertThat(DataStoreAccountRole.OWNER.usernameFor(5)).isEqualTo("GENIA_T5");
    assertThat(DataStoreAccountRole.RO.usernameFor(5)).isEqualTo("GENIA_T5_RO");
    assertThat(DataStoreAccountRole.S10.usernameFor(5)).isEqualTo("GENIA_T5_S10");
  }

  /** 슬롯 번호는 이름을 파싱하지 않고 역할에 명시한다(WD-78) — OWNER·RO 는 슬롯이 아니고 S1..S10 은 1..10. */
  @Test
  void readSlot_명시적_번호() {
    assertThat(DataStoreAccountRole.OWNER.isReadSlot()).isFalse();
    assertThat(DataStoreAccountRole.RO.isReadSlot()).isFalse();
    for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
      DataStoreAccountRole role = DataStoreAccountRole.valueOf("S" + k);
      assertThat(role.isReadSlot()).isTrue();
      assertThat(role.readSlot()).isEqualTo(k);
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> DataStoreAccountRole.RO.readSlot())
        .isInstanceOf(IllegalStateException.class);
  }

  /** forSlot(k) 는 S{k} 와 같고, 1..10 밖은 거부한다(WD-78). */
  @Test
  void forSlot_번호로_슬롯_역할을_고른다() {
    for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
      assertThat(DataStoreAccountRole.forSlot(k)).isEqualTo(DataStoreAccountRole.valueOf("S" + k));
    }
    for (int bad : new int[] {0, -1, 11}) {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> DataStoreAccountRole.forSlot(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
