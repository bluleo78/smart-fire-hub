package com.smartfirehub.global.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 다운로드 응답의 Content-Disposition 헤더 조립 — 데이터셋 내보내기와 쿼리 결과 내보내기가 같은 파일 이름 규칙을 쓰도록 한 곳에 둔다. 안전
 * 문자(영숫자·한글·{@code ._-})만 남긴 이름과 그 RFC 5987 인코딩 이름을 함께 싣는다.
 */
public final class ContentDispositions {

  private ContentDispositions() {}

  /** attachment 헤더 값 — {@code attachment; filename="..."; filename*=UTF-8''...}. */
  public static String attachment(String filename) {
    String sanitized = filename.replaceAll("[^a-zA-Z0-9가-힣._\\-]", "_");
    String encoded = URLEncoder.encode(sanitized, StandardCharsets.UTF_8).replace("+", "%20");
    return "attachment; filename=\"" + sanitized + "\"; filename*=UTF-8''" + encoded;
  }
}
