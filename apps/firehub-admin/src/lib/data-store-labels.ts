import type { DataStoreBackend, DataStoreProvisionMode } from '@/types/platform';

/**
 * 데이터셋 저장소 백엔드 표시 이름(WD-73). 생성 폼의 선택지와 테넌트 상세 카드가 같은 문구를 쓰도록 한곳에 둔다.
 * 서버 enum 이 늘면 타입 검사가 여기를 고치라고 알려 준다. 응답에 모르는 값이 오면 호출부가 enum 문자열을 그대로 보인다.
 */
export const BACKEND_LABELS: Record<DataStoreBackend, string> = {
  POSTGRESQL: 'PostgreSQL',
  TIBERO: 'Tibero',
  MYSQL: 'MySQL',
};

/** 외부 저장소 계정 프로비저닝 방식 표시 이름. */
export const MODE_LABELS: Record<DataStoreProvisionMode, string> = {
  DBA_AUTO: 'DBA 계정으로 자동 생성',
  SCRIPT: 'DBA가 스크립트로 생성',
};

/**
 * 테넌트 생성 폼의 기본 백엔드. 폼 기본값과 선택지의 "(기본)" 표시가 같은 값을 보도록 한곳에 둔다.
 * 상세 카드에는 "(기본)"을 붙이지 않는다(이미 정해진 값이라 의미 없다).
 */
export const DEFAULT_BACKEND: DataStoreBackend = 'POSTGRESQL';
