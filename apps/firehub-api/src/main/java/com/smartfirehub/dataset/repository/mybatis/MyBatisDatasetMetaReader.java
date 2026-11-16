package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.repository.DatasetMetaReader;
import com.smartfirehub.dataset.search.DatasetSourceTextBuilder;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetMetaReader 의 MyBatis 구현(WD-746) — {@code JooqDatasetMetaReader} 와 같은 의미.
 *
 * <p>왜: 데이터셋을 만들거나 바꿀 때마다 검색 원문을 다시 짓는데({@code DatasetEmbeddingService.syncSourceText}), 이 읽기가 jOOQ
 * 구현뿐이라 Tibero 설치본에서 데이터셋 생성이 전부 실패했다(WD-498 검증 설치에서 발견). 짝 검사({@code PlatformRepositoryPairTest})가
 * {@code @Component} jOOQ 클래스를 보지 않아 놓쳤다 — jOOQ 판을 {@code @Repository} + PG 조건으로 바꿔 검사 안에 넣었다.
 */
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. 테넌트 정책은 트랜잭션 시작 때 세션에 테넌트를 거므로
// 트랜잭션 없이 부르면 정책이 전 행을 막는다. REQUIRED 라 서비스가 연 트랜잭션에는 합류한다(jOOQ 판과 같다).
@PlatformMyBatisRepository
@Transactional
public class MyBatisDatasetMetaReader implements DatasetMetaReader {

  private final DatasetMetaMapper mapper;

  public MyBatisDatasetMetaReader(DatasetMetaMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 데이터셋 단건 메타를 합본 입력으로 구성한다. 컬럼은 이름과 표시명을 한 목록에 합친다(null·빈값은 빌더가 거른다) — jOOQ 판과 같은 모양.
   *
   * @return 데이터셋이 없으면 null
   */
  @Override
  public DatasetSourceTextBuilder.Input read(long datasetId) {
    DatasetMetaMapper.MetaRow row = mapper.selectMeta(datasetId);
    if (row == null) {
      return null;
    }
    List<String> columnNames = new ArrayList<>();
    for (DatasetMetaMapper.ColumnTextRow col : mapper.selectColumnTexts(datasetId)) {
      columnNames.add(col.columnName());
      columnNames.add(col.displayName());
    }
    return new DatasetSourceTextBuilder.Input(
        row.name(),
        row.description(),
        row.tableName(),
        columnNames,
        mapper.selectTagNames(datasetId),
        row.categoryName());
  }

  /** 백필용: 전체 데이터셋 id 목록(오름차순). */
  @Override
  public List<Long> findAllIds() {
    return mapper.selectAllIds();
  }
}
