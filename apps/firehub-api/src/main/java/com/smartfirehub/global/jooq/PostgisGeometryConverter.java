package com.smartfirehub.global.jooq;

import org.jooq.Converter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;

/** jOOQ Converter: DB Object ↔ JTS Geometry. */
public class PostgisGeometryConverter implements Converter<Object, Geometry> {

  @Override
  public Geometry from(Object dbObject) {
    if (dbObject == null) return null;
    try {
      String hex = dbObject.toString();
      byte[] bytes = WKBReader.hexToBytes(hex);
      return new WKBReader().read(bytes);
    } catch (Exception e) {
      throw new RuntimeException("Failed to convert DB object to Geometry", e);
    }
  }

  @Override
  public Object to(Geometry geometry) {
    if (geometry == null) return null;
    WKBWriter writer = new WKBWriter(2, true);
    return WKBWriter.toHex(writer.write(geometry));
  }

  @Override
  public Class<Object> fromType() {
    return Object.class;
  }

  @Override
  public Class<Geometry> toType() {
    return Geometry.class;
  }
}
