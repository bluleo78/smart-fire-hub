package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.FavoriteToggleResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetFavoriteRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetFavoriteService {

  private final DatasetRepository datasetRepository;
  private final DatasetFavoriteRepository favoriteRepository;

  public DatasetFavoriteService(
      DatasetRepository datasetRepository, DatasetFavoriteRepository favoriteRepository) {
    this.datasetRepository = datasetRepository;
    this.favoriteRepository = favoriteRepository;
  }

  @Transactional
  public FavoriteToggleResponse toggleFavorite(Long datasetId, Long userId) {
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

    if (favoriteRepository.existsByUserIdAndDatasetId(userId, datasetId)) {
      favoriteRepository.delete(userId, datasetId);
      return new FavoriteToggleResponse(false);
    } else {
      favoriteRepository.insert(userId, datasetId);
      return new FavoriteToggleResponse(true);
    }
  }
}
