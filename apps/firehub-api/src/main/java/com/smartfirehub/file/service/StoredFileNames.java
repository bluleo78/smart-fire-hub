package com.smartfirehub.file.service;

import com.smartfirehub.file.exception.StoragePathEscapeException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * 업로드 파일명 공용 규칙 (WD-610). 채팅
 * 첨부(FileUploadService)·문서(DocumentStorageService/DocumentIngestionService)·데이터
 * 가져오기(DataImportService)가 같은 규칙을 쓴다.
 *
 * <ul>
 *   <li><b>저장 파일명</b>: 늘 {@code <uuid>[.<ext>]}. 원본 파일명은 사용자 입력이라 마지막 점 뒤에 {@code /}·{@code
 *       \}·{@code ..}·초장문이 올 수 있고, 그대로 붙이면 저장 경로가 하위 폴더를 가리키거나 파일명 길이 제한을 넘어 500 이 났다. 그래서 확장자는
 *       {@code [a-z0-9]{1,10}}(소문자화 후)일 때만 쓰고, <b>아니면 버린다</b> — 글자를 지워 원본과 다른 확장자를 만들지 않는다(예전 문서
 *       저장은 {@code v1/final} → {@code v1final} 로 바꿨다). 두 저장소 모두 이 한 규칙이며, 원본 이름은 DB 에 따로 보존되므로 다운로드
 *       이름에는 영향이 없다.
 *   <li><b>표시(보존) 이름</b>: DB original_name 컬럼이 VARCHAR(255) 라 255 코드포인트로 자른다. 거부하지 않는 이유는 기존 데이터
 *       가져오기 정책(자르기)과 맞추고, 다중 파일 업로드에서 일부만 저장되는 부분 성공을 피하기 위해서다.
 * </ul>
 */
@Slf4j
public final class StoredFileNames {

  /** 저장 파일명에 허용하는 확장자 형식 — 소문자 영숫자 1~10자. 경로 구분자·점·유니코드가 끼어들 여지가 없다. */
  private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,10}");

  /** original_name 컬럼(uploaded_files·document_file·데이터 가져오기 잡) 길이 상한(코드포인트). */
  public static final int MAX_DISPLAY_NAME_CODE_POINTS = 255;

  private StoredFileNames() {}

  /**
   * 원본 파일명에서 저장 파일명에 붙일 안전한 확장자를 뽑는다.
   *
   * <p>마지막 점 뒤를 {@link Locale#ROOT} 로 소문자화해 {@code [a-z0-9]{1,10}} 이면 그대로, 아니면(점 없음·빈 꼬리·구분자 포함·10자
   * 초과·비ASCII 등) 빈 문자열을 돌려준다.
   *
   * @param originalName 업로드된 원본 파일명(null 가능)
   * @return 점 없는 소문자 확장자, 또는 빈 문자열
   */
  public static String safeExtension(String originalName) {
    if (originalName == null) {
      return "";
    }
    int dot = originalName.lastIndexOf('.');
    if (dot < 0) {
      return "";
    }
    String ext = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
    return SAFE_EXTENSION.matcher(ext).matches() ? ext : "";
  }

  /**
   * 저장 폴더 안의 새 저장 경로({@code dir/<uuid>[.<ext>]})를 만든다.
   *
   * <p>방어 겹으로 정규화한 경로가 {@code dir} 안인지 확인한다. 안전한 확장자만 붙이므로 정상이라면 벗어날 수 없고, 벗어나면 코드 결함이라 500 으로 끝낸다.
   * 저장 파일명은 서버 로그에만 남기고 클라이언트 메시지에는 싣지 않는다.
   *
   * @param dir 저장 폴더(절대 경로 권장)
   * @param originalName 업로드된 원본 파일명(null 가능 — 확장자만 쓴다)
   * @return 저장할 파일 경로
   * @throws StoragePathEscapeException 경로가 폴더를 벗어난 경우(코드 결함)
   */
  public static Path resolveInside(Path dir, String originalName) {
    String ext = safeExtension(originalName);
    String storedName = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
    Path target = dir.resolve(storedName);
    if (!target.normalize().startsWith(dir.normalize())) {
      log.error("저장 경로가 저장 폴더를 벗어남: dir={}, storedName={}", dir, storedName);
      throw new StoragePathEscapeException();
    }
    return target;
  }

  /**
   * DB 에 보존할 원본(표시) 이름을 255 코드포인트 이하로 만든다.
   *
   * <p>길이가 넘으면 자른다. 이때 서로게이트 쌍(이모지 등)을 가르지 않도록 코드포인트 단위로 자르고, 끝의 확장자가 안전한 형식({@link
   * #safeExtension})이면 앞부분만 잘라 {@code .ext} 를 살린다 — 다운로드 이름·확장자 기반 처리(문서 텍스트 추출 등)가 계속 맞게 동작하도록.
   *
   * @param originalName 업로드된 원본 파일명(null 이면 null)
   * @return 255 코드포인트 이하의 이름
   */
  public static String displayName(String originalName) {
    if (originalName == null
        || originalName.codePointCount(0, originalName.length()) <= MAX_DISPLAY_NAME_CODE_POINTS) {
      return originalName;
    }
    String ext = safeExtension(originalName);
    if (ext.isEmpty()) {
      return truncateCodePoints(originalName, MAX_DISPLAY_NAME_CODE_POINTS);
    }
    // 원본 대소문자를 살리기 위해 소문자화된 ext 가 아니라 원문 꼬리(점 포함)를 붙인다. 꼬리는 ASCII 영숫자라 길이 = 코드포인트 수.
    int dot = originalName.lastIndexOf('.');
    String tail = originalName.substring(dot);
    String base = originalName.substring(0, dot);
    return truncateCodePoints(base, MAX_DISPLAY_NAME_CODE_POINTS - tail.length()) + tail;
  }

  /** 문자열을 앞에서부터 최대 {@code maxCodePoints} 코드포인트로 자른다(서로게이트 쌍을 가르지 않는다). */
  private static String truncateCodePoints(String s, int maxCodePoints) {
    if (s.codePointCount(0, s.length()) <= maxCodePoints) {
      return s;
    }
    return s.substring(0, s.offsetByCodePoints(0, maxCodePoints));
  }
}
