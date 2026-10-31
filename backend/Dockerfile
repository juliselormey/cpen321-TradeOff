# syntax=docker/dockerfile:1

FROM node:24-alpine
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# Generate the client for this schema; generation does not connect to a database.
RUN DATABASE_URL=mysql://unused:unused@localhost:3306/unused npx prisma generate

# sets process.env.PORT
ENV PORT=3000 
ENV NODE_ENV=development

# documents that the container listens on port 3000
EXPOSE 3000

CMD ["npm", "run", "dev"]
