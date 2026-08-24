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
    },
  },
])
