FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
RUN mkdir -p /data/zhcp-files
COPY --from=build /src/target/qut-zhcp.jar /app/app.jar
EXPOSE 18088
ENTRYPOINT ["java","-Dfile.encoding=UTF-8","-jar","/app/app.jar"]
