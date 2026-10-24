package com.smartfirehub.global.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 다운로드 응답의 Content-Disposition 헤더 조립 — 데이터셋 내보내기·쿼리 결과 내보내기·비동기 내보내기 파일·리포트 PDF 가 같은 파일 이름 규칙을 쓰도록
 * 한 곳에 둔다.
 *
 * <p>RFC 6266/5987 형식으로 두 이름을 함께 싣는다.
 *
 * <ul>
 *   <li>{@code filename="..."} — ASCII 만 남긴 대체 이름. Tomcat 은 ISO-8859-1 밖 문자(한글 등)가 든 헤더 값을
 *       "invalid" 로 보고 헤더째 지우므로(WD-249), 여기에는 비ASCII 를 절대 넣지 않는다.
 *   <li>{@code filename*=UTF-8''...} — 안전 문자(영숫자·한글·{@code ._-})만 남긴 원래 이름의 퍼센트 인코딩. 브라우저·curl -J·웹
 *       클라이언트는 이쪽을 먼저 쓰므로 한글 이름이 그대로 저장된다.
 * </ul>
 */
public final class ContentDispositions {

  private ContentDispositions() {}

  /** attachment 헤더 값 — {@code attachment; filename="<ASCII 대체>"; filename*=UTF-8''<인코딩 이름>}. */
  public static String attachment(String filename) {
    // 따옴표·역슬래시·CR/LF·경로 구분자 등은 모두 '_' 로 바뀌므로 헤더 주입·따옴표 깨짐이 없다.
    String sanitized = filename.replaceAll("[^a-zA-Z0-9가-힣._\\-]", "_");
    // ASCII 대체 이름: 남은 비ASCII(한글)를 '_' 로 바꾼다 — 확장자(.csv 등)는 ASCII 라 그대로 남는다.
    String asciiFallback = sanitized.replaceAll("[^\\x21-\\x7E]", "_");
    // URLEncoder 는 공백을 '+' 로 바꾸므로 RFC 5987 의 %20 으로 되돌린다(정제 후엔 공백이 없지만 방어적으로 둔다).
    String encoded = URLEncoder.encode(sanitized, StandardCharsets.UTF_8).replace("+", "%20");
    return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded;
  }
}
