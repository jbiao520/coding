package com.example.codeobserver.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "learning-flow.ai")
public record LearningFlowAiProperties(
        String mode,
        String url,
        String apiKeyFile,
        String model,
        String contextModel,
        String topicPlanModel,
        boolean requireApiKey,
        Integer maxTokens,
        Double temperature,
        Duration timeout,
        boolean thinkingEnabled
) {
    public LearningFlowAiProperties {
        mode = defaultIfBlank(mode, "deepseek");
        url = defaultIfBlank(url, "https://api.deepseek.com");
        apiKeyFile = defaultIfBlank(apiKeyFile, "~/.dstoken.txt");
        model = defaultIfBlank(model, "deepseek-v4-flash");
        contextModel = defaultIfBlank(contextModel, model);
        topicPlanModel = defaultIfBlank(topicPlanModel, model);
        maxTokens = maxTokens == null ? 4096 : maxTokens;
        temperature = temperature == null ? 0.2 : temperature;
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
