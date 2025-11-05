# Start with a base image containing Java runtime
FROM amazoncorretto:25-alpine

# Add Author info
LABEL maintainer="sakata2@gmail.com"

# 작업 디렉토리 설정
WORKDIR /app

# 빌드된 JAR 복사 (JAR 이름이 고정되어 있지 않다면, 빌드 시 자동화 필요)
COPY build/libs/app.jar app.jar

# 포트 노출 (필요 시 수정)
EXPOSE 8080

# 앱 실행
ENTRYPOINT ["java", "-jar", "app.jar"]

