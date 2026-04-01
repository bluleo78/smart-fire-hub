package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerEventCleanupService {

  private final TriggerEventRepository triggerEventRepository;

  /** Clean up trigger events older than 90 days. Runs daily at 3 AM. */
  @Scheduled(cron = "0 0 3 * * *")
  public void cleanupOldEvents() {
    int deleted = triggerEventRepository.deleteOlderThan(90);
    log.info("Cleaned up {} trigger events older than 90 days", deleted);
  }
}
