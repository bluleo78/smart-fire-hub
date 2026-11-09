package com.smartfirehub.global.validation;

/**
 * 검색 질의(query) 길이 상한과 안내 문구의 단일 원본(WD-600).
 *
 * <p>데이터셋 검색({@code POST /datasets/search})·문서 검색({@code POST /documents/search})은 질의를 그대로 임베딩
 * provider 로 보내고 키워드 검색 SQL 에도 싣는다. 상한이 없으면 수 MB 질의 하나로 외부 임베딩 비용과 DB 부하를 키울 수 있어, 두 요청 DTO 가 같은 값을
 * 쓰도록 여기 한 곳에 둔다.
 *
 * <p>길이 기준은 {@code String.length()}(UTF-16 코드 단위)다 — 이모지 같은 서로게이트 쌍은 2자로 세지만 상한이 넉넉해 실사용에 영향이 없다.
 * 생략(null)은 {@code @Size} 가 통과시키므로 빈/누락 질의의 기존 처리(서비스의 빈 검색어 400 등)는 그대로다.
 */
public final class SearchQueryLimits {

  /**
   * 검색 질의 길이 상한 — 1,000자. 사람이 입력하는 검색어·AI 도구 질의 모두 몇 문장 이내라 넉넉하다. 대응 상수(값을 바꾸면 함께 바꾼다): ai-agent
   * {@code MAX_SEARCH_QUERY_LENGTH}(apps/firehub-ai-agent/src/mcp/api-client/request-limits.ts —
   * 보내기 전에 이 길이로 자른다), web {@code
   * MAX_SEARCH_QUERY_LENGTH}(apps/firehub-web/src/lib/validations/search-query.ts — 검색 입력
   * maxLength).
   */
  public static final int MAX_QUERY_LENGTH = 1_000;

  /** 상한 초과 안내 — 어노테이션 속성이라 컴파일 시간 상수여야 한다(숫자를 바꾸면 문구의 "1,000" 도 바꾼다). */
  public static final String QUERY_TOO_LONG = "검색어는 최대 1,000자까지 입력할 수 있습니다.";

  private SearchQueryLimits() {}
}
