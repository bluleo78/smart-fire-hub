package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 생성 비밀번호 규칙(24자, 영문+숫자, 첫 글자 영문) 검증. */
class DataStorePasswordGeneratorTest {

  private final DataStorePasswordGenerator generator = new DataStorePasswordGenerator();

  @Test
  void generate_is24AlphanumericStartingWithLetter() {
    for (int i = 0; i < 1000; i++) {
      assertThat(generator.generate()).matches("^[A-Za-z][A-Za-z0-9]{23}$");
    }
  }

  @Test
  void generate_differsAcrossCalls() {
    assertThat(generator.generate()).isNotEqualTo(generator.generate());
  }
}
