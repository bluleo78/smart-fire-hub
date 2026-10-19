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
}
