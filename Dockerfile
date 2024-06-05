# Start with a base image containing Java runtime
FROM amazoncorretto:17-alpine

# Add Author info
LABEL maintainer="sakata2@gmail.com"

# Add a volume to /tmp
VOLUME /tmp

# Make port 8080 available to the world outside this container
EXPOSE 8080

# The application's jar file
WORKDIR "/"
COPY ./target/javelin-0.0.1.jar /app.jar

# Run the jar file
ENTRYPOINT ["java","-jar","/app.jar"]
