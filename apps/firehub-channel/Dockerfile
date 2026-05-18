FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /app
RUN npm install -g pnpm
COPY package.json tsconfig.json ./
# pnpm 10.x strict 모드에서 transitive esbuild 의 빌드 스크립트가 실행되지 않으면
# ERR_PNPM_IGNORED_BUILDS 로 실패. esbuild 는 prebuilt binary 동봉이라 안전하게
# --ignore-scripts 로 우회 가능.
RUN pnpm install --ignore-scripts
COPY src/ ./src/
RUN pnpm build

FROM node:22-alpine
WORKDIR /app
COPY --from=build /app/dist ./dist
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/package.json ./
EXPOSE 3002
CMD ["node", "dist/index.js"]
