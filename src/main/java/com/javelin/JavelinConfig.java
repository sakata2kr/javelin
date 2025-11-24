package com.javelin;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Objects;

import org.springframework.context.annotation.Bean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.resolver.DefaultAddressResolverGroup;
import reactor.netty.http.client.HttpClient;

import java.util.LinkedHashSet;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "javelin")
@Data
public class JavelinConfig
{
    private String GitHubToken;
    private Download download;
    private Vscode vscode;
    private AmazonCorretto amazonCorretto;
    private Urls apacheMaven;
    private Urls gradle;
    private Urls git;
    private Urls postman;
    private SpringToolSuite springToolSuite;

    @Data
    public static class Download
    {
        private boolean enable = true;  // 기본값은 true
        private boolean clear = true;  // 기본값은 true
        private String path = "download/";  // 기본 경로
        
        public void setPath(String path)
        {
            if (!path.endsWith("/") )
            {
                path += "/";
            }
            this.path = path;
        }
    }

    @Data
    public static class Vscode
    {
        private String url;
        private String prefix;
        private String filenamePattern;
        private String version;
        private extension extension;

    }

    @Data
    public static class AmazonCorretto
    {
        private java.util.List<Integer> versions;
        private String url;
    }

    @Data
    public static class Category
    {
        private String publisher;
        private String extensionName;
        private String giturl;
    }

    @Data
    public static class extension
    {
        private String root;
        private String version;
        private String vsix;
        private Map<String, LinkedHashSet<Category>> category;
    }

    @Data
    public static class Urls
    {
        private boolean enabled = true;  // 기본값은 true
        private String url;
        private String prefix;
        private String fixedVersion;
        private String suffix;
    }

    @Data
    public static class SpringToolSuite
    {
        private String stsVersion;
        private String eclipseVersion;
        private String url;
    }

    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
                .followRedirect(true) // 리다이렉션 자동 처리
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 60000) // 연결 타임아웃 60초
                .responseTimeout(Duration.ofMinutes(30)) // 응답 타임아웃 30분 (큰 파일용)
                .resolver(DefaultAddressResolverGroup.INSTANCE) // macOS DNS 문제 해결
                ;

        WebClient.Builder builder = WebClient.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(100 * 1024 * 1024)) // 100MB로 증가
                        .clientConnector(new ReactorClientHttpConnector(Objects.requireNonNull(httpClient)));
        
        // GitHub 토큰이 있을 때만 Authorization 헤더 추가
        if (GitHubToken != null && !GitHubToken.trim().isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + GitHubToken.trim());
        }
        
        return builder.build();
    }
}