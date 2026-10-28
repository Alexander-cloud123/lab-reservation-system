# =====================================================================
# 前端镜像（Node.js 22.x 构建 + Nginx 静态托管）
# 需求依据：AGENTS.md 4.2 技术栈（Node.js 22.x + Vue 3.4 + Vite 5）
# 说明：构建产物为纯静态文件，运行期由 Nginx 托管并把 /api 反代到后端容器，
#       因此前端 axios 的 baseURL（/api）无需任何改动，也不产生跨域。
# =====================================================================

# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /app

# 使用国内 npm 镜像加速依赖安装（可通过 --build-arg NPM_REGISTRY=... 覆盖）
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry "${NPM_REGISTRY}"

# 先按 lockfile 安装依赖，源码变动时可复用该层缓存
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine

# 站点配置：SPA history 回退 + /api 反代 + SSE 关闭缓冲
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
