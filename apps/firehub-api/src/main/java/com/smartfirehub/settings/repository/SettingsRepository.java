package com.smartfirehub.settings.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.settings.dto.SettingResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {

  private final DSLContext dsl;

  private static final Table<?> SYSTEM_SETTINGS = table(name("system_settings"));
  private static final Field<String> KEY = field(name("system_settings", "key"), String.class);
  private static final Field<String> VALUE = field(name("system_settings", "value"), String.class);
  private static final Field<String> DESCRIPTION =
      field(name("system_settings", "description"), String.class);
  private static final Field<LocalDateTime> UPDATED_AT =
      field(name("system_settings", "updated_at"), LocalDateTime.class);
  private static final Field<Long> UPDATED_BY =
      field(name("system_settings", "updated_by"), Long.class);

  public SettingsRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<SettingResponse> findByPrefix(String prefix) {
    return dsl.select(KEY, VALUE, DESCRIPTION, UPDATED_AT)
        .from(SYSTEM_SETTINGS)
        .where(KEY.like(prefix + ".%"))
        .orderBy(KEY)
        .fetch(
            r ->
                new SettingResponse(
                    r.get(KEY), r.get(VALUE), r.get(DESCRIPTION), r.get(UPDATED_AT)));
  }

  public Optional<String> getValue(String key) {
    return dsl.select(VALUE).from(SYSTEM_SETTINGS).where(KEY.eq(key)).fetchOptional(VALUE);
  }

  public void updateSettings(Map<String, String> settings, Long userId) {
    dsl.transaction(
        tx -> {
          var ctx = tx.dsl();
          for (var entry : settings.entrySet()) {
            ctx.update(SYSTEM_SETTINGS)
                .set(VALUE, entry.getValue())
                .set(UPDATED_AT, currentLocalDateTime())
                .set(UPDATED_BY, userId)
                .where(KEY.eq(entry.getKey()))
                .execute();
          }
        });
  }
}
