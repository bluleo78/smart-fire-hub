package com.smartfirehub.dataset.dto;

/** 공간 쿼리 필터. queryData()의 선택적 파라미터. null이면 기존 동작과 동일 (공간 조건 없음). */
public sealed interface SpatialFilter {
  String geometryColumn();

  record Nearby(String geometryColumn, double longitude, double latitude, double radiusMeters)
      implements SpatialFilter {}

  record Bbox(
      String geometryColumn,
      double minLongitude,
      double minLatitude,
      double maxLongitude,
      double maxLatitude)
      implements SpatialFilter {}
}
