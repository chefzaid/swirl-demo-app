# Dockerfile for Swirl Demo App Development Environment
# Java 25 + Spring Boot 4 + Angular 22 development

FROM mcr.microsoft.com/devcontainers/base:bookworm

# Set environment variables
ENV DEBIAN_FRONTEND=noninteractive
ENV NODE_VERSION=26
ENV MAVEN_VERSION=3.10.0
ENV MAVEN_HOME=/opt/maven
ENV JAVA_HOME=/usr/lib/jvm/java-25-amazon-corretto
ENV PATH=$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH

# Install Corretto 25, Maven, and additional system dependencies
RUN wget -qO- https://apt.corretto.aws/corretto.key \
        | gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" \
        > /etc/apt/sources.list.d/corretto.list \
    && apt-get update && apt-get install -y --no-install-recommends \
    java-25-amazon-corretto-jdk \
    # Basic tools
    wget \
    # Network tools
    net-tools \
    # Process tools
    htop \
    # Clean up
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* \
    # Install Maven
    && wget -q https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    && tar -xzf apache-maven-${MAVEN_VERSION}-bin.tar.gz -C /opt \
    && ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven \
    && rm apache-maven-${MAVEN_VERSION}-bin.tar.gz

# Install Node.js 26.x using NodeSource repository
RUN curl -fsSL https://deb.nodesource.com/setup_26.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Keep the package manager and CLI aligned with the application toolchain.
RUN npm install -g npm@12.2.2 @angular/cli@22.2.6

# Create workspace and Maven directories
RUN mkdir -p /workspace \
    && mkdir -p /home/vscode/.m2/repository

# Change ownership of workspace and Maven directories to vscode user
RUN chown -R vscode:vscode /workspace \
    && chown -R vscode:vscode /home/vscode/.m2

# Switch to vscode user
USER vscode

# Set working directory
WORKDIR /workspace

# Verify installations
RUN echo "=== Verifying installations ===" \
    && java -version \
    && mvn -version \
    && node -v \
    && npm -v \
    && ng --version \
    && echo "=== All tools verified successfully ==="
