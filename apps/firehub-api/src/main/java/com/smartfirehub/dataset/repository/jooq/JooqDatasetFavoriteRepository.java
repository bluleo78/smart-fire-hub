package com.smartfirehub.dataset.repository.jooq;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.dataset.repository.DatasetFavoriteRepository;
import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/** DatasetFavoriteRepository 의 jOOQ(PostgreSQL) 구현 — WD-485. */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
public class JooqDatasetFavoriteRepository implements DatasetFavoriteRepository {

  private final DSLContext dsl;

  private static final Table<?> DATASET_FAVORITE = table(name("dataset_favorite"));
  private static final Field<Long> DF_USER_ID =
      field(name("dataset_favorite", "user_id"), Long.class);
  private static final Field<Long> DF_DATASET_ID =
      field(name("dataset_favorite", "dataset_id"), Long.class);

  @Override
  public boolean existsByUserIdAndDatasetId(Long userId, Long datasetId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(DATASET_FAVORITE)
            .where(DF_USER_ID.eq(userId))
            .and(DF_DATASET_ID.eq(datasetId)));
  }

  @Override
  public void insert(Long userId, Long datasetId) {
    dsl.insertInto(DATASET_FAVORITE)
        .set(DF_USER_ID, userId)
        .set(DF_DATASET_ID, datasetId)
        .onConflictDoNothing()
        .execute();
  }

  @Override
  public void delete(Long userId, Long datasetId) {
    dsl.deleteFrom(DATASET_FAVORITE)
        .where(DF_USER_ID.eq(userId))
        .and(DF_DATASET_ID.eq(datasetId))
        .execute();
  }

  @Override
  public Set<Long> findDatasetIdsByUserId(Long userId) {
    return new HashSet<>(
        dsl.select(DF_DATASET_ID)
            .from(DATASET_FAVORITE)
            .where(DF_USER_ID.eq(userId))
            .fetch(r -> r.get(DF_DATASET_ID)));
  }
}
