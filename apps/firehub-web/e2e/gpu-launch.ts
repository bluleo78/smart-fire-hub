// headless Chromium의 WebGL 백엔드 선택. playwright.config.ts(인자 주입)와
// webgl-renderer-guard.spec.ts(주입 결과 단정)가 같은 술어를 공유해야 가드가 대리값이 되지
// 않는다 — 조건을 양쪽에서 따로 계산하면 인자가 빠진 채로도 가드가 스킵돼 통과한다.
// (smart-dcim/apps/dcim-web/e2e/gpu-launch.ts에서 이식)

// `args`(요청)와 `rendererMarker`(가드가 단정할 렌더러 이름)를 한 레코드로 묶는다 — 백엔드를
// 바꾸면 둘이 함께 움직여야 하는데, 떼어 두면 요청만 바뀌고 단정이 낡아도 아무도 못 잡는다.
export const HARDWARE_GL = {
  args: ['--use-gl=angle', '--use-angle=metal', '--enable-gpu'],
  rendererMarker: 'Metal',
} as const;

// --use-angle=metal은 macOS 전용이고, CI(Linux)는 GPU가 없어 어차피 SwiftShader로 남는다.
// E2E_SOFTWARE_GL=1은 Metal ANGLE 초기화가 안 되는 맥을 위한 탈출구다 — 인자를 빼고 가드도
// 함께 스킵해 SwiftShader로 내려간다.
export const USE_HARDWARE_GL =
  process.platform === 'darwin' && !process.env.CI && process.env.E2E_SOFTWARE_GL !== '1';
