package com.smartfirehub.file.service;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * FILE 데이터셋 업로드용 오브젝트 키 생성기.
 *
 * <p>실제 S3와 동일하게 키 = "&lt;데이터셋 prefix&gt;&lt;원본 파일명&gt;" 이다. 앱이 uuid·날짜·출처 같은 계층을 임의로
 * 끼워넣지 않으며, 키의 마지막 세그먼트가 곧 표시/다운로드 파일명이 된다(동일 이름 재업로드는 S3처럼 덮어쓰기).
 *
 * <p>키는 항상 앱이 prefix를 앞에 붙여 결정하고, 클라이언트 파일명은 basename만 취하므로 프리픽스 밖으로 나갈 수 없다.
 * 즉 이 클래스의 {@link #sanitizeFilename}이 프리픽스 격리의 유일한 방어선이다.
 */
@Component
public class ObjectKeyGenerator {

  /** 키에 담을 원본 파일명의 최대 바이트(UTF-8). S3 키 상한(1024B) 여유 확보 + 한글(3B/자) 대비. */
  private static final int MAX_FILENAME_BYTES = 200;

  /**
   * 원본 파일명 정제: 키에 안전하게 담을 수 있도록 가공한다.
   *
   * <ul>
   *   <li>경로가 섞여 오면 basename만 취해 프리픽스 밖으로 새는 것을 차단('/'·'\\' 모두 처리).
   *   <li>제어문자 제거(키 오염 방지), 앞뒤 공백 정리.
   *   <li>"." / ".." 같은 무의미 이름은 빈 문자열로 간주.
   *   <li>UTF-8 바이트 기준으로 길이를 제한하되 확장자는 보존.
   * </ul>
   *
   * 결과가 비면 빈 문자열을 반환(호출부에서 400으로 거부).
   */
  public String sanitizeFilename(String filename) {
    if (filename == null) return "";
    String s = filename.replace('\\', '/');
    int slash = s.lastIndexOf('/');
    if (slash >= 0) s = s.substring(slash + 1);
    s = s.replaceAll("\\p{Cntrl}", "").trim();
    if (s.isEmpty() || s.equals(".") || s.equals("..")) return "";
    return truncateUtf8PreservingExt(s, MAX_FILENAME_BYTES);
  }

  /**
   * S3 방식 키 생성: "&lt;prefix&gt;&lt;원본 파일명&gt;". prefix는 항상 trailing '/'로 끝난다고 가정한다
   * (FileDatasetConfig에서 보장). 파일명이 정제 후 비면 prefix만 반환하므로, 호출부에서 이를 400으로 거부해야 한다.
   */
  public String generateKey(String prefix, String filename) {
    return prefix + sanitizeFilename(filename);
  }

  /** UTF-8 바이트 상한 내로 파일명을 자르되 확장자(마지막 '.') 이후는 보존한다. 멀티바이트 문자가 쪼개지지 않게 문자 단위로 줄인다. */
  private static String truncateUtf8PreservingExt(String s, int maxBytes) {
    if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return s;
    int dot = s.lastIndexOf('.');
    String ext = dot > 0 ? s.substring(dot) : "";
    String base = dot > 0 ? s.substring(0, dot) : s;
    int budget = maxBytes - ext.getBytes(StandardCharsets.UTF_8).length;
    // 확장자만으로도 예산을 넘으면 확장자 보존을 포기하고 전체 예산으로 자른다.
    if (budget < 0) {
      budget = maxBytes;
      ext = "";
      base = s;
    }
    String truncated = base;
    while (truncated.getBytes(StandardCharsets.UTF_8).length > budget && !truncated.isEmpty()) {
      truncated = truncated.substring(0, truncated.length() - 1);
    }
    return truncated + ext;
  }
}
