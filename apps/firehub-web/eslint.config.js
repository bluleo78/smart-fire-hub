import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import simpleImportSort from 'eslint-plugin-simple-import-sort'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  // 'coverage' 를 반드시 무시한다: vitest 커버리지 리포터가 생성하는 istanbul 자산
  // (block-navigation.js/prettify.js/sorter.js)에 eslint-disable 지시자가 들어 있어,
  // 단위 테스트를 먼저 돌린 워크스페이스에서만 경고 3건이 더 뜬다 — 즉 린트 결과가
  // 실행 순서에 의존하게 된다. 우리가 쓰지 않은 생성물이므로 검사 대상이 아니다.
  globalIgnores(['dist', 'coverage', 'src/components/ui']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    plugins: {
      'simple-import-sort': simpleImportSort,
    },
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    rules: {
      'simple-import-sort/imports': 'error',
      'simple-import-sort/exports': 'error',
      // 서버는 타임존 표기 없는 LocalDateTime 문자열을 내려준다. `new Date(str)` 은 그것을 브라우저
      // 로컬(KST)로 해석해 9시간 어긋난다 — #349, #533, #691 이 모두 같은 원인이었다. 호출부를 한 번
      // 쓸어도 규칙이 없으면 다음 화면에서 되살아나므로 문법 자체를 막는다.
      'no-restricted-syntax': [
        'error',
        {
          selector: "NewExpression[callee.name='Date'][arguments.length=1]",
          message:
            '서버 날짜 문자열은 lib/formatters 의 parseUtcDate 로 파싱할 것 (new Date 는 KST 로 해석돼 9시간 어긋난다, #691). epoch 숫자처럼 문자열이 아닌 인자만 eslint-disable 로 사유를 적어 예외 처리한다.',
        },
      ],
    },
  },
  {
    // 규칙 예외. formatters.ts 는 parseUtcDate 자신과 데이터셋 셀 포맷터가 사는 곳이고(셀 값은
    // 사용자 데이터라 UTC 계약이 적용되지 않는다), 테스트는 타임존을 명시한 고정 문자열로 값을
    // 만들므로 계약 위반이 아니다.
    files: ['src/lib/formatters.ts', '**/*.test.ts', '**/*.test.tsx'],
    rules: { 'no-restricted-syntax': 'off' },
  },
])
