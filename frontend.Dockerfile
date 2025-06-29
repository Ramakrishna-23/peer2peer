FROM node:slim
WORKDIR /app
RUN npm install -g pm2
COPY ui/package*.json ./
RUN npm install
COPY ui/ .

# Set build-time environment variable
ARG NEXT_PUBLIC_BACKEND_URL=https://filesharing.ramakrishna.tech/api
ENV NEXT_PUBLIC_BACKEND_URL=$NEXT_PUBLIC_BACKEND_URL

RUN npm run build
EXPOSE 3000
CMD ["pm2-runtime", "start", "npm", "--", "start"]