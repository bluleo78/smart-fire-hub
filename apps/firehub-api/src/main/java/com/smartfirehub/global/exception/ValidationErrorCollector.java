package com.smartfirehub.global.exception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 입력 검증 실패의 필드별 문구를 모아, 필드마다 하나를 결정적으로 고르는 수집기(WD-570, WD-640).
 *
 * <p>왜: 한 필드가 여러 제약을 동시에 어기면 Hibernate Validator 의 위반 목록 순서가 정해져 있지 않다. WD-570 은 필수값 문구만 우선하고 나머지는
 * 「처음 만난 것」을 남겨, 필수값이 아닌 위반이 둘 이상이면(예: @Size + @Pattern) 응답 문구가 요청마다 달라질 수 있었다. 여기서는 후보를 모두 모은 뒤 ①
 * 제약 종류의 고정 순위 ② 제약 이름 ③ 문구 문자열 순으로 골라, 들어온 순서와 상관없이 같은 결과를 낸다.
 *
 * <p>필드 사이 순서도 고정한다: 각 필드에서 고른 문구의 제약 순위(필수값 먼저) → 필드 이름(숫자 첨자는 수 크기로, rows[2] 가 rows[10] 앞) 순이다.
 * 웹의 토스트는 errors 의 첫 값을 보여 주므로(pickBestMessage) 맵 순서가 곧 사용자에게 보이는 문구다 — 이름순만이면 데이터셋 생성에서
 * description 의 길이 오류가 name 의 필수값 오류보다 먼저 보였다.
 *
 * <p>필드에 딸리지 않은 오류(클래스 단위 합성 제약, 교차 파라미터 제약)는 버리지 않고 맨 뒤의 {@link #GLOBAL_KEY} 아래에 모은다. 예전에는
 * getFieldErrors() 만 읽어 이런 위반이 errors={} 로 사라지고 일반 문구만 남았다.
 */
final class ValidationErrorCollector {

  /**
   * 필드가 없는 검증 오류(클래스 단위·교차 파라미터 제약)를 담는 예약 키 — 항상 맵의 맨 뒤다. 웹 applyFieldErrors 는 폼이 모르는 키를 폼
   * 상단(root)으로 보내고, 필드 오류가 없으면 토스트가 이 문구를 보인다. 여러 개면 정렬·중복 제거해 한 문장으로 잇는다.
   */
  static final String GLOBAL_KEY = "_global";

  /**
   * 바인딩 타입 변환 실패(FieldError code) — Spring 기본 문구가 영어 「Failed to convert property value…」라 한국어로 바꾼다.
   */
  static final String TYPE_MISMATCH_CODE = "typeMismatch";

  /** 타입 변환 실패의 한국어 문구. */
  static final String TYPE_MISMATCH_MESSAGE = "값의 형식이 올바르지 않습니다.";

  /**
   * 제약 종류별 우선순위(앞일수록 먼저 보인다). 필수값 → 타입 → 길이·범위 → 형식 순 — 빈 값에는 「형식이 올바르지 않습니다」보다 「필수 입력값입니다」가, 숫자 칸의
   * 문자에는 범위보다 형식 오류가 맞는 안내다. 목록에 없는 제약(사용자 정의 등)은 맨 뒤이고, 그 안에서는 이름·문구 순으로 정한다.
   */
  private static final List<String> RANK =
      List.of(
          "NotBlank",
          "NotNull",
          "NotEmpty",
          TYPE_MISMATCH_CODE,
          "Size",
          // 비밀번호 UTF-8 바이트 상한(WD-605)도 길이 제약이라 형식(Pattern)보다 먼저 보인다.
          "PasswordMaxBytes",
          "Length",
          "Min",
          "Max",
          "DecimalMin",
          "DecimalMax",
          "Range",
          "Positive",
          "PositiveOrZero",
          "Negative",
          "NegativeOrZero",
          "Digits",
          "Pattern",
          "Email",
          "URL",
          "Past",
          "PastOrPresent",
          "Future",
          "FutureOrPresent");

  /** 같은 필드의 후보 하나 — 제약 이름(code)과 사용자 문구. */
  private record Candidate(String code, String message) {}

  private static final Comparator<Candidate> ORDER =
      Comparator.comparingInt((Candidate c) -> rank(c.code()))
          .thenComparing(c -> c.code() == null ? "" : c.code())
          .thenComparing(c -> c.message() == null ? "" : c.message());

  /** 제약 이름 → 순위 — RANK 에서 한 번 만들어 O(1) 로 찾는다. */
  private static final Map<String, Integer> RANK_INDEX =
      IntStream.range(0, RANK.size()).boxed().collect(Collectors.toMap(RANK::get, i -> i));

  private final Map<String, List<Candidate>> byField = new HashMap<>();
  private final List<String> globalMessages = new ArrayList<>();

  /** 순위표의 위치. 없으면 맨 뒤. */
  private static int rank(String code) {
    return code == null ? RANK.size() : RANK_INDEX.getOrDefault(code, RANK.size());
  }

  /**
   * 필드 경로 비교 — 숫자 묶음은 수 크기로, 나머지는 문자 순으로 비교한다(자연 정렬). 「rows[10]」이 「rows[2]」 앞에 오던 사전순 문제를 막는다.
   *
   * <p>숫자 묶음은 수로 파싱하지 않고 제자리에서 비교한다(WD-654) — 맵 키는 클라이언트가 보낸 임의 길이 숫자일 수 있어 long 은 넘치고, 비교마다
   * BigInteger 로 읽으면 정렬 중 긴 키를 반복 파싱한다.
   */
  static int compareFieldPath(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      if (Character.isDigit(a.charAt(i)) && Character.isDigit(b.charAt(j))) {
        int ei = digitsEnd(a, i);
        int ej = digitsEnd(b, j);
        int cmp = compareDigitRuns(a, i, ei, b, j, ej);
        if (cmp != 0) {
          return cmp;
        }
        i = ei;
        j = ej;
      } else {
        if (a.charAt(i) != b.charAt(j)) {
          return Character.compare(a.charAt(i), b.charAt(j));
        }
        i++;
        j++;
      }
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  /** 두 숫자 묶음 a[ai,ae)·b[bi,be) 를 수 크기로 비교한다 — 앞자리 0 제거 → 자릿수 → 자리별 값. 0 만 있는 묶음끼리는 같다. */
  private static int compareDigitRuns(String a, int ai, int ae, String b, int bi, int be) {
    while (ai < ae && Character.digit(a.charAt(ai), 10) == 0) {
      ai++;
    }
    while (bi < be && Character.digit(b.charAt(bi), 10) == 0) {
      bi++;
    }
    int lengthCmp = Integer.compare(ae - ai, be - bi);
    if (lengthCmp != 0) {
      return lengthCmp;
    }
    for (; ai < ae; ai++, bi++) {
      int cmp =
          Integer.compare(Character.digit(a.charAt(ai), 10), Character.digit(b.charAt(bi), 10));
      if (cmp != 0) {
        return cmp;
      }
    }
    return 0;
  }

  /** from 부터 이어지는 숫자 묶음의 끝 위치. */
  private static int digitsEnd(String s, int from) {
    int k = from;
    while (k < s.length() && Character.isDigit(s.charAt(k))) {
      k++;
    }
    return k;
  }

  /**
   * 필드 오류 후보를 더한다.
   *
   * @param code 제약 애너테이션의 단순 이름(예: "NotBlank") 또는 "typeMismatch". 모르면 null.
   */
  void addField(String field, String code, String message) {
    String text = TYPE_MISMATCH_CODE.equals(code) ? TYPE_MISMATCH_MESSAGE : message;
    byField.computeIfAbsent(field, k -> new ArrayList<>()).add(new Candidate(code, text));
  }

  /** 필드에 딸리지 않은 오류 문구를 더한다(클래스 단위·교차 파라미터 제약). */
  void addGlobal(String message) {
    if (message != null && !message.isBlank()) {
      globalMessages.add(message);
    }
  }

  /** 필드마다 하나씩 고른 결과(순서는 클래스 설명 참고). 필드 없는 오류는 맨 뒤 {@link #GLOBAL_KEY} 에 정렬·중복 제거 후 이어 붙인다. */
  Map<String, String> toErrors() {
    // 필드마다 후보 하나를 고른 뒤, 고른 문구의 제약 순위 → 필드 경로 순으로 정렬한다(맵 자체 순서는 쓰지 않는다).
    Map<String, Candidate> chosen = new HashMap<>();
    byField.forEach((field, list) -> chosen.put(field, list.stream().min(ORDER).get()));
    Map<String, String> errors = new LinkedHashMap<>();
    chosen.entrySet().stream()
        .sorted(
            Comparator.comparingInt((Map.Entry<String, Candidate> e) -> rank(e.getValue().code()))
                .thenComparing(Map.Entry::getKey, ValidationErrorCollector::compareFieldPath)
                // 「a01」·「a1」처럼 수로는 같은 경로도 순서가 흔들리지 않게 문자열로 한 번 더 가른다.
                .thenComparing(Map.Entry::getKey))
        .forEach(e -> errors.put(e.getKey(), e.getValue().message()));
    if (!globalMessages.isEmpty()) {
      errors.put(
          GLOBAL_KEY, String.join(" ", globalMessages.stream().distinct().sorted().toList()));
    }
    return errors;
  }
}
