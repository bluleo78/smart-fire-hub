import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import simpleImportSort from 'eslint-plugin-simple-import-sort'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  // src/components/ui 는 firehub-web 에서 복사한 파일이라 우리 규칙으로 린트하지 않는다.
  // coverage 는 vitest --coverage 가 생성하는 산출물(coverage/unit/*.js) — 테스트를 먼저
  // 돌렸는지에 따라 린트 결과가 달라지는 것을 막는다(B-6).
  globalIgnores(['dist', 'src/components/ui', 'coverage']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    plugins: { 'simple-import-sort': simpleImportSort },
    languageOptions: { ecmaVersion: 2020, globals: globals.browser },
    rules: {
      'simple-import-sort/imports': 'error',
      'simple-import-sort/exports': 'error',
    },
  },
])
