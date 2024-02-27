IMAGE_REPO_DOMAIN="559616336583.dkr.ecr.ap-northeast-2.amazonaws.com"
APP_NAME="javelin"
mvn package -U -DskipTests
aws ecr get-login-password --region ap-northeast-2 | sudo docker login --username AWS --password-stdin $IMAGE_REPO_DOMAIN
sudo docker buildx build -f Dockerfile --platform linux/amd64,linux/arm64 --push -t $IMAGE_REPO_DOMAIN/$APP_NAME:latest .
kubectl delete -f ./k8s/$APP_NAME.yaml
kubectl apply -f ./k8s/$APP_NAME.yaml
