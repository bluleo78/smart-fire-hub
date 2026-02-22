package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TriggerEventCleanupService {

  private static final Logger log = LoggerFactory.getLogger(TriggerEventCleanupService.class);

  private final TriggerEventRepository triggerEventRepository;

  public TriggerEventCleanupService(TriggerEventRepository triggerEventRepository) {
    this.triggerEventRepository = triggerEventRepository;
  }

  /** Clean up trigger events older than 90 days. Runs daily at 3 AM. */
  @Scheduled(cron = "0 0 3 * * *")
  public void cleanupOldEvents() {
    int deleted = triggerEventRepository.deleteOlderThan(90);
    log.info("Cleaned up {} trigger events older than 90 days", deleted);
  }
}
