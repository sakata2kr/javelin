package com.javelin;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.channel.ChannelOption;

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
    private AmazonCorretto amazonCorretto;
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
    public static class AmazonCorretto
    {
        private String version;
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
        private boolean enabled = true;  // 기본값은 true
        private String url;
        private String prefix;
        private String fixedVersion;
        private String suffix;
    }

    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
                .followRedirect(true) // 리다이렉션 자동 처리
                .responseTimeout(Duration.ofMinutes(10)) // 큰 파일 다운로드를 위해 10분으로 증가
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000) // 연결 타임아웃 30초
                .doOnConnected(conn -> 
                    conn.addHandlerLast(new ReadTimeoutHandler(300)) // 읽기 타임아웃 5분
                        .addHandlerLast(new WriteTimeoutHandler(60)) // 쓰기 타임아웃 1분
                );

        WebClient.Builder builder = WebClient.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(100 * 1024 * 1024)) // 100MB로 증가
                        .clientConnector(new ReactorClientHttpConnector(httpClient));
        
        // GitHub 토큰이 있을 때만 Authorization 헤더 추가
        if (GitHubToken != null && !GitHubToken.trim().isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + GitHubToken.trim());
        }
        
        return builder.build();
    }
}
