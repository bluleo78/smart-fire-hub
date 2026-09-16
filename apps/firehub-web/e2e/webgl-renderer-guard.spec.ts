// playwright.config.ts가 넣는 GPU 런처 인자가 실제로 먹었는지를 하네스 안에서 단정한다.
// 왜 그 인자가 필요하고 언제 거는지는 e2e/gpu-launch.ts 주석이 든다.
// (smart-dcim/apps/dcim-web/e2e/webgl-renderer-guard.spec.ts에서 이식)
//
// pages/ 아래가 아니라 e2e/ 루트에 두는 이유 — 화면 회귀 스펙이 아니라 실행 환경 가드다.
import { expect, test } from '@playwright/test';

import { HARDWARE_GL, USE_HARDWARE_GL } from './gpu-launch';

test('headless Chromium이 하드웨어 ANGLE 렌더러를 쓴다', async ({ page }) => {
  test.skip(!USE_HARDWARE_GL, 'GPU 런처 인자를 걸지 않는 환경(CI·E2E_SOFTWARE_GL=1)');

  // 앱을 띄우지 않는다 — 렌더러는 Chromium 프로세스의 사실이라 빈 문서에서 읽어도 같다.
  await page.goto('about:blank');

  const renderer = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    const gl = canvas.getContext('webgl2') ?? canvas.getContext('webgl');
    if (!gl) return null;
    const ext = gl.getExtension('WEBGL_debug_renderer_info');
    return ext ? String(gl.getParameter(ext.UNMASKED_RENDERER_WEBGL)) : null;
  });

  // 렌더러를 못 읽은 경우를 "소프트웨어 렌더러"와 섞지 않는다 — 아래 실패 메시지는
  // E2E_SOFTWARE_GL=1을 가리키는데, 확장이 노출되지 않았을 뿐 GPU는 멀쩡한 상황에서
  // 그 안내를 따르면 인자가 통째로 빠져 이 가드가 지키려던 것을 스스로 끄게 된다.
  expect(
    renderer,
    'WEBGL_debug_renderer_info를 읽지 못했다 — 하드웨어 GPU 문제가 아니라 가드 자체를 점검한다',
  ).not.toBeNull();

  // 소프트웨어 래스터라이저의 부재가 아니라 요청한 백엔드의 존재를 단정한다 — 부재 단정은
  // 백엔드가 또 다른 소프트웨어 래스터라이저로 바뀌면 조용히 통과한다.
  expect(
    renderer,
    `WEBGL_debug_renderer_info로 읽은 실제 렌더러 (${HARDWARE_GL.rendererMarker}이 아니면 E2E_SOFTWARE_GL=1 참조)`,
  ).toContain(HARDWARE_GL.rendererMarker);
});
