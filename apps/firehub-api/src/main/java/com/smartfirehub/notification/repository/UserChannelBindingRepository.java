package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.ChannelType;
import java.util.Optional;

/** 활성 binding 조회. 구현체는 Task 5. */
public interface UserChannelBindingRepository {
    Optional<UserChannelBinding> findActive(long userId, ChannelType channelType);
}
