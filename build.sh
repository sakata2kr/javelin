IMAGE_REPO_DOMAIN="ghcr.io/sakata2kr"
APP_NAME="javelin"
./gradlew clean build -x test
docker buildx build -f Dockerfile --platform linux/amd64,linux/arm64 --push -t $IMAGE_REPO_DOMAIN/$APP_NAME:latest .
