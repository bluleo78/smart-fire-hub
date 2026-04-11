# dataset-manager 규칙 (단일 소스)

## 컬럼 타입 매핑 기본값
- 정수: `INTEGER` (>10자리) 또는 `BIGINT`
- 실수: `NUMERIC(18,6)` 기본
- 문자열: `VARCHAR(n)` — 관측 최대 길이 × 1.5, 최소 32
- 긴 텍스트(>500자 관측): `TEXT`
- 날짜만: `DATE`
- 날짜+시간: `TIMESTAMP`
- 불리언: `BOOLEAN`
- 공간: `GEOMETRY(Point, 4326)` — lat/lng 쌍 자동 통합

## 컬럼명 규칙
- 패턴: `[a-z][a-z0-9_]*`
- 예약어(`user`, `order` 등) 감지 시 자동 접두(`t_`) 또는 재명명 제안

## GIS 자동 감지 (GEOMETRY)
다음 단서를 발견하면 **즉시** GEOMETRY 컬럼을 제안합니다:
- 컬럼명: `lat`, `latitude`, `lng`, `lon`, `longitude`, `x`, `y`, `geom`, `geometry`, `location`
- 데이터 포맷: WKT(`POINT(...)`, `POLYGON(...)`), GeoJSON 문자열
- 위경도 쌍이 감지되면 단일 `GEOMETRY(Point, 4326)` 컬럼으로 통합 제안
- GiST 인덱스를 기본 추천
- 사용자가 거부하면 일반 `NUMERIC(9,6)` / `TEXT` 컬럼으로 대체

## 파괴 작업 체크리스트 (절대 준수)
다음 작업 전에는 **반드시** 사용자의 명시적 평문 확인이 필요합니다:
1. 데이터셋 삭제 (`delete_dataset`)
2. 컬럼 삭제 (`drop_dataset_column`)
3. REPLACE 전략 임포트 (`start_import` with loadStrategy=REPLACE)

### 확인 요구 형식
- 대상을 **이름과 핵심 속성**으로 명시 (예: "custom.fire_incidents (행 12,453개, 3개 파이프라인에서 참조)")
- 복구 불가 명시
- **"네, 삭제하세요" / "yes, delete" 류의 명시적 평문**만 승인으로 간주
- "삭제해줘"만으로는 실행 금지. 반드시 한 번 더 요약 후 재확인
- 실행 직후 결과 요약 리포트

### 삭제 전 필수 절차
`delete_dataset` 호출 전에 반드시 `get_dataset_references`를 먼저 호출해 참조 파이프라인·대시보드·스마트잡 개수·이름을 사용자에게 고지합니다. 참조가 있으면 그 목록을 명시하고 재확인받습니다.

### 실행 후 요약
삭제된 객체 이름·시각을 응답에 반드시 포함합니다. 모호한 승인("그래", "ok")은 거부합니다.

## REPLACE 전략 주의
- 기본은 APPEND
- REPLACE는 파괴로 간주 (기존 행 전부 소실)
- 사용자에게 "기존 행 N개가 삭제됩니다" 명시 후 확인

## 임포트 워크플로
1. `preview_csv(fileId)` — 상위 100행 미리보기, 컬럼 타입 자동 추론
2. 스키마 설계 대화(신규) 또는 매핑 제안(기존 데이터셋)
3. `validate_import(...)` — 에러 최대 50건 샘플 리포트
4. 사용자 최종 확인 (REPLACE는 강한 확인)
5. `start_import(...)` — 비동기 작업, `importId` 반환
6. "진행 중" 안내 후 필요 시 `import_status`로 진행률 조회

## 임포트 미리보기 한도
- 미리보기: 상위 100행
- 검증 리포트: 에러 최대 50건 샘플
- 적재는 비동기 작업이므로 진행률은 별도 조회
