package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.FavoriteToggleResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetFavoriteRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DatasetFavoriteService {

  private final DatasetRepository datasetRepository;
  private final DatasetFavoriteRepository favoriteRepository;

  @Transactional
  public FavoriteToggleResponse toggleFavorite(Long datasetId, Long userId) {
    datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());

    if (favoriteRepository.existsByUserIdAndDatasetId(userId, datasetId)) {
      favoriteRepository.delete(userId, datasetId);
      return new FavoriteToggleResponse(false);
    } else {
      favoriteRepository.insert(userId, datasetId);
      return new FavoriteToggleResponse(true);
    }
  }

  /**
   * 즐겨찾기를 원하는 상태로 맞춘다(멱등, WD-388). 토글은 화면이 최신이 아닐 때(다른 탭에서 이미 바꿈) 사용자가 누른 뜻과 반대로 뒤집으므로, 화면은
   * 「추가」·「해제」를 원하는 상태로 보낸다. 이미 그 상태면 그대로 두고 그 상태를 돌려준다.
   */
  @Transactional
  public FavoriteToggleResponse setFavorite(Long datasetId, Long userId, boolean favorite) {
    datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
    if (favorite) {
      favoriteRepository.insert(userId, datasetId); // ON CONFLICT DO NOTHING — 이미 있으면 그대로
    } else {
      favoriteRepository.delete(userId, datasetId);
    }
    return new FavoriteToggleResponse(favorite);
  }
}
