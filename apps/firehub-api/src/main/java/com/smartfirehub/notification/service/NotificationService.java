package com.smartfirehub.notification.service;

import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

  private final SseEmitterRegistry registry;

  @Async
  @EventListener
  public void onPipelineCompleted(PipelineCompletedEvent event) {
    if (event.createdBy() == null) {
      log.debug(
          "PipelineCompletedEvent for pipeline {} has no createdBy, skipping notification",
          event.pipelineId());
      return;
    }

    boolean failed = "FAILED".equals(event.status());
    String severity = failed ? "WARNING" : "INFO";
    String title = failed ? "Pipeline Failed" : "Pipeline Completed";
    String description =
        failed
            ? "Pipeline execution #" + event.executionId() + " failed."
            : "Pipeline execution #" + event.executionId() + " completed successfully.";

    NotificationEvent notification =
        new NotificationEvent(
            UUID.randomUUID().toString(),
            failed ? "PIPELINE_FAILED" : "PIPELINE_COMPLETED",
            severity,
            title,
            description,
            "PIPELINE",
            event.pipelineId(),
            Map.of("executionId", event.executionId(), "status", event.status()),
            LocalDateTime.now());

    registry.broadcast(event.createdBy(), notification);
  }

  public void notifyImportCompleted(
      Long userId, Long datasetId, String datasetName, boolean success) {
    String severity = success ? "INFO" : "WARNING";
    String eventType = success ? "IMPORT_COMPLETED" : "IMPORT_FAILED";
    String title = success ? "Import Completed" : "Import Failed";
    String description =
        success
            ? "Data import into dataset '" + datasetName + "' completed successfully."
            : "Data import into dataset '" + datasetName + "' failed.";

    NotificationEvent notification =
        new NotificationEvent(
            UUID.randomUUID().toString(),
            eventType,
            severity,
            title,
            description,
            "DATASET",
            datasetId,
            Map.of("datasetName", datasetName),
            LocalDateTime.now());

    registry.broadcast(userId, notification);
  }

  public void notifyDatasetChanged(Long datasetId, String datasetName) {
    NotificationEvent notification =
        new NotificationEvent(
            UUID.randomUUID().toString(),
            "DATASET_CHANGED",
            "INFO",
            "Dataset Changed",
            "Dataset '" + datasetName + "' has been updated.",
            "DATASET",
            datasetId,
            Map.of("datasetName", datasetName),
            LocalDateTime.now());

    registry.broadcastAll(notification);
  }
}
