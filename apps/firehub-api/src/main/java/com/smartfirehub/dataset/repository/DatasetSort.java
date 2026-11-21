package com.smartfirehub.dataset.repository;

/**
 * 데이터셋 목록의 정렬 기준(WD-874).
 *
 * <p>홈 「최근 데이터셋」 카드가 최신 순으로 보이도록 목록 API 에 정렬 인자를 더하기 위한 값이다. 열은 허용 목록({@link Field})으로만 고르게 해 SQL 에
 * 사용자 입력이 문자열로 끼지 않게 한다. 정렬 인자를 주지 않는 호출자는 {@link #DEFAULT}(id 오름차순)로 지금 순서를 그대로 유지한다.
 */
public record DatasetSort(Field field, boolean descending) {

  /** 정렬 가능한 열. */
  public enum Field {
    ID,
    CREATED_AT,
    NAME
  }

  /** 정렬 인자가 없을 때의 기본 — id 오름차순(예전과 같은 순서). */
  public static final DatasetSort DEFAULT = new DatasetSort(Field.ID, false);

  /**
   * 요청 문자열을 정렬 값으로 바꾼다.
   *
   * <p>null·빈 문자열은 없는 것으로 보고 해당 부분만 기본(id·asc)을 쓴다. 허용 밖 값은 대소문자가 달라도 거절한다 — 조용히 기본으로 돌리면 호출자가 잘못된
   * 순서를 눈치채지 못한다. {@link IllegalArgumentException} 은 전역 예외 처리기가 400 으로 바꾼다.
   */
  public static DatasetSort parse(String sortBy, String sortDir) {
    Field field = Field.ID;
    if (sortBy != null && !sortBy.isEmpty()) {
      field =
          switch (sortBy) {
            case "id" -> Field.ID;
            case "createdAt" -> Field.CREATED_AT;
            case "name" -> Field.NAME;
            default ->
                throw new IllegalArgumentException(
                    "정렬 기준은 id·createdAt·name 중 하나여야 합니다: " + sortBy);
          };
    }
    boolean descending = false;
    if (sortDir != null && !sortDir.isEmpty()) {
      descending =
          switch (sortDir) {
            case "asc" -> false;
            case "desc" -> true;
            default -> throw new IllegalArgumentException("정렬 방향은 asc·desc 중 하나여야 합니다: " + sortDir);
          };
    }
    return new DatasetSort(field, descending);
  }
}
