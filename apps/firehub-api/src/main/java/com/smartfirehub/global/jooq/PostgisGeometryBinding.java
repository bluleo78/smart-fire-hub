package com.smartfirehub.global.jooq;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import org.jooq.Binding;
import org.jooq.BindingGetResultSetContext;
import org.jooq.BindingGetSQLInputContext;
import org.jooq.BindingGetStatementContext;
import org.jooq.BindingRegisterContext;
import org.jooq.BindingSQLContext;
import org.jooq.BindingSetSQLOutputContext;
import org.jooq.BindingSetStatementContext;
import org.jooq.Converter;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTWriter;

/**
 * jOOQ 커스텀 바인딩: PostGIS geometry ↔ JTS Geometry 변환. jOOQ 코드젠의 forcedType 설정에서
 * 참조됨.
 */
public class PostgisGeometryBinding implements Binding<Object, Geometry> {

  @Override
  public Converter<Object, Geometry> converter() {
    return new PostgisGeometryConverter();
  }

  @Override
  public void sql(BindingSQLContext<Geometry> ctx) throws SQLException {
    if (ctx.render().paramType() == ParamType.INLINED) {
      Geometry value = ctx.value();
      if (value == null) {
        ctx.render().visit(DSL.inline((String) null));
      } else {
        ctx.render()
            .visit(
                DSL.inline(
                    "ST_GeomFromText('"
                        + new WKTWriter().write(value)
                        + "', "
                        + value.getSRID()
                        + ")"));
      }
    } else {
      ctx.render().sql(ctx.variable()).sql("::geometry");
    }
  }

  @Override
  public void register(BindingRegisterContext<Geometry> ctx) throws SQLException {
    ctx.statement().registerOutParameter(ctx.index(), Types.OTHER);
  }

  @Override
  public void set(BindingSetStatementContext<Geometry> ctx) throws SQLException {
    Geometry value = ctx.value();
    if (value == null) {
      ctx.statement().setNull(ctx.index(), Types.OTHER);
    } else {
      WKBWriter writer = new WKBWriter(2, true);
      byte[] wkb = writer.write(value);
      ctx.statement().setObject(ctx.index(), WKBWriter.toHex(wkb), Types.OTHER);
    }
  }

  @Override
  public void set(BindingSetSQLOutputContext<Geometry> ctx) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void get(BindingGetResultSetContext<Geometry> ctx) throws SQLException {
    String hex = ctx.resultSet().getString(ctx.index());
    ctx.value(hex == null ? null : fromHexEwkb(hex));
  }

  @Override
  public void get(BindingGetStatementContext<Geometry> ctx) throws SQLException {
    String hex = ctx.statement().getString(ctx.index());
    ctx.value(hex == null ? null : fromHexEwkb(hex));
  }

  @Override
  public void get(BindingGetSQLInputContext<Geometry> ctx) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  private Geometry fromHexEwkb(String hex) {
    try {
      byte[] bytes = WKBReader.hexToBytes(hex);
      WKBReader reader = new WKBReader();
      return reader.read(bytes);
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse geometry from hex EWKB: " + hex, e);
    }
  }
}
