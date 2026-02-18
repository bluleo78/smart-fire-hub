package com.smartfirehub.ai.repository;

import com.smartfirehub.ai.dto.AiSessionResponse;
import com.smartfirehub.ai.dto.CreateAiSessionRequest;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.jooq.impl.DSL.*;

@Repository
public class AiSessionRepository {

    private final DSLContext dsl;

    private static final Table<?> AI_SESSION = table(name("ai_session"));
    private static final Field<Long> ID = field(name("ai_session", "id"), Long.class);
    private static final Field<Long> USER_ID = field(name("ai_session", "user_id"), Long.class);
    private static final Field<String> SESSION_ID = field(name("ai_session", "session_id"), String.class);
    private static final Field<String> CONTEXT_TYPE = field(name("ai_session", "context_type"), String.class);
    private static final Field<Long> CONTEXT_RESOURCE_ID = field(name("ai_session", "context_resource_id"), Long.class);
    private static final Field<String> TITLE = field(name("ai_session", "title"), String.class);
    private static final Field<LocalDateTime> CREATED_AT = field(name("ai_session", "created_at"), LocalDateTime.class);
    private static final Field<LocalDateTime> UPDATED_AT = field(name("ai_session", "updated_at"), LocalDateTime.class);

    public AiSessionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<AiSessionResponse> findByUserId(Long userId) {
        return dsl.select(ID, USER_ID, SESSION_ID, CONTEXT_TYPE, CONTEXT_RESOURCE_ID, TITLE, CREATED_AT, UPDATED_AT)
                .from(AI_SESSION)
                .where(USER_ID.eq(userId))
                .orderBy(UPDATED_AT.desc())
                .fetch(r -> new AiSessionResponse(
                        r.get(ID),
                        r.get(USER_ID),
                        r.get(SESSION_ID),
                        r.get(CONTEXT_TYPE),
                        r.get(CONTEXT_RESOURCE_ID),
                        r.get(TITLE),
                        r.get(CREATED_AT),
                        r.get(UPDATED_AT)
                ));
    }

    public Optional<AiSessionResponse> findById(Long id) {
        return dsl.select(ID, USER_ID, SESSION_ID, CONTEXT_TYPE, CONTEXT_RESOURCE_ID, TITLE, CREATED_AT, UPDATED_AT)
                .from(AI_SESSION)
                .where(ID.eq(id))
                .fetchOptional(r -> new AiSessionResponse(
                        r.get(ID),
                        r.get(USER_ID),
                        r.get(SESSION_ID),
                        r.get(CONTEXT_TYPE),
                        r.get(CONTEXT_RESOURCE_ID),
                        r.get(TITLE),
                        r.get(CREATED_AT),
                        r.get(UPDATED_AT)
                ));
    }

    public Optional<AiSessionResponse> findByUserIdAndContext(Long userId, String contextType, Long contextResourceId) {
        return dsl.select(ID, USER_ID, SESSION_ID, CONTEXT_TYPE, CONTEXT_RESOURCE_ID, TITLE, CREATED_AT, UPDATED_AT)
                .from(AI_SESSION)
                .where(USER_ID.eq(userId)
                        .and(CONTEXT_TYPE.eq(contextType))
                        .and(CONTEXT_RESOURCE_ID.eq(contextResourceId)))
                .fetchOptional(r -> new AiSessionResponse(
                        r.get(ID),
                        r.get(USER_ID),
                        r.get(SESSION_ID),
                        r.get(CONTEXT_TYPE),
                        r.get(CONTEXT_RESOURCE_ID),
                        r.get(TITLE),
                        r.get(CREATED_AT),
                        r.get(UPDATED_AT)
                ));
    }

    public Optional<AiSessionResponse> findByUserIdAndSessionId(Long userId, String sessionId) {
        return dsl.select(ID, USER_ID, SESSION_ID, CONTEXT_TYPE, CONTEXT_RESOURCE_ID, TITLE, CREATED_AT, UPDATED_AT)
                .from(AI_SESSION)
                .where(USER_ID.eq(userId).and(SESSION_ID.eq(sessionId)))
                .fetchOptional(r -> new AiSessionResponse(
                        r.get(ID),
                        r.get(USER_ID),
                        r.get(SESSION_ID),
                        r.get(CONTEXT_TYPE),
                        r.get(CONTEXT_RESOURCE_ID),
                        r.get(TITLE),
                        r.get(CREATED_AT),
                        r.get(UPDATED_AT)
                ));
    }

    public AiSessionResponse create(Long userId, CreateAiSessionRequest request) {
        Long id = dsl.insertInto(AI_SESSION)
                .set(USER_ID, userId)
                .set(SESSION_ID, request.sessionId())
                .set(CONTEXT_TYPE, request.contextType())
                .set(CONTEXT_RESOURCE_ID, request.contextResourceId())
                .set(TITLE, request.title())
                .set(CREATED_AT, currentLocalDateTime())
                .set(UPDATED_AT, currentLocalDateTime())
                .returningResult(ID)
                .fetchOne()
                .get(ID);

        return findById(id).orElseThrow();
    }

    public void updateTitle(Long id, String title) {
        dsl.update(AI_SESSION)
                .set(TITLE, title)
                .set(UPDATED_AT, currentLocalDateTime())
                .where(ID.eq(id))
                .execute();
    }

    public void delete(Long id) {
        dsl.deleteFrom(AI_SESSION)
                .where(ID.eq(id))
                .execute();
    }
}
