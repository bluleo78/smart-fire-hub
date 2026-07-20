package com.smartfirehub.file.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 오브젝트 키 생성/정제 규칙 검증(순수 단위 테스트). */
class ObjectKeyGeneratorTest {

  final ObjectKeyGenerator gen = new ObjectKeyGenerator();

  @Test
  void sanitizeRobotId_lowercasesReplacesInvalidAndDefaultsToWeb() {
    assertThat(gen.sanitizeRobotId("Robot 01!")).isEqualTo("robot-01");
    assertThat(gen.sanitizeRobotId(null)).isEqualTo("web");
    assertThat(gen.sanitizeRobotId("   ")).isEqualTo("web");
  }

  @Test
  void sanitizeExt_stripsNonAlnumCapsAndDefaultsToBin() {
    assertThat(gen.sanitizeExt("JPG")).isEqualTo("jpg");
    assertThat(gen.sanitizeExt(".Png")).isEqualTo("png");
    assertThat(gen.sanitizeExt(null)).isEqualTo("bin");
    assertThat(gen.sanitizeExt("verylongextension")).hasSize(10);
  }

  @Test
  void generateKey_followsConventionUnderPrefix() {
    String key = gen.generateKey("equip/", "robot-01", "jpg");
    // <prefix><robotId>/<yyyy-MM-dd>/<uuid>.<ext>
    assertThat(key).matches("equip/robot-01/\\d{4}-\\d{2}-\\d{2}/[0-9a-f-]{36}\\.jpg");
  }
}
