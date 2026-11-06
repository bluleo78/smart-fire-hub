package com.smartfirehub.support.tibero;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Tibero 실서버 테스트의 증거 기록(WD-497) — 계정 정리 결과·라이선스 경고·측정값을 한 파일에 덧붙인다.
 *
 * <p>왜 파일인가: 종료 훅의 출력은 테스트별 결과 XML 에 실리지 않는다. 실행마다 바뀌는 계정 접미사는 가려서 남긴다(결과 비교·코멘트 인용이 쉽도록). 비밀값은
 * 호출자가 넘기지 않는다.
 */
public final class TiberoTestEvidence {

  /** 증거 파일(작업 디렉터리 apps/firehub-api 기준). */
  public static final Path FILE = Path.of("build", "tibero-test-evidence.txt");

  private TiberoTestEvidence() {}

  /** 한 줄을 표준 출력과 증거 파일에 덧붙인다. 테스트 계정 이름의 실행별 접미사는 {@code *} 로 가린다. */
  public static synchronized void write(String line) {
    String masked = TiberoTestAccount.mask(line);
    System.out.println("TIBERO_TEST " + masked);
    try {
      Files.createDirectories(FILE.getParent());
      Files.writeString(
          FILE,
          masked + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // 증거 파일은 보조 수단이다 — 표준 출력에는 이미 남겼다.
    }
  }
}
