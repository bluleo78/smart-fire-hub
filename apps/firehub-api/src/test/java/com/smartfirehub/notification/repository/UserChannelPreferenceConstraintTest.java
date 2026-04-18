package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.USER;
import static com.smartfirehub.jooq.Tables.USER_CHANNEL_PREFERENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** CHAT 채널 OFF 시도 → CHECK 제약 위반으로 INSERT 실패 검증. 안전망 불변식 보장. */
@Transactional
class UserChannelPreferenceConstraintTest extends IntegrationTestBase {

    @Autowired
    private DSLContext dsl;

    /** 테스트용 사용자 생성 후 id 반환. 각 테스트에서 격리된 사용자 사용. */
    private long createTestUser() {
        return dsl.insertInto(USER)
                .set(USER.USERNAME, "testuser_" + System.nanoTime())
                .set(USER.PASSWORD, "password")
                .set(USER.NAME, "Test User")
                .set(USER.EMAIL, "test_" + System.nanoTime() + "@example.com")
                .returning(USER.ID)
                .fetchOne()
                .getId();
    }

    @Test
    void chatChannelCannotBeDisabled() {
        long userId = createTestUser();
        assertThatThrownBy(() -> dsl
                .insertInto(USER_CHANNEL_PREFERENCE)
                .set(USER_CHANNEL_PREFERENCE.USER_ID, userId)
                .set(USER_CHANNEL_PREFERENCE.CHANNEL_TYPE, "CHAT")
                .set(USER_CHANNEL_PREFERENCE.ENABLED, false)
                .execute()
        ).hasMessageContaining("chat_always_enabled");
    }

    @Test
    void otherChannelsCanBeDisabled() {
        long userId = createTestUser();
        int rows = dsl
                .insertInto(USER_CHANNEL_PREFERENCE)
                .set(USER_CHANNEL_PREFERENCE.USER_ID, userId)
                .set(USER_CHANNEL_PREFERENCE.CHANNEL_TYPE, "SLACK")
                .set(USER_CHANNEL_PREFERENCE.ENABLED, false)
                .execute();
        assertThat(rows).isEqualTo(1);
    }
}
