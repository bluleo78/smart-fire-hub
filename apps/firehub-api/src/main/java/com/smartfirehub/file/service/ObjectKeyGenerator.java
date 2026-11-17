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
 * <p>폴더 탐색·폴더 지정 업로드(WD-761)에서 쓰는 폴더 경로는 {@link #normalizeFolderPath}로만 정규화한다. 같은 분해 함수를 쓰되, 폴더는 실제
 * S3 폴더를 가리켜야 하므로 이름('\'·공백)을 바꾸지 않는다는 점이 파일명 정제와 다르다(WD-776).
 */
@Component
public class ObjectKeyGenerator {

  /** 키에 담을 상대경로의 최대 바이트(UTF-8). S3 키 상한(1024B) 내에서 prefix 여유를 남기고 폴더 깊이를 허용. */
  private static final int MAX_KEY_SUFFIX_BYTES = 900;

  /**
   * 키의 '/' 세그먼트 하나(폴더 이름·파일명)의 최대 바이트(UTF-8) — WD-776. MinIO 는 오브젝트를 디스크 파일·디렉터리로 저장하므로 파일시스템의 이름
   * 상한(NAME_MAX 255B)이 세그먼트마다 걸리고, 넘으면 PUT 을 400(XMinioInvalidObjectName)으로 거부한다(256B 거부·255B 허용
   * 실측). 운영도 MinIO 를 쓰므로(.claude/docs/deploy.md) 전체 키 상한(900B)만 지키면 URL 은 발급되는데 업로드가 실패한다 — 그래서 발급
   * 전에 세그먼트도 잰다.
   */
  private static final int MAX_SEGMENT_BYTES = 255;

  /**
   * 원본 파일명/상대경로 정제: 키에 안전하게 담을 수 있도록 세그먼트 단위로 가공한다.
   *
   * <ul>
   *   <li>'/'·'\\'를 경로 구분자로 정규화하고 세그먼트로 분해 → 폴더 구조(하위 경로)를 보존한다.
   *   <li>빈 세그먼트("//"·선행 '/')와 "."(현재 디렉터리)는 흡수해 제거.
   *   <li>".." 세그먼트가 하나라도 있으면 traversal 시도로 보고 <b>전체를 거부</b>(빈 문자열 → 호출부가 거부).
   *   <li>각 세그먼트의 제어문자 제거(키 오염 방지), 앞뒤 공백 정리.
   *   <li>마지막 세그먼트(파일명)를 {@value #MAX_SEGMENT_BYTES}바이트 이내로 자르고(WD-776 — MinIO 세그먼트 상한), 재조합한 상대경로를
   *       {@value #MAX_KEY_SUFFIX_BYTES}바이트 이내로 자른다. 둘 다 확장자는 보존하고 글자를 쪼개지 않는다. 하위 폴더 이름은 자르지
   *       않는다(자르면 다른 폴더가 된다 — 255B 초과는 {@link #generateKey(String, String, String)} 가 거부).
   * </ul>
   *
   * 결과가 비면 빈 문자열을 반환(호출부가 거부 — {@link #generateKey(String, String, String)}).
   */
  public String sanitizeFilename(String filename) {
    // 경로 구분자 정규화 후 세그먼트 단위 정제 — 폴더 구조는 살리고 상위경로 이탈은 막는다.
    List<String> segments = splitSegments(filename, true);
    if (segments == null || segments.isEmpty()) return ""; // null = ".." → traversal 시도로 전체 거부
    return truncateRelativePath(segments);
  }

  /**
   * 루트 업로드용 자르기(WD-776): 파일명 세그먼트를 먼저 255B 로, 그다음 상대경로 전체를 900B 로 자른다 — 결과 파일명은 「900B 합계에 들어가는 길이」와
   * 「255B」 중 작은 쪽이 된다.
   */
  private static String truncateRelativePath(List<String> segments) {
    List<String> capped = new ArrayList<>(segments);
    int last = capped.size() - 1;
    capped.set(last, truncateUtf8PreservingExt(capped.get(last), MAX_SEGMENT_BYTES));
    return truncateUtf8PreservingExt(String.join("/", capped), MAX_KEY_SUFFIX_BYTES);
  }

  /** 폴더 없이(루트에) 올리는 키 — {@link #generateKey(String, String, String)} 와 같다(시험용 편의 오버로드). */
  public String generateKey(String prefix, String filename) {
    return generateKey(prefix, "", filename);
  }

  /**
   * 업로드 키: "&lt;prefix&gt;&lt;folderPath&gt;&lt;정제한 파일 상대경로&gt;"(S3 방식, WD-761 — 지금 보는 폴더에 올리기).
   * folderPath 는 {@link #normalizeFolderPath} 결과("" 또는 "a/b/")여야 한다.
   *
   * <p>파일 하나의 검증 실패는 IllegalArgumentException 으로 알린다 — 업로드 URL 묶음은 이를 그 파일만의 실패로 싣고 나머지는 계속
   * 발급한다(WD-776, 수백 개 묶음이 파일 하나 때문에 통째로 400 이 되지 않게).
   *
   * <ul>
   *   <li>정제 후 빈 이름(공백·경로만·".." 세그먼트 — traversal 시도): 키를 만들지 않으므로 prefix 격리는 그대로다.
   *   <li>파일 상대경로의 하위 폴더 이름이 {@value #MAX_SEGMENT_BYTES}바이트를 넘음(루트·폴더 모두, WD-776): MinIO 가 받지 않는
   *       키이고, 폴더 이름을 자르면 다른 폴더가 되므로 거부한다.
   *   <li>폴더 안에서 파일명이 {@value #MAX_SEGMENT_BYTES}바이트를 넘음(WD-776): 아래 합계 상한과 같은 이유로 자르지 않고 거부한다.
   *   <li>폴더 + 파일 상대경로가 상한을 넘음: <b>자르지 않고 거부</b>한다. 잘라 올리면 앞부분이 같은 두 이름이 같은 키가 되어 서로 덮어쓰거나 폴더의 기존
   *       파일을 말없이 덮고, 화면은 성공으로 보여 사용자가 원래 이름으로 찾지 못한다. 폴더 없이(루트) 올릴 때만 기존처럼 sanitizeFilename 이 상한
   *       내로 자른다.
   * </ul>
   *
   * @throws IllegalArgumentException 위 파일별 검증 실패(사유에 파일명은 넣지 않는다 — 화면이 파일 경로 옆에 사유를 보인다)
   */
  public String generateKey(String prefix, String folderPath, String filename) {
    List<String> segments = splitSegments(filename, true);
    if (segments == null || segments.isEmpty()) {
      throw new IllegalArgumentException("유효한 파일명이 필요합니다");
    }
    // 하위 폴더 이름(마지막 세그먼트 제외)은 어디서든 자르지 않는다 — 255B 초과면 그 파일만 거부(WD-776).
    int last = segments.size() - 1;
    if (segments.subList(0, last).stream().anyMatch(seg -> utf8Length(seg) > MAX_SEGMENT_BYTES)) {
      throw new IllegalArgumentException(
          "폴더 이름이 너무 깁니다(최대 " + MAX_SEGMENT_BYTES + "바이트) — 이름을 줄여 다시 올려 주세요");
    }
    String folder = folderPath == null ? "" : folderPath;
    if (folder.isEmpty()) {
      // 루트: 파일명은 255B·합계 900B 안으로 자른다(기존 규칙 유지 + 세그먼트 상한).
      return prefix + truncateRelativePath(segments);
    }
    // 폴더 안: 자르지 않은 원래 이름으로 잰다 — 자른 이름으로 재면 경계에서 잘린 키가 통과할 수 있다(WD-776).
    if (utf8Length(segments.get(last)) > MAX_SEGMENT_BYTES) {
      throw new IllegalArgumentException(
          "파일 이름이 너무 깁니다(최대 " + MAX_SEGMENT_BYTES + "바이트) — 이름을 줄여 다시 올려 주세요");
    }
    String relative = String.join("/", segments);
    if (utf8Length(folder + relative) > MAX_KEY_SUFFIX_BYTES) {
      throw new IllegalArgumentException(
          "폴더 경로와 합친 이름이 너무 깁니다(최대 " + MAX_KEY_SUFFIX_BYTES + "바이트) — 이름을 줄여 다시 올려 주세요");
    }
    return prefix + folder + relative;
  }

  /**
   * 목록·업로드 대상 폴더 경로 정규화(WD-761). 데이터셋 prefix 뒤에 이어 붙일 상대 경로를 만든다. 상태가 없어 정적이다 — 폴더 목록의 {@link
   * #isOpenableFolder} 판정도 같은 규칙을 쓴다.
   *
   * <ul>
   *   <li>null·빈 문자열·'/' 만 → "" (데이터셋 루트).
   *   <li>'/' 로만 나눈다. 빈 세그먼트('//')·"." 는 흡수하고(손으로 넣은 "a/./b" 는 "a/b"), ".." 는 거부한다.
   *   <li>세그먼트 이름은 <b>다듬지도 바꾸지도 않는다</b> — 폴더 경로는 목록이 돌려준 실제 S3 폴더(common prefix)를 가리켜야 해서, 파이프라인·직접
   *       동기화로 생긴 「report 」·공백뿐인 「 」·「report\2024」 같은 폴더도 그대로 열 수 있어야 한다(WD-776 — 예전엔 '\'→'/' 로 바꾸고
   *       공백뿐인 세그먼트를 버려 다른 폴더를 열었다). 대신 제어문자가 들어 있으면 지우지 않고 거부한다.
   *   <li>".." 세그먼트가 있으면 prefix 밖으로 나가려는 시도로 보고 거부한다(Spring 이 %2e%2e 를 미리 디코드하므로 여기서 함께 잡힌다). '\' 로
   *       나뉜 조각이 ".." 인 세그먼트("a\..\b")도 거부한다 — S3 키로는 한 이름이지만 Windows 경로로 읽히면 상위 경로가 된다.
   *   <li>상한({@value #MAX_KEY_SUFFIX_BYTES}바이트)을 넘거나 세그먼트 하나가 {@value #MAX_SEGMENT_BYTES}바이트를 넘으면
   *       거부 — 파일명과 달리 잘라내면 다른 폴더를 가리키게 된다. 255B 를 넘는 이름의 폴더는 MinIO 에 있을 수 없다(WD-776).
   * </ul>
   *
   * @return "" 또는 끝에 '/' 가 붙은 상대 경로(예 "a/b/")
   * @throws IllegalArgumentException ".." 세그먼트·제어문자가 있거나 상한(합계·세그먼트)을 넘을 때(GlobalExceptionHandler 가
   *     400 으로 응답)
   */
  public static String normalizeFolderPath(String path) {
    List<String> segments = splitSegments(path, false);
    if (segments == null) {
      throw new IllegalArgumentException("폴더 경로에 상위 경로(..)를 쓸 수 없습니다");
    }
    if (segments.isEmpty()) return "";
    if (segments.stream().anyMatch(seg -> seg.chars().anyMatch(Character::isISOControl))) {
      throw new IllegalArgumentException("폴더 경로에 제어문자를 쓸 수 없습니다");
    }
    // 세그먼트 상한(WD-776): MinIO 는 255B 를 넘는 폴더 이름을 담은 키를 받지 않는다.
    if (segments.stream().anyMatch(seg -> utf8Length(seg) > MAX_SEGMENT_BYTES)) {
      throw new IllegalArgumentException("폴더 이름이 너무 깁니다(최대 " + MAX_SEGMENT_BYTES + "바이트)");
    }
    String normalized = String.join("/", segments) + "/";
    if (utf8Length(normalized) > MAX_KEY_SUFFIX_BYTES) {
      throw new IllegalArgumentException("폴더 경로가 너무 깁니다(최대 " + MAX_KEY_SUFFIX_BYTES + "바이트)");
    }
    return normalized;
  }

  /**
   * 폴더 목록에 보일 폴더인지(WD-776) — 「목록에서 빼는 폴더」 규칙은 여기 한 곳에 둔다. {@link #normalizeFolderPath} 가 그 상대
   * 경로("a/b/")를 거부하지 않고 <b>그대로</b> 돌려주면 참이라, 목록에 보인 폴더는 눌렀을 때 서버가 반드시 같은 폴더를 연다.
   *
   * <p>거짓인 폴더: 빈 이름("a//"), "."·"..", '\' 로 나눈 조각이 ".." 인 이름, 제어문자가 든 이름, 상한 초과(검색으로는 찾을 수 있다). "."
   * 를 열게 하지 않는 이유 — MinIO SDK 는 "."·".." 세그먼트 키의 presign 을 거부하고 MinIO 서버도 그런 키를 받지 않으며, 브라우저·OkHttp
   * 는 URL 의 점 세그먼트를 접어 부모 폴더 키를 가리키게 한다. 공백뿐인 이름·'\' 가 든 이름은 정규화가 그대로 보존하므로 보인다.
   *
   * @param relativeFolderPath 데이터셋 prefix 를 뺀 폴더 상대 경로(끝 '/')
   */
  public static boolean isOpenableFolder(String relativeFolderPath) {
    try {
      return normalizeFolderPath(relativeFolderPath).equals(relativeFolderPath);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * 경로를 '/' 세그먼트로 나눈다 — 파일명 정제와 폴더 경로 정규화가 같은 분해 규칙(프리픽스 격리의 방어선)을 한곳에서 쓰게 한다.
   *
   * <p>clean(업로드 파일명용)이면 '\' 를 '/' 로 바꾸고 세그먼트의 제어문자를 지우고 앞뒤 공백을 다듬는다 — 공백뿐인 세그먼트는 비게 되어 흡수된다. clean
   * 이 아니면(폴더 경로용, WD-776) '\' 도 공백도 그대로 둬 실제 폴더 이름을 보존한다. 두 경우 모두 빈 세그먼트와 "." 는 흡수한다. ".." 세그먼트(폴더
   * 경로는 '\' 로 나뉜 조각이 ".." 인 세그먼트 포함)가 하나라도 있으면 null 을 돌려준다(호출부가 거부).
   */
  private static List<String> splitSegments(String path, boolean clean) {
    List<String> segments = new ArrayList<>();
    if (path == null) return segments;
    String source = clean ? path.replace('\\', '/') : path;
    for (String raw : source.split("/")) {
      String seg = clean ? raw.replaceAll("\\p{Cntrl}", "").trim() : raw;
      if (seg.isEmpty() || seg.equals(".")) continue; // 빈/현재디렉터리 세그먼트 흡수
      // 상위경로 → traversal 시도. '\' 로 나뉜 조각도 본다(폴더 경로는 '\' 를 보존하므로 Windows 식 해석 방지, WD-776).
      // 파일명(clean)은 이미
      // '\'→'/' 라 세그먼트 자체가 ".." 인지와 같다.
      if (List.of(seg.split("\\\\", -1)).contains("..")) return null;
      segments.add(seg);
    }
    return segments;
  }

  /** UTF-8 바이트 길이 — S3 키 상한이 바이트 기준이라 문자 수가 아닌 바이트로 잰다. */
  private static int utf8Length(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * UTF-8 바이트 상한 내로 파일명을 자르되 확장자(마지막 세그먼트 안의 마지막 '.') 이후는 보존한다. 확장자는 마지막 세그먼트(파일명) 안에서만 찾는다 — 하위 폴더
   * 이름의 '.'(「aaa….d/nnn」)를 확장자로 읽으면 폴더 이름이 잘려 다른 폴더가 된다(WD-776). 세그먼트 첫 글자의 '.'(「.gitignore」)는 확장자가
   * 아니다. 멀티바이트 문자가 쪼개지지 않게 코드포인트 단위로 줄인다 — char 단위로 줄이면 이모지 같은 서로게이트 쌍이 반쪽만 남아 '?' 로 인코딩된 깨진 키가
   * 된다(WD-776).
   */
  private static String truncateUtf8PreservingExt(String s, int maxBytes) {
    if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return s;
    int dot = s.lastIndexOf('.');
    boolean hasExt = dot > s.lastIndexOf('/') + 1;
    String ext = hasExt ? s.substring(dot) : "";
    String base = hasExt ? s.substring(0, dot) : s;
    int budget = maxBytes - ext.getBytes(StandardCharsets.UTF_8).length;
    // 확장자만으로도 예산을 넘으면 확장자 보존을 포기하고 전체 예산으로 자른다.
    if (budget < 0) {
      budget = maxBytes;
      ext = "";
      base = s;
    }
    String truncated = base;
    while (truncated.getBytes(StandardCharsets.UTF_8).length > budget && !truncated.isEmpty()) {
      truncated = truncated.substring(0, truncated.offsetByCodePoints(truncated.length(), -1));
    }
    return truncated + ext;
  }
}
