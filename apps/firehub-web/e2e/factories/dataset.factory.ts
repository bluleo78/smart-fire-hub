/**
 * 데이터셋 도메인 모킹 데이터 팩토리
 * src/types/dataset.ts 타입 기반으로 테스트용 객체를 생성한다.
 * overrides 파라미터로 특정 필드만 덮어쓸 수 있다.
 */

import type {
  CategoryResponse,
  DatasetColumnResponse,
  DatasetDetailResponse,
  DatasetResponse,
} from '@/types/dataset';

/** 카테고리 응답 객체 생성 */
export function createCategory(overrides?: Partial<CategoryResponse>): CategoryResponse {
  return {
    id: 1,
    name: '기본 카테고리',
    description: '테스트용 기본 카테고리',
    ...overrides,
  };
}

/** 데이터셋 컬럼 응답 객체 생성 */
export function createColumn(overrides?: Partial<DatasetColumnResponse>): DatasetColumnResponse {
  return {
    id: 1,
    columnName: 'id',
    displayName: 'ID',
    dataType: 'INTEGER',
    maxLength: null,
    isNullable: false,
    isIndexed: true,
    isPrimaryKey: true,
    description: '기본 키',
    columnOrder: 0,
    ...overrides,
  };
}

/** 데이터셋 목록용 응답 객체 생성 */
export function createDataset(overrides?: Partial<DatasetResponse>): DatasetResponse {
  return {
    id: 1,
    name: '테스트 데이터셋',
    tableName: 'test_dataset',
    description: '테스트용 데이터셋',
    category: createCategory(),
    datasetType: 'SOURCE',
    createdAt: '2024-01-01T00:00:00Z',
    isFavorite: false,
    tags: [],
    status: 'NONE',
    statusNote: null,
    statusUpdatedBy: null,
    statusUpdatedAt: null,
    ...overrides,
  };
}

/** 컬럼 및 태그를 포함한 데이터셋 상세 응답 객체 생성 */
export function createDatasetDetail(overrides?: Partial<DatasetDetailResponse>): DatasetDetailResponse {
  return {
    id: 1,
    name: '테스트 데이터셋',
    tableName: 'test_dataset',
    description: '테스트용 데이터셋',
    category: createCategory(),
    datasetType: 'SOURCE',
    createdBy: 'testuser',
    columns: [
      createColumn(),
      createColumn({ id: 2, columnName: 'name', displayName: '이름', dataType: 'TEXT', isPrimaryKey: false, columnOrder: 1 }),
    ],
    rowCount: 100,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: null,
    updatedBy: null,
    isFavorite: false,
    tags: ['테스트', '샘플'],
    status: 'NONE',
    statusNote: null,
    statusUpdatedBy: null,
    statusUpdatedAt: null,
    linkedPipelines: [],
    ...overrides,
  };
}

/** DatasetResponse 여러 개를 한 번에 생성 */
export function createDatasets(count: number): DatasetResponse[] {
  return Array.from({ length: count }, (_, i) =>
    createDataset({
      id: i + 1,
      name: `데이터셋 ${i + 1}`,
      tableName: `dataset_${i + 1}`,
    }),
  );
}

/** 기본 카테고리 목록 3개 생성 */
export function createCategories(): CategoryResponse[] {
  return [
    createCategory({ id: 1, name: '소방 데이터', description: '소방 관련 데이터 카테고리' }),
    createCategory({ id: 2, name: '통계 데이터', description: '통계 분석용 데이터 카테고리' }),
    createCategory({ id: 3, name: '기타', description: '기타 데이터 카테고리' }),
  ];
}
