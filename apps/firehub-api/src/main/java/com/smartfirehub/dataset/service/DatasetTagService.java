package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetTagRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetTagService {

  private final DatasetRepository datasetRepository;
  private final DatasetTagRepository tagRepository;

  public DatasetTagService(
      DatasetRepository datasetRepository, DatasetTagRepository tagRepository) {
    this.datasetRepository = datasetRepository;
    this.tagRepository = tagRepository;
  }

  @Transactional
  public void addTag(Long datasetId, String tagName, Long userId) {
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

    boolean alreadyExists = tagRepository.findByDatasetId(datasetId).contains(tagName);
    if (alreadyExists) {
      throw new IllegalStateException("Tag already exists: " + tagName);
    }
    tagRepository.insert(datasetId, tagName, userId);
  }

  @Transactional
  public void deleteTag(Long datasetId, String tagName) {
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    tagRepository.delete(datasetId, tagName);
  }

  @Transactional(readOnly = true)
  public List<String> getAllDistinctTags() {
    return tagRepository.findAllDistinctTags();
  }
}
