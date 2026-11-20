# --- Compilación de Angular ---
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

# --- Nginx: sirve la SPA y enruta /api (config montada desde infrastructure/nginx) ---
FROM nginx:1.28-alpine
COPY --from=build /src/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
