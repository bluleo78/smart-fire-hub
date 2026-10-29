package com.smartfirehub.dataset.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 위치(GEOMETRY) 칸에 들어온 GeoJSON 문자열을 검증하고, DB 가 저장할 geometry GeoJSON 으로 정규화한다(WD-201).
 *
 * <p>행 추가·편집 API({@link DatasetDataService})와 파일 임포트({@code DataValidationService})가 같은 규칙을 쓰도록 한
 * 곳에 둔다. 저장은 {@code ST_GeomFromGeoJSON(?)} 로 하는데 PostGIS 는 geometry 객체만 받고 Feature 는 "invalid
 * GeoJson representation" 으로 거부한다. 그래서:
 *
 * <ul>
 *   <li>geometry(Point·LineString·…·GeometryCollection) — 원문 그대로 통과(좌표 표기를 바꾸지 않는다).
 *   <li>Feature — 지도 도구가 흔히 내보내는 형태라 받아 주되, 안의 geometry 만 꺼내 저장한다. geometry 가 없거나 null 이면 거부.
 *   <li>FeatureCollection — 한 칸에는 위치 하나만 담기므로 거부.
 * </ul>
 *
 * <p>오류 문구는 사용자에게 그대로 보이므로 한국어로 쓰고, JSON 파서 예외 원문(내부 위치·토큰 정보)은 싣지 않는다 — 디버그 로그로만 남긴다.
 */
public final class GeoJsonValues {

  private static final Logger log = LoggerFactory.getLogger(GeoJsonValues.class);

  /** 저장 가능한 geometry 타입 — Feature 는 안의 geometry 를 꺼내 이 집합으로 다시 검사한다. */
  private static final Set<String> GEOMETRY_TYPES =
      Set.of(
          "Point",
          "LineString",
          "Polygon",
          "MultiPoint",
          "MultiLineString",
          "MultiPolygon",
          "GeometryCollection");

  /** 좌표 숫자를 double 로 바꾸지 않고 원문 그대로 읽는 리더 — Feature 에서 꺼낸 geometry 를 다시 직렬화할 때 좌표 자릿수가 바뀌지 않게 한다. */
  private static final ObjectMapper EXACT_MAPPER =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

  static final String INVALID_FORMAT_MESSAGE =
      "GeoJSON 형식이 아닙니다. 예: {\"type\":\"Point\",\"coordinates\":[126.97,37.56]}";
  static final String FEATURE_COLLECTION_MESSAGE =
      "FeatureCollection 은 저장할 수 없습니다. 한 칸에는 위치 하나만 들어가므로 Feature 하나나 geometry 를 입력하세요";
  static final String FEATURE_WITHOUT_GEOMETRY_MESSAGE =
      "Feature 에 geometry 가 없습니다. 위치가 없으면 값을 비워 두세요";

  static final String OUT_OF_RANGE_MESSAGE =
      "좌표 범위를 벗어났습니다. 경도는 -180~180, 위도는 -90~90 사이여야 합니다 (WGS84, [경도, 위도] 순서)";

  /** 화면에 되돌려 보여 줄 type 값 길이 상한 — 사용자가 넣은 긴 문자열이 오류 문구를 덮지 않게 자른다. */
  private static final int MAX_SHOWN_TYPE_LENGTH = 40;

  private GeoJsonValues() {}

  /**
   * GeoJSON 문자열을 검증하고 저장할 geometry GeoJSON 을 돌려준다.
   *
   * @param value 사용자가 입력한 GeoJSON 문자열(빈 문자열 처리는 호출자가 먼저 한다)
   * @return geometry 입력이면 원문 그대로, Feature 면 안의 geometry 를 직렬화한 문자열
   * @throws IllegalArgumentException 한국어 사유 — 형식 오류, FeatureCollection, geometry 없는 Feature 등
   */
  public static String toStorableGeometry(String value) {
    JsonNode node;
    try {
      node = EXACT_MAPPER.readTree(value);
    } catch (JsonProcessingException e) {
      // 파서 원문(토큰·위치)은 사용자 문구에 싣지 않는다 — 원인 추적용으로만 남긴다.
      log.debug("GeoJSON 파싱 실패: {}", e.getOriginalMessage());
      throw new IllegalArgumentException(INVALID_FORMAT_MESSAGE);
    }
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException(INVALID_FORMAT_MESSAGE);
    }

    String type = typeOf(node);
    if ("FeatureCollection".equals(type)) {
      throw new IllegalArgumentException(FEATURE_COLLECTION_MESSAGE);
    }
    if ("Feature".equals(type)) {
      // Feature 는 안의 geometry 만 저장한다 — 속성(properties)은 칸 하나에 담을 곳이 없어 버린다.
      JsonNode geometry = node.get("geometry");
      if (geometry == null || geometry.isNull()) {
        throw new IllegalArgumentException(FEATURE_WITHOUT_GEOMETRY_MESSAGE);
      }
      if (!geometry.isObject()) {
        throw new IllegalArgumentException(INVALID_FORMAT_MESSAGE);
      }
      String geometryType = typeOf(geometry);
      if ("Feature".equals(geometryType) || "FeatureCollection".equals(geometryType)) {
        // geometry 자리에 Feature 를 겹쳐 넣은 값 — "Feature 만 가능"이라는 모순 문구 대신 형식 오류로 안내한다.
        throw new IllegalArgumentException(INVALID_FORMAT_MESSAGE);
      }
      validateGeometry(geometry, geometryType);
      try {
        return EXACT_MAPPER.writeValueAsString(geometry);
      } catch (JsonProcessingException e) {
        // 방금 읽은 트리를 다시 쓰는 것이라 실제로는 일어나지 않는다 — 일어나면 형식 오류로 돌린다.
        log.debug("GeoJSON geometry 직렬화 실패: {}", e.getOriginalMessage());
        throw new IllegalArgumentException(INVALID_FORMAT_MESSAGE);
      }
    }
    validateGeometry(node, type);
    return value;
  }

  /** type 항목을 읽는다 — 없으면 한국어 오류. */
  private static String typeOf(JsonNode node) {
    JsonNode typeNode = node.get("type");
    if (typeNode == null || typeNode.isNull()) {
      throw new IllegalArgumentException("GeoJSON 에 type 항목이 없습니다");
    }
    return typeNode.asText();
  }

  /** geometry 객체 하나를 검사한다 — 저장 가능한 타입인지, 필수 항목(coordinates·geometries)이 있는지. */
  private static void validateGeometry(JsonNode node, String type) {
    if (!GEOMETRY_TYPES.contains(type)) {
      String shown =
          type.length() > MAX_SHOWN_TYPE_LENGTH
              ? type.substring(0, MAX_SHOWN_TYPE_LENGTH) + "…"
              : type;
      throw new IllegalArgumentException(
          "지원하지 않는 GeoJSON type 입니다: "
              + shown
              + " (Point·LineString·Polygon·MultiPoint·MultiLineString·MultiPolygon·GeometryCollection·Feature"
              + " 만 입력할 수 있습니다)");
    }
    if ("GeometryCollection".equals(type)) {
      if (!node.has("geometries")) {
        throw new IllegalArgumentException("GeoJSON 에 geometries 항목이 없습니다");
      }
    } else if (!node.has("coordinates")) {
      throw new IllegalArgumentException("GeoJSON 에 coordinates 항목이 없습니다");
    }
    validateCoordinateRange(node);
  }

  /**
   * 모든 좌표가 WGS84 범위(경도 -180~180, 위도 -90~90, 경계 포함) 안인지 검사한다(WD-340).
   *
   * <p>PostGIS 는 SRID 4326 이어도 범위 밖 좌표(POINT(999 999))를 그대로 저장하고, 지도는 그 좌표로 경계를 잡다가 오류로 화면 전체가
   * 깨지거나(위도) 경도를 360 으로 감아 엉뚱한 곳에 그린다(경도). 그래서 저장 전에 막는다. GeometryCollection 은 안의 geometry 마다, 나머지는
   * coordinates 의 중첩 배열을 끝까지 내려가 위치([경도, 위도, …])를 본다.
   */
  private static void validateCoordinateRange(JsonNode geometry) {
    // geometries 키가 아니라 type 으로 가른다 — Point 에 빈 geometries 를 붙여 범위 검사를 피하지 못하게.
    if ("GeometryCollection".equals(typeOf(geometry))) {
      for (JsonNode child : geometry.get("geometries")) {
        if (child.isObject()) {
          validateCoordinateRange(child);
        }
      }
      return;
    }
    checkPositions(geometry.get("coordinates"));
  }

  /**
   * coordinates 배열을 재귀로 내려가 위치(배열이 아닌 원소로 된 배열)마다 경도·위도 범위를 검사한다.
   *
   * <p>위치의 경도·위도가 숫자가 아니면(예: {@code [127,"999"]}) 형식 오류로 거부한다 — PostGIS 가 문자열을 숫자로 읽어 범위 밖 값이 검사를
   * 건너뛰고 저장되지 않게.
   */
  private static void checkPositions(JsonNode coords) {
    if (coords == null || !coords.isArray() || coords.isEmpty()) {
      return;
    }
    if (!coords.get(0).isArray()) {
      if (coords.size() < 2 || !coords.get(0).isNumber() || !coords.get(1).isNumber()) {
        throw new IllegalArgumentException(INVALID_FORMAT_MESSAGE);
      }
      double lon = coords.get(0).asDouble();
      double lat = coords.get(1).asDouble();
      if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
        throw new IllegalArgumentException(OUT_OF_RANGE_MESSAGE);
      }
      return;
    }
    for (JsonNode child : coords) {
      checkPositions(child);
    }
  }
}
