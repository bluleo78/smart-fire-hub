FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /app
RUN npm install -g pnpm
COPY package.json tsconfig.json ./
RUN pnpm install
COPY src/ ./src/
RUN pnpm build

FROM node:22-alpine
WORKDIR /app
COPY --from=build /app/dist ./dist
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/package.json ./
EXPOSE 3002
CMD ["node", "dist/index.js"]
