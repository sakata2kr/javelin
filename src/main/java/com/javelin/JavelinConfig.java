package com.javelin;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "javelin")
@Data
public class JavelinConfig
{
    private String root;
    private Vscode vscode;
    private LinkedHashSet<String> microsoftJdk;
    private Urls apacheMaven;
    private Urls gradle;
    private Urls git;
    private Urls postman;
    private boolean downloadComplete = false;

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
    public static class Vscode
    {
        private String url;
        private String version;
        private Extension extensions;

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
    public static class Urls
    {
        private String url;
        private String prefix;
        private String fixedVersion;
        private String suffix;
    }

    public void setRoot(String root)
    {
        if (!root.endsWith("/") )
        {
            root += "/";
        }

        this.root = root;
    }
}
