package com.smartfirehub.file.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * FILE 데이터셋 업로드용 오브젝트 키 생성기.
 *
 * <p>실제 S3와 동일하게 키 = "&lt;데이터셋 prefix&gt;&lt;상대 경로/파일명&gt;" 이다. 앱이 uuid·날짜·출처 같은 계층을
 * 임의로 끼워넣지 않으며, 키의 마지막 세그먼트가 곧 표시/다운로드 파일명이 된다(동일 경로 재업로드는 S3처럼 덮어쓰기).
 *
 * <p>폴더 업로드를 지원하기 위해 하위 경로(세그먼트)는 보존하되, 상위경로("..")로의 이탈은 차단한다. 키는 항상 앱이
 * prefix를 앞에 붙여 결정하고 정제된 경로만 이어붙이므로 프리픽스 밖으로 나갈 수 없다 — 즉 이 클래스의
 * {@link #sanitizeFilename}이 프리픽스 격리의 유일한 방어선이다.
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
    if (filename == null) return "";
    // 경로 구분자 정규화 후 세그먼트 단위 정제 — 폴더 구조는 살리고 상위경로 이탈은 막는다.
    String normalized = filename.replace('\\', '/');
    List<String> segments = new ArrayList<>();
    for (String raw : normalized.split("/")) {
      String seg = raw.replaceAll("\\p{Cntrl}", "").trim();
      if (seg.isEmpty() || seg.equals(".")) continue; // 빈/현재디렉터리 세그먼트 흡수
      if (seg.equals("..")) return ""; // 상위경로 → traversal 시도로 전체 거부
      segments.add(seg);
    }
    if (segments.isEmpty()) return "";
    return truncateUtf8PreservingExt(String.join("/", segments), MAX_KEY_SUFFIX_BYTES);
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
