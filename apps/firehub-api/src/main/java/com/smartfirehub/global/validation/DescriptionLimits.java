package com.smartfirehub.global.validation;

/**
 * 자산 설명(description) 길이 상한과 안내 문구의 단일 원본(WD-520).
 *
 * <p>저장 쿼리·차트·데이터셋·파이프라인·API 연결·리포트 양식·대시보드의 목록 응답은 설명 전문을 싣는다. 상한이 없으면 20MB 설명 하나가 모든 사용자의 목록 응답을
 * 부풀린다(대시보드는 WD-425 에서 먼저 막았다). 자산마다 같은 값을 쓰도록 숫자를 여기 한 곳에만 두고, 각 요청 DTO 의 {@code @Size} 가 이 상수를
 * 참조한다 — 값이 자산마다 갈라지지 않게 하기 위해서다.
 *
 * <p>각 DTO 의 설명 필드에 {@code @Size(max = MAX_LENGTH, message = …)} 를 붙인다. 생략(null)은 {@code @Size} 가
 * 통과시키므로 수정 요청의 "변경 없음" 은 그대로 허용된다. 문구는 어노테이션 속성에 넣어야 해 컴파일 시간 상수여야 한다 — 숫자를 바꾸면 아래 문구의 "2,000" 도
 * 함께 바꾼다. 화면(웹 {@code MAX_DESCRIPTION_LENGTH})·AI 도구 입력 검증도 같은 값·문구를 쓴다.
 */
public final class DescriptionLimits {

  /** 설명 길이 상한 — 2,000자. 화면에서 쓰는 설명은 몇 문장이라 넉넉하다. */
  public static final int MAX_LENGTH = 2_000;

  /** 저장 쿼리 설명 상한 초과 안내 — 생성·수정 요청이 같이 쓴다. */
  public static final String QUERY_TOO_LONG = "쿼리 설명은 최대 2,000자까지 입력할 수 있습니다.";

  /** 차트 설명 상한 초과 안내 — 생성·수정 요청이 같이 쓴다. */
  public static final String CHART_TOO_LONG = "차트 설명은 최대 2,000자까지 입력할 수 있습니다.";

  /** 대시보드 설명 상한 초과 안내(WD-425 문구 그대로). */
  public static final String DASHBOARD_TOO_LONG = "대시보드 설명은 최대 2,000자까지 입력할 수 있습니다.";

  /** 데이터셋 설명 상한 초과 안내 — 생성·수정·복제 요청이 같이 쓴다. */
  public static final String DATASET_TOO_LONG = "데이터셋 설명은 최대 2,000자까지 입력할 수 있습니다.";

  /** 파이프라인 설명 상한 초과 안내 — 생성·수정 요청과 API 임포트(새 파이프라인 설명)가 같이 쓴다. */
  public static final String PIPELINE_TOO_LONG = "파이프라인 설명은 최대 2,000자까지 입력할 수 있습니다.";

  /** API 연결 설명 상한 초과 안내 — 생성·수정 요청이 같이 쓴다. */
  public static final String API_CONNECTION_TOO_LONG = "API 연결 설명은 최대 2,000자까지 입력할 수 있습니다.";

  /** 리포트 양식 설명 상한 초과 안내 — 생성·수정 요청이 같이 쓴다. */
  public static final String REPORT_TEMPLATE_TOO_LONG = "리포트 양식 설명은 최대 2,000자까지 입력할 수 있습니다.";

  private DescriptionLimits() {}
}
