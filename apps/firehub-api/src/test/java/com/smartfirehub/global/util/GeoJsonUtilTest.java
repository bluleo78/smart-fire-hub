package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/** GeoJsonUtil 단위 테스트 (DB 불필요). */
class GeoJsonUtilTest {

  @Test
  void createPoint_validCoordinates() {
    Point point = GeoJsonUtil.createPoint(126.9780, 37.5665);

    assertThat(point.getX()).isCloseTo(126.9780, within(0.0001));
    assertThat(point.getY()).isCloseTo(37.5665, within(0.0001));
    assertThat(point.getSRID()).isEqualTo(4326);
  }

  @Test
  void fromGeoJson_point() {
    String geoJson = "{\"type\":\"Point\",\"coordinates\":[126.9780,37.5665]}";

    Geometry geom = GeoJsonUtil.fromGeoJson(geoJson);

    assertThat(geom).isInstanceOf(Point.class);
    assertThat(geom.getSRID()).isEqualTo(4326);
    Point point = (Point) geom;
    assertThat(point.getX()).isCloseTo(126.9780, within(0.0001));
    assertThat(point.getY()).isCloseTo(37.5665, within(0.0001));
  }

  @Test
  void fromGeoJson_polygon() {
    String geoJson =
        """
        {"type":"Polygon","coordinates":[[[127.01,37.49],[127.05,37.49],[127.05,37.52],[127.01,37.52],[127.01,37.49]]]}
        """;

    Geometry geom = GeoJsonUtil.fromGeoJson(geoJson);

    assertThat(geom).isInstanceOf(Polygon.class);
    assertThat(geom.getSRID()).isEqualTo(4326);
    assertThat(geom.getCoordinates()).hasSize(5);
  }

  @Test
  void toGeoJson_point() {
    Point point = GeoJsonUtil.createPoint(126.9780, 37.5665);

    String geoJson = GeoJsonUtil.toGeoJson(point);

    assertThat(geoJson).contains("Point");
    assertThat(geoJson).contains("126.978");
    assertThat(geoJson).contains("37.5665");
  }

  @Test
  void toGeoJson_null_returnsNull() {
    assertThat(GeoJsonUtil.toGeoJson(null)).isNull();
  }

  @Test
  void geoJsonRoundTrip_preservesCoordinates() {
    Point original = GeoJsonUtil.createPoint(127.0276, 37.4979);

    String geoJson = GeoJsonUtil.toGeoJson(original);
    Geometry restored = GeoJsonUtil.fromGeoJson(geoJson);

    assertThat(restored).isInstanceOf(Point.class);
    Point restoredPoint = (Point) restored;
    assertThat(restoredPoint.getX()).isCloseTo(original.getX(), within(0.0001));
    assertThat(restoredPoint.getY()).isCloseTo(original.getY(), within(0.0001));
  }

  @Test
  void getLatitude_fromPoint() {
    Point point = GeoJsonUtil.createPoint(126.9780, 37.5665);

    assertThat(GeoJsonUtil.getLatitude(point)).isCloseTo(37.5665, within(0.0001));
    assertThat(GeoJsonUtil.getLongitude(point)).isCloseTo(126.9780, within(0.0001));
  }

  @Test
  void getLatitude_fromNonPoint_returnsNull() {
    String polygonGeoJson =
        """
        {"type":"Polygon","coordinates":[[[127.01,37.49],[127.05,37.49],[127.05,37.52],[127.01,37.52],[127.01,37.49]]]}
        """;
    Geometry polygon = GeoJsonUtil.fromGeoJson(polygonGeoJson);

    assertThat(GeoJsonUtil.getLatitude(polygon)).isNull();
    assertThat(GeoJsonUtil.getLongitude(polygon)).isNull();
  }

  @Test
  void fromGeoJson_invalidInput_throwsException() {
    assertThatThrownBy(() -> GeoJsonUtil.fromGeoJson("not a valid geojson"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid GeoJSON");
  }

  @Test
  void fromGeoJson_multiPolygon() {
    String geoJson =
        """
        {"type":"MultiPolygon","coordinates":[[[[127.01,37.49],[127.05,37.49],[127.05,37.52],[127.01,37.52],[127.01,37.49]]]]}
        """;

    Geometry geom = GeoJsonUtil.fromGeoJson(geoJson);

    assertThat(geom).isInstanceOf(MultiPolygon.class);
    assertThat(geom.getSRID()).isEqualTo(4326);
  }
}
