package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.repository.DatasetCategoryRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * DatasetCategoryRepository 의 MyBatis 구현(WD-707) — {@code JooqDatasetCategoryRepository} 와 같은 의미.
 *
 * <p>jOOQ 구현이 메서드에 {@code @Transactional} 을 달지 않으므로 여기서도 달지 않는다 — 각 문장은 호출자 트랜잭션에 참여한다. 이름 중복은
 * (테넌트, name) 유일 색인이 {@code DuplicateKeyException} 으로 알린다(서비스가 번역). 빈 문자열은 변환하지 않는다 — Tibero 는
 * {@code ''} 를 NULL 로 저장해 NULL 허용 열(description)은 null 로 돌아오고 NOT NULL 열(name)은 거절된다.
 */
@PlatformMyBatisRepository
public class MyBatisDatasetCategoryRepository implements DatasetCategoryRepository {

  private final DatasetCategoryMapper mapper;

  public MyBatisDatasetCategoryRepository(DatasetCategoryMapper mapper) {
    this.mapper = mapper;
  }

  /** 현재 테넌트(RLS)의 카테고리 전부를 id 순으로 돌려준다. */
  @Override
  public List<CategoryResponse> findAll() {
    return mapper.selectAll();
  }

  /** id 로 카테고리를 찾는다. 없거나 다른 테넌트면 빈 값. */
  @Override
  public Optional<CategoryResponse> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id));
  }

  /** 넣고 생성 키로 다시 읽어 돌려준다(Tibero 는 RETURNING 이 없다). */
  @Override
  public CategoryResponse save(String name, String description) {
    CategoryInsert row = new CategoryInsert(name, description);
    mapper.insert(row);
    return mapper.selectById(row.getId());
  }

  /** 이름·설명을 바꾸고 수정 시각을 자바 현재 시각으로 남긴다(jOOQ 판과 같다). */
  @Override
  public void update(Long id, String name, String description) {
    mapper.update(id, name, description, LocalDateTime.now());
  }

  /** 카테고리를 지우고 지운 행 수를 돌려준다 — 0 이면 이미 없는 카테고리다. */
  @Override
  public int deleteById(Long id) {
    return mapper.deleteById(id);
  }

  /** 앞뒤 공백·대소문자를 무시한 같은 이름 확인(WD-241) — 대소문자 변환은 DB 의 LOWER 한 곳에서만 한다. */
  @Override
  public boolean existsByName(String name) {
    return mapper.countByName(name, null) > 0;
  }

  /** 수정용 이름 중복 확인 — {@link #existsByName} 과 같되 자기 자신(excludeId)은 뺀다. */
  @Override
  public boolean existsByNameExcludingId(String name, Long excludeId) {
    return mapper.countByName(name, excludeId) > 0;
  }

  /** RLS 가 같은 테넌트 데이터셋만 보이게 하므로 호출자 트랜잭션(테넌트 컨텍스트) 안에서만 맞는 결과를 낸다 — jOOQ 구현과 같다. */
  @Override
  public boolean existsDatasetUsing(Long categoryId) {
    return mapper.countDatasetsUsing(categoryId) > 0;
  }
}
