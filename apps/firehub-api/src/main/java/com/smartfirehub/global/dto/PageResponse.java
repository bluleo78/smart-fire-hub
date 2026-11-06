package com.smartfirehub.global.dto;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(
    List<T> content, int page, int size, long totalElements, int totalPages) {

  /** 페이지 정보(page·size·건수)는 그대로 두고 내용만 다른 형태로 바꾼 페이지를 돌려준다 — 저장소 응답을 API 응답 DTO 로 옮길 때 쓴다. */
  public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
    return new PageResponse<>(
        content.stream().<R>map(mapper).toList(), page, size, totalElements, totalPages);
  }
}
