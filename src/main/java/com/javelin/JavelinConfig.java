package com.javelin;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.LinkedHashSet;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "javelin")
@Data
public class JavelinConfig
{
    private String GitHubToken;
    private String root;
    private Vscodium vscodium;
    private EclipseTemurin eclipseTemurin;
    private Urls apacheMaven;
    private Urls gradle;
    private Urls git;
    private Urls postman;
    private boolean downloadComplete = false;

    public void setRoot(String root)
    {
        if (!root.endsWith("/") )
        {
            root += "/";
        }

        this.root = root;
    }

    @Data
    public static class Vscodium
    {
        private String url;
        private String prefix;
        private String filenamePattern;
        private String version;
        private Extension extensions;

    }

    @Data
    public static class EclipseTemurin
    {
        private String version;
        private String url;
        private String suffix;

        public void setUrl(String url)
        {
            if (!url.endsWith("/") )
            {
                url += "/";
            }
    
            this.url = url;
        }
    }

    @Data
    public static class Category
    {
        private String publisher;
        private String extensionName;
        private String giturl;
    }

    @Data
    public static class Extension
    {
        private String root;
        private String version;
        private String vsix;
        private Map<String, LinkedHashSet<Category>> category;
    }

    @Data
    public static class Urls
    {
        private String url;
        private String prefix;
        private String fixedVersion;
        private String suffix;
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                        .defaultHeader("Authorization", "Bearer " + GitHubToken.trim())
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)) // 50MB
                        .clientConnector(new ReactorClientHttpConnector(
                            HttpClient.create()
                                .followRedirect(true) // 리다이렉션 자동 처리
                                .responseTimeout(Duration.ofSeconds(30))
                        ))
                        .build();
    }
}
