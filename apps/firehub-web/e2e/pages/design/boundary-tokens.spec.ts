import type { Locator, Page } from '@playwright/test';

import { createCategories, createColumnStats, createDatasetDetail } from '../../factories/dataset.factory';
import { createLevelSummary } from '../../factories/security-level.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks, setupDatasetMocks } from '../../fixtures/dataset.fixture';
import { clickToolbarAddStep, grantPipelinePermissions } from '../../fixtures/pipeline.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/**
 * 영역 경계 단계 회귀 (WD-755)
 *
 * 무엇: 실제로 그려진 화면에서 계산된 스타일(getComputedStyle)로 경계 단계를 확인한다.
 *   (a) 카드 면이 페이지 바탕과 갈라진다 — 다크는 반투명 카드라 바탕 위에 실제로 겹친 색으로 잰다.
 *   (b) 표 본문 행의 아래 선은 행 구분선(--divider), 머리 행 아래 선은 외곽(--border), 마지막 행은 아래 선이 없다.
 *   (c) 라이트·다크 둘 다 — 테마마다 같은 페이지를 한 번 열고 (a)·(b) 를 단계(test.step)로 본다.
 *   그리고 마지막 행의 아래 선을 빼는 규칙(row-divider)이 행 왼쪽 표식(전부 null 컬럼의 border-l-4)까지 지우지 않는지.
 * 왜: 토큰 대비는 design-tokens.contrast.test.ts 가 CSS 값으로 지키지만, 화면이 토큰을 실제로 쓰는지·
 *     base 규칙(divide-*)·row-divider·tailwind 클래스 충돌로 선이 바뀌는지는 렌더된 결과로만 보인다.
 */

const THEMES = ['light', 'dark'] as const;
type Theme = (typeof THEMES)[number];

/** next-themes 는 localStorage 'theme' 을 읽어 html 에 class 를 붙인다 — 첫 렌더 전에 심고, 시스템 색 구성표도 맞춘다. */
async function useTheme(page: Page, theme: Theme) {
  await page.emulateMedia({ colorScheme: theme });
  await page.addInitScript((t) => localStorage.setItem('theme', t), theme);
}

async function expectThemeApplied(page: Page, theme: Theme) {
  if (theme === 'dark') await expect(page.locator('html')).toHaveClass(/\bdark\b/);
  else await expect(page.locator('html')).not.toHaveClass(/\bdark\b/);
}

/**
 * 같은 요소에서 읽은 CSS 변수 값들을 브라우저가 계산한 색 문자열로 정규화해 한 번에 돌려준다.
 * var 원문(`oklch(1 0 0 / 9%)`)과 계산값(`oklch(1 0 0 / 0.09)`)은 표기가 달라, 탐침 요소에 값을 넣어 같은 직렬화로 맞춘다.
 */
async function resolvedVars<N extends string>(target: Locator, names: readonly N[]): Promise<Record<N, string>> {
  return target.evaluate((el, varNames) => {
    const probe = document.createElement('div');
    document.body.appendChild(probe);
    const out: Record<string, string> = {};
    for (const name of varNames) {
      probe.style.color = getComputedStyle(el).getPropertyValue(name).trim();
      out[name] = getComputedStyle(probe).color;
    }
    probe.remove();
    return out;
  }, names) as Promise<Record<N, string>>;
}

/**
 * 카드와 페이지 바탕을 실제로 칠해 본 픽셀 색의 대비(WCAG 상대 휘도 비).
 * 다크 카드는 흰 4.5% 알파라 문자열만 비교하면 의미가 없다 — 바탕 위에 겹쳐 칠한 결과로 잰다.
 */
async function cardOverPageContrast(card: Locator): Promise<number> {
  return card.evaluate((el) => {
    const pageBg = getComputedStyle(document.body).backgroundColor;
    const cardBg = getComputedStyle(el).backgroundColor;
    const canvas = document.createElement('canvas');
    canvas.width = 1;
    canvas.height = 1;
    const ctx = canvas.getContext('2d')!;
    const paint = (layers: string[]) => {
      ctx.clearRect(0, 0, 1, 1);
      for (const c of layers) {
        ctx.fillStyle = c;
        ctx.fillRect(0, 0, 1, 1);
      }
      const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
      return [r, g, b];
    };
    const lum = ([r, g, b]: number[]) => {
      const ch = (v: number) => {
        const s = v / 255;
        return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
      };
      return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
    };
    const a = lum(paint([pageBg]));
    const b = lum(paint([pageBg, cardBg]));
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  });
}

test.describe('영역 경계 단계 — 데이터셋 목록 (WD-755)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
    // 「최근 접근」 카드(bg-card)가 보이도록 현재 사용자(1)·테넌트(1) 이력을 심는다
    await page.addInitScript(() => {
      localStorage.setItem(
        'sfh-recent-datasets:1:1',
        JSON.stringify([{ id: 99, name: '경계 확인 카드', tableName: 'boundary_card', accessedAt: new Date().toISOString() }]),
      );
    });
    await setupDatasetMocks(page);
  });

  for (const theme of THEMES) {
    test(`${theme}: 카드 면과 표 선이 경계 단계를 따른다`, async ({ authenticatedPage: page }) => {
      await useTheme(page, theme);
      await page.goto('/data/datasets');
      await expectThemeApplied(page, theme);

      await test.step('카드 면 — 페이지 바탕과 갈라지고 카드 색은 --card 다', async () => {
        const card = page.getByRole('link', { name: /경계 확인 카드/ });
        await expect(card).toBeVisible();

        const { cardBg, pageBg } = await card.evaluate((el) => ({
          cardBg: getComputedStyle(el).backgroundColor,
          pageBg: getComputedStyle(document.body).backgroundColor,
        }));
        const vars = await resolvedVars(card, ['--card']);
        expect(cardBg).toBe(vars['--card']);
        expect(cardBg).not.toBe(pageBg);
        // 경계 단계 기준(카드 / 바탕 ≥ 1.04)을 렌더된 색으로 다시 확인 — 다크는 반투명 카드를 바탕 위에 겹친 결과
        expect(await cardOverPageContrast(card)).toBeGreaterThanOrEqual(1.04);
      });

      await test.step('표 선 — 본문 행 아래는 --divider, 머리 행 아래는 --border, 마지막 행은 선이 없다', async () => {
        const table = page.getByRole('table', { name: '데이터셋 목록' });
        const bodyRows = table.locator('tbody tr');
        await expect(bodyRows).toHaveCount(5);

        const bottomLine = (row: Locator) =>
          row.evaluate((el) => {
            const st = getComputedStyle(el);
            return { width: st.borderBottomWidth, style: st.borderBottomStyle, color: st.borderBottomColor };
          });
        const vars = await resolvedVars(table, ['--divider', '--border']);

        expect(await bottomLine(bodyRows.first())).toEqual({ width: '1px', style: 'solid', color: vars['--divider'] });
        // 마지막 행은 row-divider 가 스스로 선을 뺀다(divide-y 와 같은 뜻) — 카드 외곽선과 겹쳐 두 줄로 보이지 않게
        expect((await bottomLine(bodyRows.last())).width).toBe('0px');

        // 머리 행은 외곽 단계(border) — 행 구분선보다 진해서 머리와 본문이 갈라진다
        const headLine = await bottomLine(table.locator('thead tr'));
        expect(headLine.color).toBe(vars['--border']);
        expect(headLine.color).not.toBe(vars['--divider']);
      });

      await test.step('표 면 — 바탕 위에 바로 놓인 목록 표도 카드 단계(--card)라 행 선·머리 띠가 카드 위 단계로 읽힌다', async () => {
        const table = page.getByRole('table', { name: '데이터셋 목록' });
        // <table> 을 감싼 스크롤 상자(table-container)의 바깥 상자가 표 면이다
        const surface = table.locator('xpath=../..');
        const { surfaceBg, pageBg } = await surface.evaluate((el) => ({
          surfaceBg: getComputedStyle(el).backgroundColor,
          pageBg: getComputedStyle(document.body).backgroundColor,
        }));
        const vars = await resolvedVars(surface, ['--card']);
        expect(surfaceBg).toBe(vars['--card']);
        expect(surfaceBg).not.toBe(pageBg);
      });

      await test.step('머리 띠 — 공용 표 thead 는 알파 없는 단색 --muted 라 본문(카드 면)과 갈라진다 (WD-1121, §1-7 배경 3단)', async () => {
        const table = page.getByRole('table', { name: '데이터셋 목록' });
        const thead = table.locator('thead[data-slot="table-header"]');
        // 표 면 = <table> 을 감싼 스크롤 상자의 바깥 상자(위 단계와 같다)
        const surface = table.locator('xpath=../..');
        const headBg = await thead.evaluate((el) => getComputedStyle(el).backgroundColor);
        const surfaceBg = await surface.evaluate((el) => getComputedStyle(el).backgroundColor);
        const vars = await resolvedVars(thead, ['--muted']);
        expect(headBg).toBe(vars['--muted']);
        expect(headBg).not.toBe(surfaceBg);
        // 클래스에 /50 같은 알파를 덧대지 않는다(§1-7) — 라이트는 불투명 토큰 그대로(다크 --muted 는 토큰 자체가 반투명이라 칠한 알파는 재지 않는다)
        if (theme === 'light') expect(await paintedAlpha(thead)).toBe(255);
      });
    });
  }
});

test.describe('표 마지막 행 — 아래 선만 끄고 왼쪽 표식은 남긴다 (WD-755)', () => {
  test('전부 null 인 컬럼이 마지막 행이어도 왼쪽 destructive 표식(4px)이 보인다', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
    await setupDatasetDetailMocks(page, 1);
    // 기본 상세의 마지막 컬럼(name)을 100% NULL 로 — 그 행이 tbody 의 마지막 행이 된다
    await mockApi(
      page,
      'GET',
      '/api/v1/datasets/1/stats',
      createColumnStats({ name: { nullCount: 100, nullPercent: 100, distinctCount: 0, topValues: [] } }),
    );

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '필드' }).click();

    const emptyRow = page.getByTestId('empty-column-row');
    await expect(emptyRow).toHaveCount(1);
    // 정말 마지막 행인지 먼저 확인 — 아니면 이 시험은 아무것도 지키지 못한다
    expect(await emptyRow.evaluate((el) => el === el.parentElement?.lastElementChild)).toBe(true);

    // 표식은 통계가 도착한 뒤 붙고 행에 transition-colors 가 있어 색이 잠깐 섞여 보인다 — 전환이 끝날 때까지 기다린다
    const { '--destructive': destructive } = await resolvedVars(emptyRow, ['--destructive']);
    await expect
      .poll(() =>
        emptyRow.evaluate((el) => {
          const s = getComputedStyle(el);
          return { left: s.borderLeftWidth, leftColor: s.borderLeftColor, bottom: s.borderBottomWidth };
        }),
      )
      .toEqual({ left: '4px', leftColor: destructive, bottom: '0px' });
  });
});

/** 요소의 계산된 배경색을 투명한 1px 캔버스에 칠해 본 알파(0~255) — oklch·rgb 등 표기와 상관없이 불투명한지 잰다. */
async function paintedAlpha(target: Locator): Promise<number> {
  return target.evaluate((el) => {
    const canvas = document.createElement('canvas');
    canvas.width = 1;
    canvas.height = 1;
    const ctx = canvas.getContext('2d')!;
    ctx.clearRect(0, 0, 1, 1);
    ctx.fillStyle = getComputedStyle(el).backgroundColor;
    ctx.fillRect(0, 0, 1, 1);
    return ctx.getImageData(0, 0, 1, 1).data[3];
  });
}

test.describe('캔버스 위에 떠 있는 버튼 — 불투명한 면 (WD-778)', () => {
  /**
   * 공용 outline 버튼은 WD-755 부터 쉬는 면이 투명이라, ReactFlow 캔버스 위에 떠 있으면 팬할 때 노드·엣지·점 격자가
   * 글자 뒤로 비친다. 캔버스 위 버튼만 페이지 바탕(--background)으로 칠해 가린다(공용 outline 은 투명 그대로).
   * 다크 outline 은 dark:bg-input/30(반투명)이라 다크에서도 같은 문제가 있어 두 테마를 다 본다.
   */
  for (const theme of THEMES) {
    test(`${theme}: 「자동 정렬」·「스텝 추가」 버튼 면이 불투명한 --background 이고 hover 면도 불투명하다`, async ({ authenticatedPage: page }) => {
      await grantPipelinePermissions(page);
      await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });
      await useTheme(page, theme);
      await page.goto('/pipelines/new');
      await expectThemeApplied(page, theme);

      // 빈 상태의 「스텝 추가」로 첫 스텝을 만들어야 캔버스와 우상단 떠 있는 버튼이 나타난다
      await clickToolbarAddStep(page);
      const autoLayout = page.getByRole('button', { name: '자동 정렬' });
      await expect(autoLayout).toBeVisible();
      // 캔버스 위 버튼 묶음(우상단) 안의 「스텝 추가」 — 자동 정렬과 같은 부모에 있다
      const addStep = autoLayout.locator('xpath=..').getByRole('button', { name: '스텝 추가', exact: true });
      await expect(addStep).toBeVisible();
      // 쉬는 면을 잰다 — 방금 누른 자리의 포인터가 버튼 위에 남아 hover 면(accent·input/50)을 재지 않게 치운다
      await page.mouse.move(0, 0);

      for (const button of [autoLayout, addStep]) {
        const { '--background': background } = await resolvedVars(button, ['--background']);
        // 버튼에 transition-all 이 있어 hover 가 풀리는 동안 색이 섞일 수 있다 — 전환이 끝날 때까지 기다린다
        await expect.poll(() => button.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe(background);
        expect(await paintedAlpha(button)).toBe(255);
      }

      // hover 면도 불투명해야 한다(WD-778) — 다크 outline 의 dark:hover:bg-input/50·다크 --accent 는 반투명이라 hover 때 캔버스가 비쳤다.
      // 라이트는 불투명한 --accent, 다크는 한 단계 떠 있는 불투명 --popover 로 칠하고, 쉬는 면(--background)과 달라 hover 가 보여야 한다.
      const hoverVar = theme === 'dark' ? '--popover' : '--accent';
      for (const button of [autoLayout, addStep]) {
        const vars = await resolvedVars(button, ['--background', hoverVar]);
        await button.hover();
        await expect.poll(() => button.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe(vars[hoverVar]);
        expect(vars[hoverVar]).not.toBe(vars['--background']);
        expect(await paintedAlpha(button)).toBe(255);
        await page.mouse.move(0, 0);
      }
    });
  }
});

test.describe('카드 안의 표 — 감싸개가 칠하지 않는다 (WD-778)', () => {
  /**
   * 표 감싸개는 바탕 위 목록 표를 위해 bg-card 로 칠한다. 다크 --card 는 반투명(흰 4.5%)이라 카드 안에서 또 칠하면
   * 9% 남짓으로 겹쳐 표만 밝은 사각형으로 떠 보인다 — 카드 안(data-slot="card" 조상)에서는 감싸개가 투명이어야 한다.
   * 바탕 위 목록 표가 여전히 --card 인지는 위 「데이터셋 목록」 describe 의 「표 면」 단계가 지킨다.
   */
  for (const theme of THEMES) {
    test(`${theme}: 보안 탭 허용 목록 표의 감싸개는 투명하고 카드 면은 --card 다`, async ({ authenticatedPage: page }) => {
      await setupAdminAuth(page);
      await setupDatasetDetailMocks(page, 1);
      await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
      await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
      await mockApi(
        page,
        'GET',
        '/api/v1/datasets/1',
        createDatasetDetail({ id: 1, name: '인사_평가_2026', securityLevel: createLevelSummary('기밀') }),
      );
      await setupSecurityLevelMocks(page);
      await mockApi(page, 'GET', '/api/v1/datasets/1/access-grants', [
        { id: 11, type: 'ROLE', subjectId: 5, subjectName: '인사팀', grantedByName: '양동희', grantedAt: '2026-10-07T10:00:00' },
      ]);
      await useTheme(page, theme);
      await page.goto('/data/datasets/1?tab=security');
      await expectThemeApplied(page, theme);

      const table = page.getByRole('table').filter({ hasText: '인사팀' });
      await expect(table).toBeVisible();
      const card = table.locator('xpath=ancestor::*[@data-slot="card"][1]');
      await expect(card).toHaveCount(1);

      // <table> 을 감싼 스크롤 상자(table-container)의 바깥 상자가 표 면(감싸개)이다
      const surface = table.locator('xpath=../..');
      expect(await paintedAlpha(surface)).toBe(0);

      const { '--card': cardColor } = await resolvedVars(card, ['--card']);
      expect(await card.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe(cardColor);
    });
  }
});

test.describe('row-divider — 한 클래스 덮어쓰기가 이긴다 (WD-778)', () => {
  /**
   * row-divider 가 특이도 0,2,0(.row-divider:not(:last-child))이거나 유틸리티 순서상 뒤에 찍히면, 행에 준 border-b-0 같은
   * 한 클래스 덮어쓰기와 두 줄 머리(thead)의 [&_tr]:border-border 가 진다. 실제 컴파일된 CSS 로 확인하려고 이미 열린
   * 화면에 표를 직접 만들어 넣는다(이 조합을 쓰는 실제 화면은 아직 없다). 쓰는 클래스는 모두 src 에 있는 것만 쓴다.
   */
  test('border-b-0 행은 선이 없고, 두 줄 머리의 첫 행은 --border, 나머지 행 선은 그대로다', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
    await setupDatasetMocks(page);
    await useTheme(page, 'light');
    await page.goto('/data/datasets');
    await expect(page.getByRole('table', { name: '데이터셋 목록' })).toBeVisible();

    await page.evaluate(() => {
      const host = document.createElement('div');
      host.innerHTML = `
        <table data-testid="row-divider-probe">
          <thead class="[&_tr]:border-b [&_tr]:border-border">
            <tr class="row-divider" data-testid="head-1"><th>머리 1</th></tr>
            <tr class="row-divider" data-testid="head-2"><th>머리 2</th></tr>
          </thead>
          <tbody>
            <tr class="row-divider" data-testid="body-1"><td>본문 1</td></tr>
            <tr class="row-divider border-b-0" data-testid="body-2"><td>본문 2</td></tr>
            <tr class="row-divider" data-testid="body-3"><td>본문 3</td></tr>
          </tbody>
        </table>`;
      document.body.appendChild(host);
    });

    const probe = page.getByTestId('row-divider-probe');
    const bottom = (id: string) =>
      page.getByTestId(id).evaluate((el) => {
        const st = getComputedStyle(el);
        return { width: st.borderBottomWidth, color: st.borderBottomColor };
      });
    const vars = await resolvedVars(probe, ['--divider', '--border']);

    // 덮어쓰기가 없는 본문 행은 그대로 row-divider 선(--divider 1px)
    expect(await bottom('body-1')).toEqual({ width: '1px', color: vars['--divider'] });
    // 한 클래스 덮어쓰기(border-b-0)가 이긴다 — 마지막 행이 아니어도 선이 없다
    expect((await bottom('body-2')).width).toBe('0px');
    // 마지막 행은 여전히 선이 없다
    expect((await bottom('body-3')).width).toBe('0px');
    // 두 줄 머리: 첫 행은 마지막 자식이 아니지만 머리 색([&_tr]:border-border)이 이긴다
    expect(await bottom('head-1')).toEqual({ width: '1px', color: vars['--border'] });
    expect(await bottom('head-2')).toEqual({ width: '1px', color: vars['--border'] });
  });
});
