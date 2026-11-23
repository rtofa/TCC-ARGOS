# Imagem do frontend do Argos: build do Vite servido pelo nginx, que tambem encaminha /api ao backend.
FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
# O entrypoint do nginx gera /etc/nginx/conf.d/default.conf a partir do template com envsubst.
ENV API_UPSTREAM=http://backend:8081
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
