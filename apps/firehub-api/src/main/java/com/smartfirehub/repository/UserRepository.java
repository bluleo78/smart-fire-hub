package com.smartfirehub.repository;

import com.smartfirehub.dto.UserResponse;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Repository
public class UserRepository {

    private static final org.jooq.Table<?> USER = table("\"user\"");
    private static final org.jooq.Field<Long> ID = field("id", Long.class);
    private static final org.jooq.Field<String> USERNAME = field("username", String.class);
    private static final org.jooq.Field<String> EMAIL = field("email", String.class);
    private static final org.jooq.Field<String> PASSWORD = field("password", String.class);
    private static final org.jooq.Field<String> NAME = field("name", String.class);
    private static final org.jooq.Field<LocalDateTime> CREATED_AT = field("created_at", LocalDateTime.class);

    private final DSLContext dsl;

    public UserRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<UserResponse> findByUsername(String username) {
        return dsl.select(ID, USERNAME, EMAIL, NAME, CREATED_AT)
                .from(USER)
                .where(USERNAME.eq(username))
                .fetchOptional(r -> new UserResponse(
                        r.get(ID),
                        r.get(USERNAME),
                        r.get(EMAIL),
                        r.get(NAME),
                        r.get(CREATED_AT)
                ));
    }

    public Optional<UserResponse> findById(Long id) {
        return dsl.select(ID, USERNAME, EMAIL, NAME, CREATED_AT)
                .from(USER)
                .where(ID.eq(id))
                .fetchOptional(r -> new UserResponse(
                        r.get(ID),
                        r.get(USERNAME),
                        r.get(EMAIL),
                        r.get(NAME),
                        r.get(CREATED_AT)
                ));
    }

    public Optional<String> findPasswordByUsername(String username) {
        return dsl.select(PASSWORD)
                .from(USER)
                .where(USERNAME.eq(username))
                .fetchOptional(r -> r.get(PASSWORD));
    }

    public boolean existsByUsername(String username) {
        return dsl.fetchExists(
                dsl.selectOne().from(USER).where(USERNAME.eq(username))
        );
    }

    public boolean existsByEmail(String email) {
        return dsl.fetchExists(
                dsl.selectOne().from(USER).where(EMAIL.eq(email))
        );
    }

    public UserResponse save(String username, String email, String password, String name) {
        return dsl.insertInto(USER)
                .set(USERNAME, username)
                .set(EMAIL, email)
                .set(PASSWORD, password)
                .set(NAME, name)
                .returning(ID, USERNAME, EMAIL, NAME, CREATED_AT)
                .fetchOne(r -> new UserResponse(
                        r.get(ID),
                        r.get(USERNAME),
                        r.get(EMAIL),
                        r.get(NAME),
                        r.get(CREATED_AT)
                ));
    }
}
