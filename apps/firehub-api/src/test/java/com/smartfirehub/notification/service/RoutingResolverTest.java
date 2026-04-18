package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import com.smartfirehub.notification.repository.UserChannelPreferenceRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Spec 6장 라우팅 매트릭스 단위 검증. */
@ExtendWith(MockitoExtension.class)
class RoutingResolverTest {

    @Mock private UserChannelPreferenceRepository preferenceRepo;
    @Mock private UserChannelBindingRepository bindingRepo;

    @InjectMocks private RoutingResolver resolver;

    private static final long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        // 디폴트: 모든 채널 enabled (AuthStrategy는 ChannelType enum에서 직접 참조하므로 mock 불필요)
        lenient().when(preferenceRepo.isEnabled(eq(USER_ID), any())).thenReturn(true);
    }

    @Test
    void slackEnabledWithBinding_resolvesSlackOnly() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK))
                .thenReturn(Optional.of(stubBinding(ChannelType.SLACK)));
        Recipient r = new Recipient(USER_ID, null, EnumSet.of(ChannelType.SLACK));

        ResolvedRouting result = resolver.resolve(r);

        assertThat(result.resolvedChannels()).containsExactly(ChannelType.SLACK);
        assertThat(result.forcedChatFallback()).isFalse();
        assertThat(result.skippedReasons()).isEmpty();
    }

    @Test
    void slackOptedOut_forcesChatFallback() {
        when(preferenceRepo.isEnabled(USER_ID, ChannelType.SLACK)).thenReturn(false);
        Recipient r = new Recipient(USER_ID, null, EnumSet.of(ChannelType.SLACK));

        ResolvedRouting result = resolver.resolve(r);

        assertThat(result.resolvedChannels()).containsExactly(ChannelType.CHAT);
        assertThat(result.forcedChatFallback()).isTrue();
        assertThat(result.skippedReasons()).containsEntry(ChannelType.SLACK, "OPTED_OUT");
    }

    @Test
    void slackEnabledWithoutBinding_forcesChatFallback() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK)).thenReturn(Optional.empty());
        Recipient r = new Recipient(USER_ID, null, EnumSet.of(ChannelType.SLACK));

        ResolvedRouting result = resolver.resolve(r);

        assertThat(result.resolvedChannels()).containsExactly(ChannelType.CHAT);
        assertThat(result.forcedChatFallback()).isTrue();
        assertThat(result.skippedReasons()).containsEntry(ChannelType.SLACK, "BINDING_MISSING");
    }

    @Test
    void slackOffEmailOn_resolvesEmailOnly() {
        when(preferenceRepo.isEnabled(USER_ID, ChannelType.SLACK)).thenReturn(false);
        // EMAIL은 binding 불필요 (AuthStrategy.EMAIL_ADDRESS)
        Recipient r = new Recipient(USER_ID, null, EnumSet.of(ChannelType.SLACK, ChannelType.EMAIL));

        ResolvedRouting result = resolver.resolve(r);

        assertThat(result.resolvedChannels()).containsExactly(ChannelType.EMAIL);
        assertThat(result.forcedChatFallback()).isFalse();
        assertThat(result.skippedReasons()).containsEntry(ChannelType.SLACK, "OPTED_OUT");
    }

    @Test
    void emptyRequestedChannels_resolvesChatDefault() {
        Recipient r = new Recipient(USER_ID, null, EnumSet.noneOf(ChannelType.class));

        ResolvedRouting result = resolver.resolve(r);

        assertThat(result.resolvedChannels()).containsExactly(ChannelType.CHAT);
        assertThat(result.forcedChatFallback()).isTrue();
    }

    @Test
    void chatAndEmailRequested_bothResolved() {
        Recipient r = new Recipient(USER_ID, null, EnumSet.of(ChannelType.CHAT, ChannelType.EMAIL));

        ResolvedRouting result = resolver.resolve(r);

        assertThat(result.resolvedChannels())
                .containsExactlyInAnyOrder(ChannelType.CHAT, ChannelType.EMAIL);
    }

    private UserChannelBinding stubBinding(ChannelType ch) {
        return new UserChannelBinding(1L, USER_ID, ch, null, "ext-id", "addr", null, null, null,
                "ACTIVE", null, Instant.EPOCH, Instant.EPOCH);
    }
}
