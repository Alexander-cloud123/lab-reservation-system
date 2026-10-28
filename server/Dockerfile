# =====================================================================
# 后端镜像（Spring Boot 3.2.10 / JDK 21）
# 需求依据：AGENTS.md 4.2 技术栈（JDK 21 LTS + Maven 3.9.x）
# 说明：镜像内仅做编译与运行，不改变任何业务配置；数据库地址、Redis 地址、
#       AI 密钥等全部经环境变量注入（见 docker-compose.yml），禁止硬编码进镜像。
# =====================================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 镜像内 Maven 走阿里云镜像（与本机 ~/.m2/settings.xml 一致），避免中央仓库跨境拉取超时
ARG MAVEN_MIRROR=https://maven.aliyun.com/repository/public
RUN mkdir -p /root/.m2 && \
    printf '%s\n' \
      '<?xml version="1.0" encoding="UTF-8"?>' \
      '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
      '  <mirrors>' \
      '    <mirror>' \
      '      <id>aliyun</id>' \
      '      <mirrorOf>central</mirrorOf>' \
      '      <url>'"${MAVEN_MIRROR}"'</url>' \
      '    </mirror>' \
      '  </mirrors>' \
      '</settings>' > /root/.m2/settings.xml

# 先只拷 pom 拉依赖，源码变动时可复用该层缓存
COPY pom.xml ./
RUN mvn -B -q dependency:resolve

COPY src ./src
# 跳过测试：测试依赖真实数据库/端口，属构建期外的验证环节（见 docs 测试报告）
RUN mvn -B -q -Dmaven.test.skip=true package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-Xms256m -Xmx512m -Duser.timezone=Asia/Shanghai"

COPY --from=build /workspace/target/*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
