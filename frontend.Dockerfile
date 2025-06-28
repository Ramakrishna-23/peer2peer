FROM node:slim
WORKDIR /app
RUN npm install -g pm2
COPY ui/package*.json ./
RUN npm install
COPY ui/ .
RUN npm run build
EXPOSE 3000
CMD ["pm2-runtime", "start", "npm", "--", "start"]