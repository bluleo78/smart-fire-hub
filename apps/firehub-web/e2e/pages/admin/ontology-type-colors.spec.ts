import { nodeTintAlpha } from '@/lib/ontology-colors';
import type { EntityTypeDef } from '@/types/ontology';

import { composite, contrast, hexToRgb } from '../../../src/styles/theme-tokens.test-helpers';
import { createOntologySummaries } from '../../factories/mapping.factory';
import { createOntologyGraph, createOntologySchema } from '../../factories/ontology.factory';
import { mockOntologyGraph, setupAdminAuth } from '../../fixtures/admin.fixture';
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

/**
 * 이슈 재현에 쓰인 "화재안전 통합 v2" 초안 온톨로지의 타입 9종.
 * (#493) 색 배정이 정렬 인덱스 대신 이름 해시(FNV-1a mod 12)로 바뀌면서, 해시값이 우연히 겹치는
 * 이름 조합은 "9개 전부 다른 색"이라는 이 테스트의 전제를 깨뜨릴 수 있다. 그래서 여기 9개는
 * hash(type) % 12가 서로 겹치지 않는 것을 사전에 확인해 둔 이름들이다.
 */
const CUSTOM_TYPE_NAMES = [
  'FireIncident',
  'Facility',
  'FireCause',
  'District',
  'Municipality',
  'Ward',
  'IncidentStat',
  'ResponseMetric',
  'SprinklerSystem',
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
    await mockOntologyGraph(page, createOntologyGraph());
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

  // WD-1070: cytoscape 는 background-color 의 rgba 알파를 버려 노드가 원색(500)으로 칠해지고, 700/300 라벨이
  // 배경에 묻혔다(약 2:1). 노드 레이어 캔버스의 실제 픽셀(padding 영역)을 읽어 컨테이너 배경 위에 합성한
  // "보이는 배경"과 라벨 색의 WCAG 대비를 잰다 — data(bg) 문자열만 보면 이 결함을 그대로 통과시키기 때문이다.
  for (const mode of ['light', 'dark'] as const) {
    test(`${mode}: 스키마 캔버스 노드 배경이 옅은 tint 라 라벨 대비가 4.5:1 이상이다 (WD-1070)`, async ({
      authenticatedPage: page,
    }) => {
      await page.addInitScript((m) => localStorage.setItem('theme', m), mode);
      await selectCustomOntology(page);
      await expect(page.getByTestId('schema-graph')).toHaveAttribute(
        'data-node-count',
        String(CUSTOM_TYPE_NAMES.length),
      );
      await expect(page.locator('html')).toHaveClass(new RegExp(`\\b${mode}\\b`));

      // cytoscape 가 아직 칠하기 전이거나 재배치 직후면 픽셀이 비어 있을 수 있어, 칠해질 때까지 측정·단언을 재시도한다.
      await expect(async () => {
        const results = await page.evaluate(() => {
          type CyNode = {
            data(k: string): string;
            renderedBoundingBox(): { x1: number; y1: number; y2: number };
          };
          const cy = (window as unknown as { __ontologySchemaCy?: { zoom(): number; nodes(): { map<T>(fn: (n: CyNode) => T): T[] } } })
            .__ontologySchemaCy;
          const container = document.querySelector('[data-testid="schema-graph"]');
          const nodeCanvas = container?.querySelector<HTMLCanvasElement>('canvas[data-id="layer2-node"]');
          const nodeCtx = nodeCanvas?.getContext('2d');
          if (!cy || !container || !nodeCanvas || !nodeCtx) return [];

          // 1x1 캔버스로 임의 CSS 색(oklch 등)을 RGBA 로 변환한다.
          const probe = document.createElement('canvas');
          probe.width = 1;
          probe.height = 1;
          const pctx = probe.getContext('2d')!;
          const toRgba = (css: string): number[] => {
            pctx.clearRect(0, 0, 1, 1);
            pctx.fillStyle = css;
            pctx.fillRect(0, 0, 1, 1);
            return [...pctx.getImageData(0, 0, 1, 1).data];
          };
          // 컨테이너부터 위로 올라가며 처음 불투명한 배경을 찾는다(컨테이너 자체는 투명일 수 있다).
          let bg = [255, 255, 255, 255];
          for (let el: Element | null = container; el; el = el.parentElement) {
            const c = toRgba(getComputedStyle(el).backgroundColor);
            if (c[3] === 255) {
              bg = c;
              break;
            }
          }
          // 화면(CSS px) → 캔버스 픽셀 배율 — devicePixelRatio 대신 캔버스 실제 크기로 구한다(cytoscape 가 쓰는 배율 그대로).
          const scale = nodeCanvas.width / nodeCanvas.clientWidth;
          // 노드 안쪽 오프셋(그래프 좌표) — 윤곽선 1.5px + padding 10px 의 가운데. 줌을 곱해 화면 px 로 옮긴다
          // (뷰포트·줌이 바뀌어도 윤곽선과 라벨 사이 순수 배경 영역을 읽게 — WD-1070 리뷰 후속).
          const inset = (1.5 + 10 / 2) * cy.zoom();
          // 날 데이터만 돌려준다 — 합성·대비 계산은 Node 쪽에서 공용 헬퍼로 한다.
          return cy.nodes().map((n) => {
            const box = n.renderedBoundingBox();
            const px = Math.round((box.x1 + inset) * scale);
            const py = Math.round(((box.y1 + box.y2) / 2) * scale);
            return { sample: [...nodeCtx.getImageData(px, py, 1, 1).data], bg, text: n.data('text') };
          });
        });

        expect(results).toHaveLength(CUSTOM_TYPE_NAMES.length);
        // 기대 알파 — 소스의 tint 상수를 255 스케일로 옮긴 값. 샘플 점이 노드를 빗나가면(alpha 0 → 페이지 배경)
        // 대비가 공허하게 통과하므로 알파가 tint 값 근처인지 고정한다.
        const expectedAlpha = Math.round(nodeTintAlpha(mode === 'dark') * 255);
        for (const { sample, bg, text } of results) {
          const [r, g, b, a] = sample;
          expect(a).toBeLessThan(255); // 원색 불투명이 아니다
          expect(Math.abs(a - expectedAlpha)).toBeLessThanOrEqual(6);
          const visible = composite({ r, g, b, a: a / 255 }, { r: bg[0], g: bg[1], b: bg[2], a: 1 });
          expect(contrast(hexToRgb(text), visible)).toBeGreaterThanOrEqual(4.5);
        }
      }).toPass({ timeout: 10_000 });
    });
  }

  test('탭을 바꿔도 각 화면 안에서 타입 색은 계속 구분된다', async ({ authenticatedPage: page }) => {
    await selectCustomOntology(page);

    // 그래프 탐색(인스턴스) 탭도 지식 모델 탭과 같은 온톨로지 선택을 공유한다(#677 후속) — 탭을
    // 바꿔도 선택된 온톨로지(초안, 9타입)가 그대로 유지되고 색도 요동치지 않는다.
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expect(page.getByTestId('type-filter-list').getByRole('button')).toHaveCount(CUSTOM_TYPE_NAMES.length);
    const instanceColors = await readDotColors(page);
    expect(new Set(instanceColors).size).toBe(CUSTOM_TYPE_NAMES.length);
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
