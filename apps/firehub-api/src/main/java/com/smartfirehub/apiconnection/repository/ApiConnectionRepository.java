package com.smartfirehub.apiconnection.repository;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.jooq.impl.DSL.*;

@Repository
public class ApiConnectionRepository {

    private final DSLContext dsl;

    private static final Table<?>             API_CONNECTION = table(name("api_connection"));
    private static final Field<Long>          AC_ID          = field(name("api_connection", "id"), Long.class);
    private static final Field<String>        AC_NAME        = field(name("api_connection", "name"), String.class);
    private static final Field<String>        AC_AUTH_TYPE   = field(name("api_connection", "auth_type"), String.class);
    private static final Field<String>        AC_AUTH_CONFIG = field(name("api_connection", "auth_config"), String.class);
    private static final Field<String>        AC_DESCRIPTION = field(name("api_connection", "description"), String.class);
    private static final Field<Long>          AC_CREATED_BY  = field(name("api_connection", "created_by"), Long.class);
    private static final Field<LocalDateTime> AC_CREATED_AT  = field(name("api_connection", "created_at"), LocalDateTime.class);
    private static final Field<LocalDateTime> AC_UPDATED_AT  = field(name("api_connection", "updated_at"), LocalDateTime.class);

    public ApiConnectionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<Record> findAll() {
        return dsl.select(
                        AC_ID, AC_NAME, AC_AUTH_TYPE, AC_AUTH_CONFIG,
                        AC_DESCRIPTION, AC_CREATED_BY, AC_CREATED_AT, AC_UPDATED_AT)
                .from(API_CONNECTION)
                .orderBy(AC_CREATED_AT.desc())
                .fetch()
                .stream()
                .map(r -> (Record) r)
                .toList();
    }

    public Optional<Record> findById(Long id) {
        return dsl.select(
                        AC_ID, AC_NAME, AC_AUTH_TYPE, AC_AUTH_CONFIG,
                        AC_DESCRIPTION, AC_CREATED_BY, AC_CREATED_AT, AC_UPDATED_AT)
                .from(API_CONNECTION)
                .where(AC_ID.eq(id))
                .fetchOptional()
                .map(r -> (Record) r);
    }

    public List<Record> findByCreatedBy(Long userId) {
        return dsl.select(
                        AC_ID, AC_NAME, AC_AUTH_TYPE, AC_AUTH_CONFIG,
                        AC_DESCRIPTION, AC_CREATED_BY, AC_CREATED_AT, AC_UPDATED_AT)
                .from(API_CONNECTION)
                .where(AC_CREATED_BY.eq(userId))
                .fetch()
                .stream()
                .map(r -> (Record) r)
                .toList();
    }

    public Long save(String name, String description, String authType, String encryptedAuthConfig, Long createdBy) {
        return dsl.insertInto(API_CONNECTION)
                .set(AC_NAME, name)
                .set(AC_DESCRIPTION, description)
                .set(AC_AUTH_TYPE, authType)
                .set(AC_AUTH_CONFIG, encryptedAuthConfig)
                .set(AC_CREATED_BY, createdBy)
                .returning(AC_ID)
                .fetchOne(r -> r.get(AC_ID));
    }

    public void update(Long id, String name, String description, String authType, String encryptedAuthConfig) {
        if (encryptedAuthConfig != null) {
            dsl.update(API_CONNECTION)
                    .set(AC_NAME, name)
                    .set(AC_DESCRIPTION, description)
                    .set(AC_AUTH_TYPE, authType)
                    .set(AC_AUTH_CONFIG, encryptedAuthConfig)
                    .set(AC_UPDATED_AT, LocalDateTime.now())
                    .where(AC_ID.eq(id))
                    .execute();
        } else {
            dsl.update(API_CONNECTION)
                    .set(AC_NAME, name)
                    .set(AC_DESCRIPTION, description)
                    .set(AC_AUTH_TYPE, authType)
                    .set(AC_UPDATED_AT, LocalDateTime.now())
                    .where(AC_ID.eq(id))
                    .execute();
        }
    }

    public void deleteById(Long id) {
        dsl.deleteFrom(API_CONNECTION)
                .where(AC_ID.eq(id))
                .execute();
    }
}
