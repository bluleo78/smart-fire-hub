package com.smartfirehub.global.platformdb.tibero;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

/**
 * {@link TiberoNameHash} — 잠금 id·알림 이름·컨텍스트 이름이 저장·파생 식별자라, 공용 도우미로 옮긴 뒤에도 예전 계산과 바이트 단위로 같아야 한다.
 */
class TiberoNameHashTest {

  @Test
  void 예전_계산식과_같은_값을_낸다() throws Exception {
    String sample = "FH_OWNER";
    // 예전 식을 도우미와 독립적으로 다시 적는다: UTF-8 SHA-256 앞 8바이트 → long, 위 32비트 → 대문자 16진 8자.
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(sample.getBytes(UTF_8));
    long expected64 = ByteBuffer.wrap(digest, 0, 8).getLong();
    String expectedHex = String.format("%08X", (int) (expected64 >>> 32));

    assertThat(TiberoNameHash.hash64(sample)).isEqualTo(expected64);
    assertThat(TiberoNameHash.hex8(sample)).isEqualTo(expectedHex).hasSize(8);
  }
}
