package com.smartfirehub.global.validation;

/**
 * 검색 질의(query) 길이 상한과 안내 문구의 단일 원본(WD-600).
 *
 * <p>데이터셋 검색({@code POST /datasets/search})·문서 검색({@code POST /documents/search})은 질의를 그대로 임베딩
 * provider 로 보내고 키워드 검색 SQL 에도 싣는다. 상한이 없으면 수 MB 질의 하나로 외부 임베딩 비용과 DB 부하를 키울 수 있어, 두 요청 DTO 가 같은 값을
 * 쓰도록 여기 한 곳에 둔다.
 *
 * <p>감사 로그 목록({@code GET /admin/audit-logs} 의 search 파라미터, WD-1111)도 같은 상한을 쓴다. 플랫폼 감사 로그({@code
 * GET /api/platform/audit-logs} 의 actor·target, WD-1130)도 같다.
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
   * maxLength), admin {@code MAX_AUDIT_SEARCH_LENGTH}(apps/firehub-admin/src/lib/search-limits.ts —
   * 플랫폼 감사 로그 검색 입력 maxLength).
   */
  public static final int MAX_QUERY_LENGTH = 1_000;

  /** 상한 초과 안내 — 어노테이션 속성이라 컴파일 시간 상수여야 한다(숫자를 바꾸면 문구의 "1,000" 도 바꾼다). */
  public static final String QUERY_TOO_LONG = "검색어는 최대 1,000자까지 입력할 수 있습니다.";

  /**
   * 자유 문자열 필터(감사 로그 actionType·resource·result 등)의 최대 길이 — 100자(WD-1131). 요청 헤더 한도가 32KB 로 넓어져 긴 값이
   * 그대로 DB 조건으로 가므로 컨트롤러에서 막는다. 대응 상수(값을 바꾸면 함께 바꾼다): ai-agent {@code MAX_AUDIT_FILTER_LENGTH}
   * (apps/firehub-ai-agent/src/mcp/api-client/request-limits.ts).
   */
  public static final int MAX_FILTER_LENGTH = 100;

  /** 필터 길이 초과 안내 — 상수 {@link #MAX_FILTER_LENGTH} 로 만든 컴파일 시간 상수라 어노테이션 속성에 쓸 수 있다. */
  public static final String FILTER_TOO_LONG = "필터 값은 최대 " + MAX_FILTER_LENGTH + "자까지 입력할 수 있습니다.";

  private SearchQueryLimits() {}
}
