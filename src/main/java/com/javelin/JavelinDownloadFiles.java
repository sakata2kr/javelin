package com.javelin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableScheduling
public class JavelinDownloadFiles
{
    private static final String ExtensionPath = "extensions/";
    private RestTemplate restTemplate = new RestTemplate();

    private final JavelinConfig JavelinConfig;

    @PostConstruct
    public void init() throws Exception
    {
        // 컨테이너가 시작될 때마다 실행할 작업을 여기에 작성합니다.
        DownloadJavelin();
        //JavelinConfig.setDownloadComplete(true);
    }

    @SuppressWarnings({ "unchecked", "null", "rawtypes" })
    public void DownloadJavelin() throws Exception
    {
        Map<String, Object> response = null;
        List<Map<String, Object>> responseList = null;
        String downloadUrl = null;
        String downloadUrlPrefix = null;
        String latestVersion = null;

        StopWatch stopWatch = new StopWatch("파일 다운로드 시작");

        JavelinConfig.setDownloadComplete(false);

        stopWatch.start("디렉토리 삭제 시작");
        // 상위 디렉토리 삭제
        Path dirPath = Paths.get(JavelinConfig.getRoot());

        if (Files.exists(dirPath))
        {
            Files.walk(dirPath).map(Path::toFile).sorted((o1, o2) -> -o1.compareTo(o2)).forEach(File::delete);
        }

        stopWatch.stop();

        stopWatch.start("VSCODE 다운로드");

        // VSCODE 다운로드
        response = restTemplate.getForObject(JavelinConfig.getVscode().getUrl(), Map.class);

        if ( response != null && response.containsKey("name") && response.containsKey("url") )
        {
            JavelinConfig.getVscode().setVersion(response.get("name").toString());
            downloadUrl = response.get("url").toString();

            downloadFile(downloadUrl, JavelinConfig.getRoot(), false);
        }

        stopWatch.stop();
        stopWatch.start("JDK 다운로드");

        // JDK 다운로드
        for ( String jdkVersion : JavelinConfig.getMicrosoftJdk() )
        {
            downloadUrl = "https://aka.ms/download-jdk/microsoft-jdk-" + jdkVersion + "-windows-x64.zip";
            downloadFile(downloadUrl, JavelinConfig.getRoot(), false);
        }

        stopWatch.stop();
        stopWatch.start("apache maven 다운로드");

        // apache maven 다운로드
        latestVersion = JavelinConfig.getApacheMaven().getFixedVersion();
        if ( latestVersion == null || latestVersion.isEmpty() )
        {
            responseList = restTemplate.getForObject(JavelinConfig.getApacheMaven().getUrl(), List.class);

            for (Map<String, Object> tempRes : responseList)
            {
                if ( tempRes != null && tempRes.containsKey("name")
                  && tempRes.get("name").toString().startsWith("maven-")
                  && !tempRes.get("name").toString().contains("alpha")
                  && !tempRes.get("name").toString().contains("beta")
                )
                {
                    latestVersion = tempRes.get("name").toString().replace("maven-", "");
                    break;
                }
            }
        }

        downloadUrl = new StringBuilder().append(JavelinConfig.getApacheMaven().getPrefix())
                                        .append(latestVersion.substring(0, 1)).append("/")
                                        .append(latestVersion).append("/binaries/apache-maven-")
                                        .append(latestVersion).append("-bin.tar.gz")
                                        .toString();

        downloadFile(downloadUrl, JavelinConfig.getRoot(), false);

        stopWatch.stop();
        stopWatch.start("Gradle 다운로드");

        // Gradle 다운로드
        latestVersion = JavelinConfig.getGradle().getFixedVersion();

        if ( latestVersion == null || latestVersion.isEmpty() )
        {
            response = restTemplate.getForObject(JavelinConfig.getGradle().getUrl(), Map.class);
            if ( response != null && response.containsKey("version") )
            {
                latestVersion = response.get("version").toString();
            }
        }

        downloadUrl = new StringBuilder().append(JavelinConfig.getGradle().getPrefix())
                      .append(latestVersion).append("-bin.zip")
                      .toString();

        downloadFile(downloadUrl, JavelinConfig.getRoot(), false);

        stopWatch.stop();
        stopWatch.start("Git 다운로드");

        // Git 다운로드
        latestVersion = JavelinConfig.getGit().getFixedVersion();

        if ( latestVersion == null || latestVersion.isEmpty() )
        {
            responseList = restTemplate.getForObject(JavelinConfig.getGit().getUrl(), List.class);
            for (Map<String, Object> tempRes : responseList)
            {
                if ( tempRes != null && tempRes.containsKey("name") && !tempRes.get("name").toString().contains("-rc") )
                {
                    latestVersion = tempRes.get("name").toString();
                    break;
                }
            }
        }
        Map<String, Object> subResponse = restTemplate.getForObject(JavelinConfig.getGit().getPrefix() + latestVersion, Map.class);

        if ( subResponse != null && subResponse.containsKey("assets") )
        {
            responseList = (List) subResponse.get("assets");
        }

        if ( !responseList.isEmpty() )
        {
            for ( Map<String, Object> assets : responseList )
            {
                if ( assets != null && assets.containsKey("browser_download_url")
                  && assets.get("browser_download_url").toString().contains("64-bit.exe")
                   )
                {
                    downloadUrl = assets.get("browser_download_url").toString();
                    downloadFile(downloadUrl, JavelinConfig.getRoot(), false);
                    break;
                }
            }
        }

        stopWatch.stop();

        // Postman 다운로드
        stopWatch.start("Postman 다운로드");
        downloadUrl = JavelinConfig.getPostman().getPrefix()
                    + JavelinConfig.getPostman().getFixedVersion()
                    + JavelinConfig.getPostman().getSuffix();
        downloadFile( downloadUrl, JavelinConfig.getRoot(), false);
        stopWatch.stop();

        stopWatch.start("Extensions 다운로드");

        // Extension Path를 확인 (/download/extensions)
        Files.createDirectories(Paths.get(JavelinConfig.getRoot() + ExtensionPath));

        // 각 Extension 유형별 Path를 확인 (/download/extensions/{})
        for (Map.Entry<String, LinkedHashSet<JavelinConfig.Category>> entry : JavelinConfig.getVscode().getExtensions().getCategory().entrySet())
        {
            downloadUrlPrefix = new StringBuilder().append(JavelinConfig.getRoot())
                                                .append(ExtensionPath)
                                                .append("/")
                                                .append(entry.getKey())
                                                .append("/")
                                                .toString();

            Files.createDirectories(Paths.get(downloadUrlPrefix));

            // 유형별로 파일을 다운로드
            for (JavelinConfig.Category category : entry.getValue())
            {
                String publisher = category.getPublisher();
                String extensionName = category.getExtensionName();
                latestVersion = null;

                if ( category.getGiturl() != null && !category.getGiturl().isEmpty() )
                {
                    latestVersion = getGitVersion(category.getGiturl(), JavelinConfig.getVscode().getVersion());
                }

                if ( latestVersion == null || latestVersion.isEmpty() )
                {
                    latestVersion = getLatestVersion(publisher, extensionName);
                }

                downloadLatestVersion(publisher, extensionName, latestVersion, downloadUrlPrefix);
            }
        }

        stopWatch.stop();
        log.info("{}", stopWatch.prettyPrint());
        JavelinConfig.setDownloadComplete(true);
        log.info("FINISH DOWNLOAD !!");
    }

    @SuppressWarnings({ "unchecked", "null" })
    private String getGitVersion(String gitUrl, String vsCodeVersion) throws Exception
    {
        List<String> diffVersionSource = Arrays.asList(vsCodeVersion.split("\\."));
        List<String> diffVersionTarget = null;

        List<Map<String, Object>> responseList = restTemplate.getForObject(gitUrl, List.class);

        if ( responseList != null )
        {
            for ( Map<String, Object> response : responseList)
            {
                if ( response.containsKey("name") )
                {
                    diffVersionTarget = Arrays.asList(response.get("name").toString().toLowerCase().replace("v", "").split("\\."));

                    if ( diffVersionSource.get(0).compareTo(diffVersionTarget.get(0)) >= 0
                      && diffVersionSource.get(1).compareTo(diffVersionTarget.get(1)) >= 0 )
                    {
                        return response.get("name").toString().replace("v", "");
                    }
                }
            }
        }

        return null;
    }
    private String getLatestVersion(String publisher, String extensionName) throws IOException
    {
        String url = String.format(JavelinConfig.getVscode().getExtensions().getVersion());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"filters\":[{\"criteria\":[{\"filterType\":7,\"value\":\"%s.%s\"}]}],\"flags\":870}", publisher, extensionName);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        @SuppressWarnings("null")
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(response.getBody());
        return root.path("results").get(0).path("extensions").get(0).path("versions").get(0).path("version").asText();
    }

    private void downloadLatestVersion(String publisher, String extensionName, String latestVersion, String baseUrl) throws Exception {
        String downloadUrl = String.format(JavelinConfig.getVscode().getExtensions().getVsix(), publisher, publisher, extensionName, latestVersion);
        String targetPath = baseUrl + publisher + "." + extensionName + "." + latestVersion + ".vsix";
        downloadFile(downloadUrl, targetPath, true);
    }

    @SuppressWarnings("null")
    private void downloadFile(String url, String targetPath, Boolean isExtensions) throws IOException
    {
        log.info("DOWNLOAD URL : {}", url);
        RestTemplate restTemplate = new RestTemplate();

        // Prepare the request callback
        RequestCallback requestCallback = restTemplate.httpEntityCallback(null);

        // Prepare the response extractor
        ResponseExtractor<Void> responseExtractor = new FileResponseExtractor(targetPath, url, isExtensions);

        // Execute the request
        restTemplate.execute(url, HttpMethod.GET, requestCallback, responseExtractor);
    }

    private static class FileResponseExtractor implements ResponseExtractor<Void>
    {
        private final String targetPathString;
        private final String requestUrl;
        private final Boolean isExtension;

        public FileResponseExtractor(String targetPathString, String requestUrl, Boolean isExtension)
        {
            this.targetPathString = targetPathString;
            this.requestUrl = requestUrl;
            this.isExtension = isExtension;
        }

        @SuppressWarnings("null")
        @Override
        public Void extractData(ClientHttpResponse response) throws IOException
        {
            // Get the Content-Disposition header from the response
            String contentDisposition = response.getHeaders().getFirst("Content-Disposition");
            String contentType = response.getHeaders().getFirst("Content-Type");

            String filename;
            if (contentDisposition != null && !contentDisposition.isEmpty()) {
                int startIndex = contentDisposition.indexOf("filename=") + "filename=".length();
                int endIndex = contentDisposition.indexOf(";", startIndex);

                if ( endIndex < 0) endIndex = contentDisposition.length();

                // Extract the filename from the Content-Disposition header
                filename = contentDisposition.substring(startIndex, endIndex);
            }
            else if ("application/x-gzip".equals(contentType))
            {
                // If Content-Type is application/x-gzip, use the last part of the request URL as the filename
                filename = requestUrl.substring(requestUrl.lastIndexOf("/") + 1);
            }
            else
            {
                throw new IOException("Cannot determine filename from the response");
            }

            // Create the target path
            Path targetPath = null;
            if ( isExtension )
            {
                // Extension의 filename은 targetPath는 마지막 / 뒤 문자열로 변환
                filename = targetPathString.substring(targetPathString.lastIndexOf("/") + 1);

                // Extension의 targetPath는 기존 targetPath에서 파일명을 제외한 문자열로 변환
                targetPath = Paths.get(targetPathString.substring(0, targetPathString.lastIndexOf("/")), filename);
            }
            else
            {
                targetPath = Paths.get(targetPathString, filename);
            }

            // Ensure the target directory exists
            Files.createDirectories(targetPath.getParent());

            // Copy the file to the target path
            try (FileOutputStream out = new FileOutputStream(targetPath.toFile()))
            {
                StreamUtils.copy(response.getBody(), out);
            }
            return null;
        }
    }
}
