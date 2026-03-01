package com.smartfirehub.global.util;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;

/**
 * GeoJSON ↔ JTS Geometry 변환 유틸리티. API 입출력에서 GeoJSON 문자열 ↔ JTS Geometry 간 변환에
 * 사용.
 */
public final class GeoJsonUtil {

  private static final GeometryFactory FACTORY =
      new GeometryFactory(new PrecisionModel(), 4326); // WGS84

  private GeoJsonUtil() {}

  /** GeoJSON 문자열 → JTS Geometry */
  public static Geometry fromGeoJson(String geoJson) {
    try {
      GeoJsonReader reader = new GeoJsonReader(FACTORY);
      Geometry geom = reader.read(geoJson);
      geom.setSRID(4326);
      return geom;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid GeoJSON: " + e.getMessage(), e);
    }
  }

  /** JTS Geometry → GeoJSON 문자열 */
  public static String toGeoJson(Geometry geometry) {
    if (geometry == null) return null;
    GeoJsonWriter writer = new GeoJsonWriter();
    writer.setEncodeCRS(false);
    return writer.write(geometry);
  }

  /** 위도/경도 → JTS Point (EPSG:4326) */
  public static Point createPoint(double longitude, double latitude) {
    Point point = FACTORY.createPoint(new Coordinate(longitude, latitude));
    point.setSRID(4326);
    return point;
  }

  /** JTS Geometry에서 위도 추출 (Point만) */
  public static Double getLatitude(Geometry geometry) {
    if (geometry instanceof Point p) return p.getY();
    return null;
  }

  /** JTS Geometry에서 경도 추출 (Point만) */
  public static Double getLongitude(Geometry geometry) {
    if (geometry instanceof Point p) return p.getX();
    return null;
  }
}
