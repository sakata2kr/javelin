package com.javelin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private RestTemplate restTemplate = new RestTemplate();

    private final JavelinConfig JavelinConfig;
 
    @PostConstruct
    @SuppressWarnings({ "unchecked", "null", "rawtypes" })
    public void init() throws Exception
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

        stopWatch.start("VSCODIUM 다운로드");

        // VSCODIUM 다운로드
        responseList = restTemplate.getForObject(JavelinConfig.getVscodium().getUrl(), List.class);

        if (responseList != null && !responseList.isEmpty()) 
        {
            String version = JavelinConfig.getVscodium().getVersion();
            
            // 안정 버전 찾기 (pre-release 제외)
            if (version == null || version.isEmpty())
            {
                for (Map<String, Object> tag : responseList)
                {
                    if (tag.containsKey("name"))
                    {
                        String tagName = tag.get("name").toString();
                        // pre-release나 beta 버전 제외
                        if (!tagName.contains("-") && !tagName.contains("beta") && !tagName.contains("alpha"))
                        {
                            version = tagName;
                            break;
                        }
                    }
                }
            }
            
            if ( version != null && !version.isEmpty() )
            {
                JavelinConfig.getVscodium().setVersion(version);
                
                // prefix와 filename-pattern을 사용하여 다운로드 URL 구성
                downloadUrl = new StringBuilder()
                    .append(JavelinConfig.getVscodium().getPrefix())
                    .append(version)
                    .append("/")
                    .append(String.format(JavelinConfig.getVscodium().getFilenamePattern(), version))
                    .toString();

                downloadFile(downloadUrl, JavelinConfig.getRoot(), false);
            }
        }
        stopWatch.stop();
        stopWatch.start("Eclipse Temurin JDK 다운로드");

        // Eclipse Temurin JDK 다운로드
        String apiUrl = new StringBuilder()
            .append(JavelinConfig.getEclipseTemurin().getUrl())
            .append(JavelinConfig.getEclipseTemurin().getVersion())
            .append("/")
            .append(JavelinConfig.getEclipseTemurin().getSuffix())
            .toString();


        responseList = restTemplate.getForObject(apiUrl, List.class);

        if (responseList != null && !responseList.isEmpty())
        {
            Map<String, Object> jdkAsset = responseList.get(0);

            if (jdkAsset.containsKey("binary"))
            {
                Map<String, Object> binary = (Map<String, Object>) jdkAsset.get("binary");
                if (binary.containsKey("installer"))
                {
                    Map<String, Object> packageInfo = (Map<String, Object>) binary.get("installer");
                    if (packageInfo.containsKey("link"))
                    {
                        downloadUrl = packageInfo.get("link").toString();
                        downloadFile(downloadUrl, JavelinConfig.getRoot(), false);
                    }
                }
            }
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
                  && !tempRes.get("name").toString().contains("rc")
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

        String ExtensionPath = JavelinConfig.getVscodium().getExtensions().getClass().getSimpleName();

        // Extension Path를 확인 (/download/extensions)
        Files.createDirectories(Paths.get(JavelinConfig.getRoot() + ExtensionPath));

        // 각 Extension 유형별 Path를 확인 (/download/extensions/{})
        for (Map.Entry<String, LinkedHashSet<JavelinConfig.Category>> entry : JavelinConfig.getVscodium().getExtensions().getCategory().entrySet())
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
                getLatestVersion( category.getPublisher(), category.getExtensionName(), downloadUrlPrefix);
            }
        }

        stopWatch.stop();
        log.info("{}", stopWatch.prettyPrint());
        JavelinConfig.setDownloadComplete(true);
        log.info("FINISH DOWNLOAD !!");
    }

    private void getLatestVersion(String publisher, String extensionName, String baseUrl) throws IOException
    {
        // Open VSX Registry API 사용
        String url = "https://open-vsx.org/api/" + publisher + "/" + extensionName;
        String latestVersion = null;
        String downloadUrl = null;
        String targetPath = null;

        try
        {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.getBody());

            latestVersion = root.path("version").asText();
            downloadUrl = String.format("https://open-vsx.org/api/%s/%s/%s/file/%s.%s-%s.vsix", publisher, extensionName, latestVersion, publisher, extensionName, latestVersion);
            targetPath = baseUrl + publisher + "." + extensionName + "." + latestVersion + ".vsix";

            downloadFile(downloadUrl, targetPath, true);
        }
        catch (Exception e)
        {
            url = "https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery?api-version=6.1-preview.1";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String body = String.format("{\"filters\":[{\"criteria\":[{\"filterType\":7,\"value\":\"%s.%s\"}]}],\"flags\":870}", publisher, extensionName);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
    
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
    
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.getBody());

            latestVersion = root.path("results").get(0).path("extensions").get(0).path("versions").get(0).path("version").asText();
            downloadUrl = String.format("https://%s.gallery.vsassets.io/_apis/public/gallery/publisher/%s/extension/%s/%s/assetbyname/Microsoft.VisualStudio.Services.VSIXPackage", publisher, publisher, extensionName, latestVersion);
            targetPath = baseUrl + publisher + "." + extensionName + "." + latestVersion + ".vsix";

            downloadFile(downloadUrl, targetPath, true);
        }
    }

    private void downloadFile(String url, String targetPath, Boolean isExtensions) throws IOException
    {
        String decodeUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);

        log.info("DOWNLOAD URL : {}", decodeUrl);
        log.info("targetPath : {}", targetPath );
        //if (true) return;
        RestTemplate restTemplate = new RestTemplate();

        // Prepare the request callback
        RequestCallback requestCallback = restTemplate.httpEntityCallback(null);

        // Prepare the response extractor
        ResponseExtractor<Void> responseExtractor = new FileResponseExtractor(targetPath, decodeUrl, isExtensions);

        // Execute the request
        restTemplate.execute(decodeUrl, HttpMethod.GET, requestCallback, responseExtractor);
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
