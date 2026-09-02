import type { EntityTypeDef } from '@/types/ontology';

import { createOntologySummaries } from '../../factories/mapping.factory';
import { createOntologyGraph, createOntologySchema } from '../../factories/ontology.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * (#396) 엔티티 타입 색상 회귀 테스트
 *
 * 무엇: 데모 6종(Incident/Building/Cause/Damage/Equipment/Regulation)과 **이름이 전혀 다른**
 *       온톨로지를 모킹해, 타입 필터 점과 스키마 캔버스 노드 윤곽선이 타입마다 실제로 다른
 *       색을 받는지(= 회색 하나로 뭉개지지 않는지) 단언한다.
 * 왜:   예전 구현은 6개 데모 타입명 리터럴 키 룩업이라, 사용자가 만든 온톨로지의 타입 대부분이
 *       폴백 회색(#64748b = rgb(100, 116, 139)) 하나로 수렴해 캔버스·필터·인스펙터에서 타입
 *       구분이 사실상 사라졌다. "점이 보인다"만 확인하면 그 회귀를 그대로 통과시키므로,
 *       **색 값 자체를 수집해 서로 다른지**를 검증해야 한다.
 */

/** 폴백 회색(DEFAULT_TYPE_COLOR #64748b)의 computed 표기 — 이 색으로 뭉개지는 것이 곧 결함이다. */
const FALLBACK_GRAY = 'rgb(100, 116, 139)';

/** 이슈 재현에 쓰인 "화재안전 통합 v2" 초안 온톨로지의 타입 9종 — 데모 6종과 겹치는 이름은 Damage뿐. */
const CUSTOM_TYPE_NAMES = [
  'FireIncident',
  'Facility',
  'FireCause',
  'FireProtectionSystem',
  'InvestigationReport',
  'Region',
  'FireStatistic',
  'Violation',
  'Damage',
];

function createCustomEntities(): EntityTypeDef[] {
  return CUSTOM_TYPE_NAMES.map((type, i) => ({
    id: 100 + i,
    type,
    description: `${type} 설명`,
    naming: '본문 표기 보존',
    // resolution을 섞어 두 그룹(정확 매칭/임베딩 해소)에 걸쳐 렌더되게 한다 — 그룹이 갈려도
    // 색 배정은 온톨로지 전체 타입 기준이어야 한다.
    resolution: i % 3 === 0 ? 'exact' : 'embedding',
    properties: [],
  }));
}

/** 초안 온톨로지(id=2) 스키마 — 관계는 이 테스트의 관심사가 아니라 비워 둔다. */
const customSchema = () =>
  createOntologySchema({ domain: '화재안전 통합 v2', entities: createCustomEntities(), relations: [] });

/** 타입 필터 패널의 각 토글 버튼 앞 색상 점의 computed background-color를 순서대로 수집한다. */
async function readDotColors(page: import('@playwright/test').Page): Promise<string[]> {
  return page.getByTestId('type-filter-list').evaluate((list) =>
    [...list.querySelectorAll('button > span:first-child')].map(
      (el) => getComputedStyle(el).backgroundColor,
    ),
  );
}

test.describe('#396 엔티티 타입 색상 — 데모 이름과 다른 온톨로지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await mockApi(page, 'GET', '/api/v1/ontology', createOntologySchema());
    await mockApi(page, 'GET', '/api/v1/ontology/1', createOntologySchema());
    await mockApi(page, 'GET', '/api/v1/ontology/2', customSchema());
    await mockApi(page, 'GET', '/api/v1/ontology/graph', createOntologyGraph());
    await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
  });

  /** 초안 온톨로지(id=2)로 전환하고 타입 9종이 모두 렌더될 때까지 기다린다. */
  async function selectCustomOntology(page: import('@playwright/test').Page) {
    await page.goto('/knowledge-graph/model');
    await expect(page.getByTestId('type-filter-list').getByRole('button')).toHaveCount(6);
    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: '건축물 대장' }).click();
    await expect(page.getByTestId('type-filter-list').getByRole('button')).toHaveCount(
      CUSTOM_TYPE_NAMES.length,
    );
  }

  test('타입 필터 점이 타입마다 서로 다른 색을 받는다(회색으로 뭉개지지 않는다)', async ({
    authenticatedPage: page,
  }) => {
    await selectCustomOntology(page);

    const colors = await readDotColors(page);
    expect(colors).toHaveLength(CUSTOM_TYPE_NAMES.length);
    // 핵심 단언 1 — 9개 타입이 9개 서로 다른 색을 받는다(팔레트 12색 이내라 순환도 없다).
    expect(new Set(colors).size).toBe(CUSTOM_TYPE_NAMES.length);
    // 핵심 단언 2 — 폴백 회색이 하나도 나오면 안 된다. 예전 구현에서는 Damage를 뺀 8개가 전부 이 색이었다.
    expect(colors).not.toContain(FALLBACK_GRAY);
  });

  // 라이트/다크 각각에서 캔버스 윤곽선이 타입마다 갈리는지 확인한다. next-themes는 localStorage의
  // 'theme'를 읽으므로 페이지 로드 **전에** 심어야 한다(로드 후 emulateMedia만으로는 반영되지 않는다 —
  // 실제로 그렇게 썼다가 라이트 값이 그대로 나와 단언이 공허해지는 것을 확인했다).
  for (const mode of ['light', 'dark'] as const) {
    test(`${mode}: 스키마 캔버스 노드 윤곽선도 타입마다 갈린다`, async ({ authenticatedPage: page }) => {
      await page.addInitScript((m) => localStorage.setItem('theme', m), mode);
      await selectCustomOntology(page);
      await expect(page.getByTestId('schema-graph')).toHaveAttribute(
        'data-node-count',
        String(CUSTOM_TYPE_NAMES.length),
      );
      // 테마가 실제로 적용됐는지 먼저 확인 — 이게 없으면 아래 단언이 두 모드에서 같은 것을 볼 수 있다.
      await expect(page.locator('html')).toHaveClass(new RegExp(`\\b${mode}\\b`));

      // 캔버스는 Canvas 렌더라 DOM 계산색이 없다 — dev에서 노출되는 cy 인스턴스의 노드 data로 검증한다.
      const borders = await page.evaluate(() => {
        const cy = (
          window as unknown as {
            __ontologySchemaCy?: { nodes(): { map(fn: (n: { data(k: string): string }) => string): string[] } };
          }
        ).__ontologySchemaCy;
        return cy ? cy.nodes().map((n) => n.data('border')) : [];
      });

      expect(borders).toHaveLength(CUSTOM_TYPE_NAMES.length);
      // 핵심 — 9개 노드가 9개 서로 다른 윤곽선 색을 갖는다.
      expect(new Set(borders).size).toBe(CUSTOM_TYPE_NAMES.length);
      // 폴백 윤곽선(slate-700/300)으로 뭉개지지 않는다.
      expect(borders).not.toContain(mode === 'dark' ? '#cbd5e1' : '#334155');
      // 모드별 shade가 실제로 다르다는 것까지 고정 — 라이트는 700, 다크는 300 계열이다.
      // (같은 값이 나오면 위 단언들이 두 테스트에서 같은 것을 보고 있다는 뜻이라 공허해진다.)
      expect(borders).toContain(mode === 'dark' ? '#fca5a5' : '#b91c1c');
    });
  }

  test('탭을 바꿔도 각 화면 안에서 타입 색은 계속 구분된다', async ({ authenticatedPage: page }) => {
    await selectCustomOntology(page);

    // 그래프 탐색(인스턴스) 탭은 기본 온톨로지(6타입) 기준 — 여기서도 회색 뭉침이 없어야 한다.
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expect(page.getByTestId('type-filter-list').getByRole('button')).toHaveCount(6);
    const instanceColors = await readDotColors(page);
    expect(new Set(instanceColors).size).toBe(6);
    expect(instanceColors).not.toContain(FALLBACK_GRAY);

    // 지식 모델 탭으로 되돌아오면 초안 온톨로지의 9색이 그대로 복원된다(색이 요동치지 않는다).
    await page.getByRole('tab', { name: '지식 모델' }).click();
    await expect(page.getByTestId('type-filter-list').getByRole('button')).toHaveCount(
      CUSTOM_TYPE_NAMES.length,
    );
    const back = await readDotColors(page);
    expect(new Set(back).size).toBe(CUSTOM_TYPE_NAMES.length);
    expect(back).not.toContain(FALLBACK_GRAY);
  });
});
