package com.smartfirehub.dataset.dto;

import java.util.List;

public record DataDeleteRequest(List<Long> rowIds) {}
