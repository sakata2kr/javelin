IMAGE_REPO_DOMAIN="ghcr.io/sakata2kr"
APP_NAME="javelin"
mvn package -U -DskipTests
docker buildx build -f Dockerfile --platform linux/amd64,linux/arm64 --push -t $IMAGE_REPO_DOMAIN/$APP_NAME:latest .
docker compose down
docker compose pull
docker compose up -d
