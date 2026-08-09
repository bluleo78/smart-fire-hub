import { describe, expect, it } from 'vitest';

import {
  DATA_TYPES,
  RESERVED_PROPERTY_NAMES,
  RESOLUTIONS,
  validateDomain,
  validateEntityTypeName,
  validatePropertyName,
  validateRelationName,
} from '@/lib/ontology-validation';

// 문구는 요소 단위 편집 폼(EntityInspector/RelationInspector)이 오늘 실제로 보여주는 것과
// byte-identical해야 한다(공유 대상은 문자열이 아니라 규칙 — 예약어 집합/enum/진단 순서).
// 순서 회귀(#302)를 잡는 것이 핵심이므로 각 그룹은 "blank가 먼저"를 명시적으로 검증한다.

describe('constants', () => {
  it('예약어 집합은 Neo4j 노드 정체성 필드 5개다', () => {
    expect(RESERVED_PROPERTY_NAMES).toEqual(new Set(['key', 'type', 'name', 'sourceChunkIds', 'schemaVersion']));
  });

  it('DATA_TYPES/RESOLUTIONS는 백엔드 OntologyRules와 동일한 허용값이다', () => {
    expect(DATA_TYPES).toEqual(['text', 'number', 'date']);
    expect(RESOLUTIONS).toEqual(['embedding', 'exact']);
  });
});

describe('validatePropertyName', () => {
  it('빈 속성명은 빈 이름으로 진단된다(중복이 아니라)', () => {
    expect(validatePropertyName('  ', 'Sensor', ['  '])).toBe('속성명을 입력하세요');
  });

  it('예약어는 중복보다 먼저 진단된다', () => {
    expect(validatePropertyName('type', 'Sensor', ['type'])).toBe('예약어는 속성명으로 쓸 수 없습니다(Sensor): type');
  });

  it('같은 스코프 내 중복 속성명을 진단한다', () => {
    expect(validatePropertyName('model', 'Sensor', ['model'])).toBe('중복된 속성명(Sensor): model');
  });

  it('자기 이름으로의 리네임은 중복이 아니다(excludeName)', () => {
    expect(validatePropertyName('model', 'Sensor', ['model'], 'model')).toBeNull();
  });

  it('excludeName은 자기 이름만 빼고 다른 속성과의 충돌은 그대로 잡는다', () => {
    expect(validatePropertyName('serial', 'Sensor', ['model', 'serial'], 'model')).toBe(
      '중복된 속성명(Sensor): serial',
    );
  });

  it('예약어/중복 비교는 trim하지 않는다(백엔드와 동일) — 호출부가 trim해서 넘긴다', () => {
    expect(validatePropertyName(' type ', 'Sensor', [])).toBeNull();
  });

  it('문제 없는 속성명은 null을 반환한다', () => {
    expect(validatePropertyName('temperature', 'Sensor', ['humidity'])).toBeNull();
  });
});

describe('validateEntityTypeName', () => {
  it('빈 타입명은 빈 이름으로 진단된다', () => {
    expect(validateEntityTypeName('  ', ['Incident'])).toBe('타입 이름을 입력하세요.');
  });

  it('이미 존재하는 타입명은 중복으로 진단된다', () => {
    expect(validateEntityTypeName('Incident', ['Incident', 'Building'])).toBe('이미 존재하는 타입입니다: Incident');
  });

  it('자기 이름으로의 리네임은 중복이 아니다(excludeName)', () => {
    expect(validateEntityTypeName('Incident', ['Incident', 'Building'], 'Incident')).toBeNull();
  });

  it('excludeName은 자기 이름만 빼고 다른 타입과의 충돌은 그대로 잡는다', () => {
    expect(validateEntityTypeName('Building', ['Incident', 'Building'], 'Incident')).toBe(
      '이미 존재하는 타입입니다: Building',
    );
  });

  it('문제 없는 타입명은 null을 반환한다', () => {
    expect(validateEntityTypeName('Sensor', ['Incident', 'Building'])).toBeNull();
  });
});

describe('validateRelationName', () => {
  it('빈 관계명은 주어/목적어를 포함한 문구로 진단된다', () => {
    expect(validateRelationName('  ', 'Incident', 'Building')).toBe('관계명을 입력하세요(Incident → Building)');
  });

  it('문제 없는 관계명은 null을 반환한다', () => {
    expect(validateRelationName('CAUSED_BY', 'Incident', 'Building')).toBeNull();
  });
});

describe('validateDomain', () => {
  it('빈 도메인명은 빈 이름으로 진단된다', () => {
    expect(validateDomain('  ')).toBe('도메인명을 입력하세요.');
  });

  it('문제 없는 도메인명은 null을 반환한다', () => {
    expect(validateDomain('화재조사 보고서')).toBeNull();
  });
});
