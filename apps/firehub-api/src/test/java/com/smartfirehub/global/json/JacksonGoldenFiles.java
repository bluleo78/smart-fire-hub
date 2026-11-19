package com.smartfirehub.global.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Jackson 골든 시험(WD-793)들이 함께 쓰는 골든 파일 읽기·쓰기 도우미.
 *
 * <p>골든은 클래스패스의 {@code <디렉터리>/<파일>} 에 끝 줄바꿈 없이 저장한다. 시스템 속성 {@code
 * -Djackson.golden.write=true}(Gradle 에서는 {@code -PjacksonGoldenWrite=true})일 때만 현재 값으로 다시 쓰고, 평소에는
 * 비교만 한다. 시험마다 같은 코드를 복붙하지 않으려고 한곳에 둔다.
 */
final class JacksonGoldenFiles {

  /** 골든 쓰기 모드 스위치. */
  static final boolean WRITE = Boolean.getBoolean("jackson.golden.write");

  private JacksonGoldenFiles() {}

  /** 쓰기 모드면 골든 파일을 현재 값으로 쓰고, 아니면 골든 파일과 바이트 단위(문자열)로 비교한다. */
  static void check(String dir, String fileName, String actual) throws IOException {
    if (WRITE) {
      write(dir, fileName, actual);
      return;
    }
    assertThat(actual).as("골든 " + dir + fileName).isEqualTo(read(dir, fileName));
  }

  /** 쓰기 모드일 때만 골든 파일을 쓴다(비교는 호출부가 한다). */
  static void write(String dir, String fileName, String content) throws IOException {
    Path path = Path.of("src/test/resources/" + dir);
    Files.createDirectories(path);
    Files.writeString(path.resolve(fileName), content, StandardCharsets.UTF_8);
  }

  /** 클래스패스의 골든 파일을 UTF-8 문자열로 읽는다. */
  static String read(String dir, String fileName) throws IOException {
    try (InputStream in =
        JacksonGoldenFiles.class.getClassLoader().getResourceAsStream(dir + fileName)) {
      assertThat(in).as("골든 파일 없음: " + dir + fileName).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
