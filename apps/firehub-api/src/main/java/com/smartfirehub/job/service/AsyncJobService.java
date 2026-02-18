package com.smartfirehub.job.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.repository.AsyncJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 범용 비동기 작업 관리 서비스.
 * SSE emitter 맵은 JVM 로컬이므로 단일 인스턴스 배포에서만 실시간 전송이 보장됩니다.
 * 다중 인스턴스 환경에서는 클라이언트의 REST 폴백(/jobs/{id}/status)이 대체합니다.
 */
@Service
public class AsyncJobService {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobService.class);

    private static final long EMITTER_TIMEOUT_MS = 300_000L;
    private static final int MAX_SUBSCRIBERS_PER_JOB = 5;
    private static final int DB_UPDATE_INTERVAL = 5;

    private final AsyncJobRepository asyncJobRepository;
    private final ObjectMapper objectMapper;

    // jobId -> list of active SSE emitters
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters =
            new ConcurrentHashMap<>();

    // jobId -> update call counter (for DB throttling)
    private final ConcurrentHashMap<String, AtomicInteger> updateCounters =
            new ConcurrentHashMap<>();

    // jobId -> last persisted stage (force DB write on stage change)
    private final ConcurrentHashMap<String, String> lastPersistedStage =
            new ConcurrentHashMap<>();

    public AsyncJobService(AsyncJobRepository asyncJobRepository, ObjectMapper objectMapper) {
        this.asyncJobRepository = asyncJobRepository;
        this.objectMapper = objectMapper;
    }

    public String createJob(String jobType, String resource, String resourceId,
                            Long userId, Map<String, Object> metadata) {
        String jobId = UUID.randomUUID().toString();
        asyncJobRepository.insert(jobId, jobType, resource, resourceId, userId,
                metadata != null ? metadata : Collections.emptyMap());
        log.debug("Created async job: jobId={}, jobType={}, resource={}/{}", jobId, jobType, resource, resourceId);
        return jobId;
    }

    public void updateProgress(String jobId, String stage, int progress,
                               String message, Map<String, Object> metadata) {
        // Always emit SSE
        Map<String, Object> event = buildEventPayload(jobId, null, stage, progress, message, metadata, null);
        broadcastEvent(jobId, "progress", event);

        // DB update throttled: every DB_UPDATE_INTERVAL calls, but always on stage change
        AtomicInteger counter = updateCounters.computeIfAbsent(jobId, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();
        String prevStage = lastPersistedStage.get(jobId);
        boolean stageChanged = prevStage == null || !prevStage.equals(stage);
        if (stageChanged || count % DB_UPDATE_INTERVAL == 0) {
            asyncJobRepository.updateStageAndProgress(jobId, stage, progress, message,
                    metadata != null ? metadata : Collections.emptyMap());
            lastPersistedStage.put(jobId, stage);
        }
    }

    public void completeJob(String jobId, Map<String, Object> metadata) {
        asyncJobRepository.updateStageAndProgress(jobId, "COMPLETED", 100, "완료",
                metadata != null ? metadata : Collections.emptyMap());
        updateCounters.remove(jobId);
        lastPersistedStage.remove(jobId);

        Map<String, Object> event = buildEventPayload(jobId, null, "COMPLETED", 100, "완료", metadata, null);
        broadcastEvent(jobId, "complete", event);
        terminateEmitters(jobId);
        log.debug("Completed async job: jobId={}", jobId);
    }

    public void failJob(String jobId, String errorMessage) {
        // Preserve last known progress for UI display
        int lastProgress = asyncJobRepository.findById(jobId)
                .map(AsyncJobStatusResponse::progress).orElse(0);
        asyncJobRepository.updateStageAndError(jobId, "FAILED", errorMessage);
        updateCounters.remove(jobId);
        lastPersistedStage.remove(jobId);

        Map<String, Object> event = buildEventPayload(jobId, null, "FAILED", lastProgress, errorMessage, Collections.emptyMap(), errorMessage);
        broadcastEvent(jobId, "error", event);
        terminateEmitters(jobId);
        log.debug("Failed async job: jobId={}, error={}", jobId, errorMessage);
    }

    public SseEmitter subscribe(String jobId, Long userId) {
        // Single query — owner verification + current state
        AsyncJobStatusResponse status = asyncJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        if (!status.userId().equals(userId)) {
            throw new AccessDeniedException("Access denied: not the owner of job " + jobId);
        }

        // Subscriber limit
        CopyOnWriteArrayList<SseEmitter> list = emitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        if (list.size() >= MAX_SUBSCRIBERS_PER_JOB) {
            throw new IllegalStateException("Too many subscribers for job " + jobId);
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(e -> removeEmitter(jobId, emitter));

        list.add(emitter);

        // Send current state immediately (already fetched above)
        Map<String, Object> event = buildEventPayload(
                status.jobId(), status.jobType(), status.stage(),
                status.progress(), status.message(), status.metadata(), status.errorMessage());
        String eventName = switch (status.stage()) {
            case "COMPLETED" -> "complete";
            case "FAILED" -> "error";
            default -> "progress";
        };
        safeSend(jobId, emitter, SseEmitter.event().name(eventName).data(toJson(event)));

        return emitter;
    }

    public AsyncJobStatusResponse getJobStatus(String jobId, Long userId) {
        // Single query — includes userId for ownership verification
        AsyncJobStatusResponse status = asyncJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        if (!status.userId().equals(userId)) {
            throw new AccessDeniedException("Access denied: not the owner of job " + jobId);
        }
        return status;
    }

    public boolean hasActiveJob(String jobType, String resource, String resourceId) {
        return !asyncJobRepository.findActiveByResource(jobType, resource, resourceId).isEmpty();
    }

    public List<AsyncJobStatusResponse> findActiveJobs(String jobType, String resource, String resourceId) {
        return asyncJobRepository.findActiveByResource(jobType, resource, resourceId);
    }

    // --- Internal helpers ---

    private Map<String, Object> buildEventPayload(String jobId, String jobType, String stage,
                                                   int progress, String message,
                                                   Map<String, Object> metadata, String errorMessage) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("jobId", jobId);
        if (jobType != null) payload.put("jobType", jobType);
        payload.put("stage", stage);
        payload.put("progress", progress);
        payload.put("message", message);
        payload.put("metadata", metadata != null ? metadata : Collections.emptyMap());
        if (errorMessage != null) payload.put("errorMessage", errorMessage);
        return payload;
    }

    private void broadcastEvent(String jobId, String eventName, Map<String, Object> payload) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(jobId);
        if (list == null || list.isEmpty()) return;

        String json = toJson(payload);
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventName).data(json);

        for (SseEmitter emitter : list) {
            safeSend(jobId, emitter, event);
        }
    }

    private void safeSend(String jobId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE send failed for jobId={}, removing emitter: {}", jobId, e.getMessage());
            removeEmitter(jobId, emitter);
        }
    }

    private void removeEmitter(String jobId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(jobId);
        if (list != null) {
            list.remove(emitter);
            emitters.computeIfPresent(jobId, (k, v) -> v.isEmpty() ? null : v);
        }
    }

    private void terminateEmitters(String jobId) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.remove(jobId);
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("Error completing emitter for jobId={}: {}", jobId, e.getMessage());
            }
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize SSE payload: {}", e.getMessage());
            return "{}";
        }
    }
}
