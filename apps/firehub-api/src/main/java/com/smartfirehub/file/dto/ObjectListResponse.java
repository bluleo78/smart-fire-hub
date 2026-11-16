package com.smartfirehub.file.dto;

import java.util.List;

/**
 * 오브젝트 목록 페이지 응답. nextToken 이 있으면 다음 페이지 존재.
 *
 * <p>WD-761 에서 폴더 모드·검색 모드가 생기며 두 필드를 더했다.
 *
 * <ul>
 *   <li>{@code folders}: 폴더 모드의 하위 폴더(common prefix). 다른 모드에선 빈 목록.
 *   <li>{@code truncated}: 검색 모드가 요청당 훑기 상한에 닿아 일부만 찾았음. 이때 이어 보기는 없다(hasMore=false).
 * </ul>
 */
public record ObjectListResponse(
    List<ObjectItemResponse> objects,
    List<ObjectFolderResponse> folders,
    String nextToken,
    boolean hasMore,
    boolean truncated) {

  /** 기존(재귀 평면 목록) 응답 생성자 — 폴더 없음·잘림 없음. 기존 호출부·시험 호환용으로 남긴다. */
  public ObjectListResponse(List<ObjectItemResponse> objects, String nextToken, boolean hasMore) {
    this(objects, List.of(), nextToken, hasMore, false);
  }
}
