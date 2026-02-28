package com.smartfirehub.analytics.service;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.global.dto.PageResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SavedQueryService {

  private final SavedQueryRepository savedQueryRepository;
  private final DatasetRepository datasetRepository;
  private final AnalyticsQueryExecutionService executionService;

  public SavedQueryService(
      SavedQueryRepository savedQueryRepository,
      DatasetRepository datasetRepository,
      AnalyticsQueryExecutionService executionService) {
    this.savedQueryRepository = savedQueryRepository;
    this.datasetRepository = datasetRepository;
    this.executionService = executionService;
  }

  /** List saved queries with optional filters and pagination. */
  public PageResponse<SavedQueryListResponse> list(
      String search, String folder, Boolean sharedOnly, Long userId, int page, int size) {
    List<SavedQueryListResponse> content =
        savedQueryRepository.findAll(search, folder, sharedOnly, userId, page, size);
    long total = savedQueryRepository.countAll(search, folder, sharedOnly, userId);
    int totalPages = (int) Math.ceil((double) total / size);
    return new PageResponse<>(content, page, size, total, totalPages);
  }

  /** Create a new saved query. */
  @Transactional
  public SavedQueryResponse create(CreateSavedQueryRequest req, Long userId) {
    if (req.datasetId() != null) {
      datasetRepository
          .findById(req.datasetId())
          .orElseThrow(
              () ->
                  new ResponseStatusException(
                      HttpStatus.NOT_FOUND, "Dataset not found: " + req.datasetId()));
    }
    Long id = savedQueryRepository.insert(req, userId);
    return savedQueryRepository
        .findById(id, userId)
        .orElseThrow(() -> new SavedQueryNotFoundException("Saved query not found after insert"));
  }

  /** Get a single saved query — owner or any shared query. */
  public SavedQueryResponse getById(Long id, Long userId) {
    return savedQueryRepository
        .findById(id, userId)
        .orElseThrow(() -> new SavedQueryNotFoundException("Saved query not found: " + id));
  }

  /**
   * Update a saved query. Only the owner can update. If the query is shared and other users' charts
   * reference it, sqlText cannot be changed.
   */
  @Transactional
  public SavedQueryResponse update(Long id, UpdateSavedQueryRequest req, Long userId) {
    SavedQueryResponse existing =
        savedQueryRepository
            .findByIdForOwner(id, userId)
            .orElseThrow(() -> new SavedQueryNotFoundException("Saved query not found: " + id));

    // Protect shared query SQL if other users' charts reference it
    if (req.sqlText() != null && !req.sqlText().equals(existing.sqlText()) && existing.isShared()) {
      long otherChartCount = savedQueryRepository.countOtherUserCharts(id, userId);
      if (otherChartCount > 0) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "공유 쿼리의 SQL은 수정할 수 없습니다. '복제' 후 수정하세요.");
      }
    }

    savedQueryRepository.update(id, req, userId);
    return savedQueryRepository
        .findByIdForOwner(id, userId)
        .orElseThrow(() -> new SavedQueryNotFoundException("Saved query not found: " + id));
  }

  /** Delete a saved query (owner only). CASCADE removes linked charts and widgets. */
  @Transactional
  public void delete(Long id, Long userId) {
    // Verify ownership first
    savedQueryRepository
        .findByIdForOwner(id, userId)
        .orElseThrow(() -> new SavedQueryNotFoundException("Saved query not found: " + id));
    boolean deleted = savedQueryRepository.deleteById(id, userId);
    if (!deleted) {
      throw new SavedQueryNotFoundException("Saved query not found: " + id);
    }
  }

  /**
   * Clone a saved query. The clone is private (is_shared=false) and belongs to the requesting user.
   */
  @Transactional
  public SavedQueryResponse clone(Long id, Long userId) {
    var raw =
        savedQueryRepository
            .findRawByIdUnrestricted(id)
            .orElseThrow(() -> new SavedQueryNotFoundException("Saved query not found: " + id));

    // Verify access: owner or shared
    boolean isShared = Boolean.TRUE.equals(raw.get("is_shared", Boolean.class));
    Long ownerId = raw.get("created_by", Long.class);
    if (!isShared && !ownerId.equals(userId)) {
      throw new SavedQueryNotFoundException("Saved query not found: " + id);
    }

    String originalName = raw.get("name", String.class);
    CreateSavedQueryRequest cloneReq =
        new CreateSavedQueryRequest(
            originalName + " (복사본)",
            raw.get("description", String.class),
            raw.get("sql_text", String.class),
            raw.get("dataset_id", Long.class),
            raw.get("folder", String.class),
            false);

    Long newId = savedQueryRepository.insert(cloneReq, userId);
    return savedQueryRepository
        .findById(newId, userId)
        .orElseThrow(() -> new SavedQueryNotFoundException("Clone failed"));
  }

  /** Execute a saved query by ID. */
  @Transactional
  public AnalyticsQueryResponse executeById(Long id, int maxRows, boolean readOnly, Long userId) {
    SavedQueryResponse query = getById(id, userId);
    return executionService.execute(query.sqlText(), maxRows, readOnly);
  }

  /** Get distinct folder names visible to the user. */
  public List<String> getFolders(Long userId) {
    return savedQueryRepository.findDistinctFolders(userId);
  }
}
