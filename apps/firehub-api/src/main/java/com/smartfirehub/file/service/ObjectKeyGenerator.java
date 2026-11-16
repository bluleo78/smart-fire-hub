package com.smartfirehub.file.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * FILE 데이터셋 업로드용 오브젝트 키 생성기.
 *
 * <p>실제 S3와 동일하게 키 = "&lt;데이터셋 prefix&gt;&lt;상대 경로/파일명&gt;" 이다. 앱이 uuid·날짜·출처 같은 계층을 임의로 끼워넣지 않으며,
 * 키의 마지막 세그먼트가 곧 표시/다운로드 파일명이 된다(동일 경로 재업로드는 S3처럼 덮어쓰기).
 *
 * <p>폴더 업로드를 지원하기 위해 하위 경로(세그먼트)는 보존하되, 상위경로("..")로의 이탈은 차단한다. 키는 항상 앱이 prefix를 앞에 붙여 결정하고 정제된 경로만
 * 이어붙이므로 프리픽스 밖으로 나갈 수 없다 — 즉 이 클래스의 {@link #sanitizeFilename}이 프리픽스 격리의 유일한 방어선이다.
 *
 * <p>폴더 탐색·폴더 지정 업로드(WD-761)에서 쓰는 폴더 경로도 같은 세그먼트 규칙의 {@link #normalizeFolderPath}로만 정규화한다.
 */
@Component
public class ObjectKeyGenerator {

  /** 키에 담을 상대경로의 최대 바이트(UTF-8). S3 키 상한(1024B) 내에서 prefix 여유를 남기고 폴더 깊이를 허용. */
  private static final int MAX_KEY_SUFFIX_BYTES = 900;

  /**
   * 원본 파일명/상대경로 정제: 키에 안전하게 담을 수 있도록 세그먼트 단위로 가공한다.
   *
   * <ul>
   *   <li>'/'·'\\'를 경로 구분자로 정규화하고 세그먼트로 분해 → 폴더 구조(하위 경로)를 보존한다.
   *   <li>빈 세그먼트("//"·선행 '/')와 "."(현재 디렉터리)는 흡수해 제거.
   *   <li>".." 세그먼트가 하나라도 있으면 traversal 시도로 보고 <b>전체를 거부</b>(빈 문자열 → 호출부 400).
   *   <li>각 세그먼트의 제어문자 제거(키 오염 방지), 앞뒤 공백 정리.
   *   <li>재조합한 상대경로를 UTF-8 바이트 기준으로 제한하되 확장자는 보존.
   * </ul>
   *
   * 결과가 비면 빈 문자열을 반환(호출부에서 400으로 거부).
   */
  public String sanitizeFilename(String filename) {
    // 경로 구분자 정규화 후 세그먼트 단위 정제 — 폴더 구조는 살리고 상위경로 이탈은 막는다.
    List<String> segments = splitSegments(filename, true);
    if (segments == null || segments.isEmpty()) return ""; // null = ".." → traversal 시도로 전체 거부
    return truncateUtf8PreservingExt(String.join("/", segments), MAX_KEY_SUFFIX_BYTES);
  }

  /**
   * S3 방식 키 생성: "&lt;prefix&gt;&lt;원본 파일명&gt;". prefix는 항상 trailing '/'로 끝난다고 가정한다
   * (FileDatasetConfig에서 보장). 파일명이 정제 후 비면 prefix만 반환하므로, 호출부에서 이를 400으로 거부해야 한다.
   */
  public String generateKey(String prefix, String filename) {
    return generateKey(prefix, "", filename);
  }

  /**
   * 폴더 지정 업로드 키: "&lt;prefix&gt;&lt;folderPath&gt;&lt;정제한 파일 상대경로&gt;"(WD-761 — 지금 보는 폴더에 올리기).
   *
   * <p>folderPath 는 {@link #normalizeFolderPath} 결과("" 또는 "a/b/")여야 한다. 파일명이 정제 후 비면 폴더를 붙이지 않고
   * prefix 만 반환해 기존 2인자와 같이 호출부가 400 으로 거부하게 한다. 폴더 + 파일 상대경로 합이 상한을 넘으면 조용히 자르지 않고 거부한다 — 잘라내면 폴더
   * 경로나 파일명이 사용자가 고르지 않은 값으로 바뀐다.
   *
   * @throws IllegalArgumentException 폴더 + 파일 상대경로가 {@value #MAX_KEY_SUFFIX_BYTES}바이트를 넘을 때(400)
   */
  public String generateKey(String prefix, String folderPath, String filename) {
    String sanitized = sanitizeFilename(filename);
    if (sanitized.isEmpty()) return prefix;
    String folder = folderPath == null ? "" : folderPath;
    // 폴더 없이 올리면 기존 동작(파일명만 상한 내로 자름)과 같아야 한다 — 폴더가 있을 때만 합계를 검사한다.
    if (!folder.isEmpty() && utf8Length(folder + sanitized) > MAX_KEY_SUFFIX_BYTES) {
      throw new IllegalArgumentException(
          "폴더 경로와 파일 경로를 합친 길이가 너무 깁니다(최대 " + MAX_KEY_SUFFIX_BYTES + "바이트): " + filename);
    }
    return prefix + folder + sanitized;
  }

  /**
   * 목록·업로드 대상 폴더 경로 정규화(WD-761). 데이터셋 prefix 뒤에 이어 붙일 상대 경로를 만든다.
   *
   * <ul>
   *   <li>null·공백·'/' 만 → "" (데이터셋 루트).
   *   <li>{@link #sanitizeFilename}과 같은 분해 규칙: '\'→'/', 빈(공백뿐인) 세그먼트·"." 흡수, ".." 거부.
   *   <li>다만 세그먼트 이름은 <b>다듬지 않는다</b>(앞뒤 공백 유지) — 폴더 경로는 목록이 돌려준 실제 S3 폴더(common prefix)를 가리켜야 해서,
   *       파이프라인·직접 동기화로 생긴 「report 」 같은 폴더도 열 수 있어야 한다. 대신 제어문자가 들어 있으면 지우지 않고 거부한다.
   *   <li>".." 세그먼트가 있으면 prefix 밖으로 나가려는 시도로 보고 거부한다(Spring 이 %2e%2e 를 미리 디코드하므로 여기서 함께 잡힌다).
   *   <li>상한({@value #MAX_KEY_SUFFIX_BYTES}바이트)을 넘으면 거부 — 파일명과 달리 잘라내면 다른 폴더를 가리키게 된다.
   * </ul>
   *
   * @return "" 또는 끝에 '/' 가 붙은 상대 경로(예 "a/b/")
   * @throws IllegalArgumentException ".." 세그먼트가 있거나 상한을 넘을 때(GlobalExceptionHandler 가 400 으로 응답)
   */
  public String normalizeFolderPath(String path) {
    List<String> segments = splitSegments(path, false);
    if (segments == null) {
      throw new IllegalArgumentException("폴더 경로에 상위 경로(..)를 쓸 수 없습니다");
    }
    if (segments.isEmpty()) return "";
    if (segments.stream().anyMatch(seg -> seg.chars().anyMatch(Character::isISOControl))) {
      throw new IllegalArgumentException("폴더 경로에 제어문자를 쓸 수 없습니다");
    }
    String normalized = String.join("/", segments) + "/";
    if (utf8Length(normalized) > MAX_KEY_SUFFIX_BYTES) {
      throw new IllegalArgumentException("폴더 경로가 너무 깁니다(최대 " + MAX_KEY_SUFFIX_BYTES + "바이트)");
    }
    return normalized;
  }

  /**
   * 경로를 '/' 세그먼트로 나눈다 — 파일명 정제와 폴더 경로 정규화가 같은 분해 규칙(프리픽스 격리의 방어선)을 한곳에서 쓰게 한다.
   *
   * <p>'\' 는 '/' 로 바꾸고, 빈(공백뿐인) 세그먼트와 "." 는 흡수한다. clean 이면 세그먼트의 제어문자를 지우고 앞뒤 공백을 다듬는다(업로드 파일명용).
   * ".." 세그먼트가 하나라도 있으면 null 을 돌려준다(호출부가 거부).
   */
  private static List<String> splitSegments(String path, boolean clean) {
    List<String> segments = new ArrayList<>();
    if (path == null) return segments;
    for (String raw : path.replace('\\', '/').split("/")) {
      String seg = clean ? raw.replaceAll("\\p{Cntrl}", "").trim() : raw;
      if (seg.isBlank() || seg.equals(".")) continue; // 빈/현재디렉터리 세그먼트 흡수
      if (seg.equals("..")) return null; // 상위경로 → traversal 시도
      segments.add(seg);
    }
    return segments;
  }

  /** UTF-8 바이트 길이 — S3 키 상한이 바이트 기준이라 문자 수가 아닌 바이트로 잰다. */
  private static int utf8Length(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
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
