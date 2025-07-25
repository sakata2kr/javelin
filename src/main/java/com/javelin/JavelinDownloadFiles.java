package com.javelin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils; // DataBufferUtils 임포트 확인
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableScheduling
public class JavelinDownloadFiles
{
    private final WebClient webClient;

    private final JavelinConfig javelinConfig;

    @PostConstruct
    public void init() throws Exception
    {
        javelinConfig.setDownloadComplete(false);

        // 디렉토리 삭제
        deleteDirectory();

        // 모든 다운로드 작업을 비동기로 실행
        CompletableFuture<Void> allDownloads = CompletableFuture.allOf
        ( downloadVscodium().toFuture()
        , downloadEclipseTemurinJDK().toFuture()
        , downloadApacheMaven().toFuture()
        , downloadGradle().toFuture()
        , downloadGit().toFuture()
        , downloadPostman().toFuture()
        , downloadExtensions().toFuture()
        );

        // 모든 작업 완료 대기
        allDownloads.get();

        javelinConfig.setDownloadComplete(true);
        log.info("FINISH DOWNLOAD !!");
    }

    private void deleteDirectory() throws IOException
    {
        Path dirPath = Paths.get(javelinConfig.getRoot());
        if (Files.exists(dirPath))
        {
            Files.walk(dirPath)
                .map(Path::toFile)
                .sorted((o1, o2) -> -o1.compareTo(o2))
                .forEach(File::delete);
        }
    }

    private Mono<Void> downloadVscodium()
    {
        return Mono.<String>fromCallable((() -> {
            log.info("VSCODIUM 다운로드");
            return "started";
        }))
        .subscribeOn(Schedulers.boundedElastic())
        .then(webClient.get()
            .uri(javelinConfig.getVscodium().getUrl())
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {}))
        .flatMap((List<Map<String, Object>> responseList) -> {
            if (responseList != null && !responseList.isEmpty()) 
            {
                String version = javelinConfig.getVscodium().getVersion();
                
                if (version == null || version.isEmpty())
                {
                    for (Map<String, Object> tag : responseList)
                    {
                        if (tag.containsKey("name"))
                        {
                            String tagName = tag.get("name").toString();
                            if (!tagName.contains("-") && !tagName.contains("beta") && !tagName.contains("alpha"))
                            {
                                version = tagName;
                                break;
                            }
                        }
                    }
                }
                
                if (version != null && !version.isEmpty())
                {
                    javelinConfig.getVscodium().setVersion(version);
                    
                    String downloadUrl = new StringBuilder()
                        .append(javelinConfig.getVscodium().getPrefix())
                        .append(version)
                        .append("/")
                        .append(String.format(javelinConfig.getVscodium().getFilenamePattern(), version))
                        .toString();

                    return downloadFile(downloadUrl, javelinConfig.getRoot(), false);
                }
            }
            return Mono.empty();
        })
        .then();
    }

    private Mono<Void> downloadEclipseTemurinJDK()
    {
        return Mono.<String>fromCallable(() -> {
            log.info("Eclipse Temurin JDK 다운로드");
            return "started";
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then(Mono.<String>fromCallable(() -> {
            return new StringBuilder()
                .append(javelinConfig.getEclipseTemurin().getUrl())
                .append(javelinConfig.getEclipseTemurin().getVersion())
                .append("/")
                .append(javelinConfig.getEclipseTemurin().getSuffix())
                .toString();
        }))
        .flatMap((String apiUrl) -> 
            webClient.get()
                .uri(apiUrl)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {}))
        .flatMap((List<Map<String, Object>> responseList) -> {
            if (responseList != null && !responseList.isEmpty())
            {
                Map<String, Object> jdkAsset = responseList.get(0);
                if (jdkAsset.containsKey("binary"))
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> binary = (Map<String, Object>) jdkAsset.get("binary");
                    if (binary.containsKey("installer"))
                    {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> packageInfo = (Map<String, Object>) binary.get("installer");
                        if (packageInfo.containsKey("link"))
                        {
                            String downloadUrl = packageInfo.get("link").toString();
                            return downloadFile(downloadUrl, javelinConfig.getRoot(), false);
                        }
                    }
                }
            }
            return Mono.empty();
        })
        .then();
    }

    private Mono<Void> downloadApacheMaven()
    {
        return Mono.<String>fromCallable(() -> {
            log.info("Apache Maven 다운로드");
            return "started";
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then(Mono.<String>fromCallable(() -> javelinConfig.getApacheMaven().getFixedVersion()))
        .flatMap((String fixedVersion) -> {
            if (fixedVersion != null && !fixedVersion.isEmpty())
            {
                return Mono.just(fixedVersion);
            }
            else
            {
                return webClient.get()
                    .uri(javelinConfig.getApacheMaven().getUrl())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                    .map((List<Map<String, Object>> responseList) -> {
                        for (Map<String, Object> tempRes : responseList)
                        {
                            if (tempRes != null && tempRes.containsKey("name")
                              && tempRes.get("name").toString().startsWith("maven-")
                              && !tempRes.get("name").toString().contains("alpha")
                              && !tempRes.get("name").toString().contains("beta")
                              && !tempRes.get("name").toString().contains("rc"))
                            {
                                return tempRes.get("name").toString().replace("maven-", "");
                            }
                        }
                        return "";
                    });
            }
        })
        .flatMap((String latestVersion) -> {
            String downloadUrl = new StringBuilder()
                .append(javelinConfig.getApacheMaven().getPrefix())
                .append(latestVersion.substring(0, 1)).append("/")
                .append(latestVersion).append("/binaries/apache-maven-")
                .append(latestVersion).append("-bin.tar.gz")
                .toString();
            
            return downloadFile(downloadUrl, javelinConfig.getRoot(), false);
        })
        .then();
    }

    private Mono<Void> downloadGradle()
    {
        return Mono.<String>fromCallable(() -> {
            log.info("Gradle 다운로드");
            return "started";
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then(Mono.<String>fromCallable(() -> javelinConfig.getGradle().getFixedVersion()))
        .flatMap((String fixedVersion) -> {
            if (fixedVersion != null && !fixedVersion.isEmpty())
            {
                return Mono.just(fixedVersion);
            }
            else
            {
                return webClient.get()
                    .uri(javelinConfig.getGradle().getUrl())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .map((Map<String, Object> response) -> {
                        if (response != null && response.containsKey("version"))
                        {
                            return response.get("version").toString();
                        }
                        return "";
                    });
            }
        })
        .flatMap((String latestVersion) -> {
            String downloadUrl = new StringBuilder()
                .append(javelinConfig.getGradle().getPrefix())
                .append(latestVersion).append("-bin.zip")
                .toString();
            
            return downloadFile(downloadUrl, javelinConfig.getRoot(), false);
        })
        .then();
    }

    private Mono<Void> downloadGit()
    {
        return Mono.<String>fromCallable(() -> {
            log.info("Git 다운로드");
            return "started";
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then(Mono.<String>fromCallable(() -> javelinConfig.getGit().getFixedVersion()))
        .flatMap((String fixedVersion) -> {
            if (fixedVersion != null && !fixedVersion.isEmpty())
            {
                return Mono.just(fixedVersion);
            }
            else
            {
                return webClient.get()
                    .uri(javelinConfig.getGit().getUrl())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                    .map((List<Map<String, Object>> responseList) -> {
                        for (Map<String, Object> tempRes : responseList)
                        {
                            if (tempRes != null && tempRes.containsKey("name") && !tempRes.get("name").toString().contains("-rc"))
                            {
                                return tempRes.get("name").toString();
                            }
                        }
                        return "";
                    });
            }
        })
        .flatMap((String latestVersion) -> 
            webClient.get()
                .uri(javelinConfig.getGit().getPrefix() + latestVersion)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {}))
        .flatMap((Map<String, Object> subResponse) -> {
            if (subResponse != null && subResponse.containsKey("assets"))
            {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> responseList = (List<Map<String, Object>>) subResponse.get("assets");
                
                for (Map<String, Object> assets : responseList)
                {
                    if (assets != null && assets.containsKey("browser_download_url")
                      && assets.get("browser_download_url").toString().contains("64-bit.exe"))
                    {
                        String downloadUrl = assets.get("browser_download_url").toString();
                        return downloadFile(downloadUrl, javelinConfig.getRoot(), false);
                    }
                }
            }
            return Mono.empty();
        })
        .then();
    }

    private Mono<Void> downloadPostman()
    {
        return Mono.<String>fromCallable(() -> {
            log.info("Postman 다운로드");
            return "started";
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then(Mono.<String>fromCallable(() -> {
            return javelinConfig.getPostman().getPrefix()
                + javelinConfig.getPostman().getFixedVersion()
                + javelinConfig.getPostman().getSuffix();
        }))
        .flatMap((String downloadUrl) -> downloadFile(downloadUrl, javelinConfig.getRoot(), false))
        .then();
    }

    private Mono<Void> downloadExtensions()
    {
        return Mono.<String>fromCallable(() -> {
            log.info("Extensions 다운로드");
            return "started";
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then(Mono.<String>fromCallable(() -> {
            String extensionPath = javelinConfig.getVscodium().getExtensions().getClass().getSimpleName();
            try {
                Files.createDirectories(Paths.get(javelinConfig.getRoot() + extensionPath));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return extensionPath;
        }))
        .flatMap((String extensionPath) -> {
            return Flux.fromIterable(javelinConfig.getVscodium().getExtensions().getCategory().entrySet())
                .flatMap((Map.Entry<String, LinkedHashSet<JavelinConfig.Category>> entry) -> {
                    String downloadUrlPrefix = new StringBuilder()
                        .append(javelinConfig.getRoot())
                        .append(extensionPath)
                        .append("/")
                        .append(entry.getKey())
                        .append("/")
                        .toString();

                    return Mono.<String>fromCallable(() -> {
                        try {
                            Files.createDirectories(Paths.get(downloadUrlPrefix));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return downloadUrlPrefix;
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap((String prefix) -> 
                        Flux.fromIterable(entry.getValue())
                            .flatMap((JavelinConfig.Category category) -> 
                                getLatestVersion(category.getPublisher(), category.getExtensionName(), prefix))
                            .then(Mono.just(prefix))
                    );
                })
                .then();
        })
        .then();
    }

    private Mono<Void> getLatestVersion(String publisher, String extensionName, String baseUrl)
    {
        String url = "https://open-vsx.org/api/" + publisher + "/" + extensionName;
        
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(String.class)
            .flatMap((String responseBody) -> {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(responseBody);
                    
                    String latestVersion = root.path("version").asText();
                    String downloadUrl = String.format("https://open-vsx.org/api/%s/%s/%s/file/%s.%s-%s.vsix", 
                        publisher, extensionName, latestVersion, publisher, extensionName, latestVersion);
                    String targetPath = baseUrl + publisher + "." + extensionName + "." + latestVersion + ".vsix";
                    
                    return downloadFile(downloadUrl, targetPath, true);
                } catch (Exception e) {
                    return Mono.error(e);
                }
            })
            .onErrorResume((Throwable throwable) -> {
                // VSCode Marketplace API 시도
                String marketplaceUrl = "https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery?api-version=6.1-preview.1";
                String body = String.format("{\"filters\":[{\"criteria\":[{\"filterType\":7,\"value\":\"%s.%s\"}]}],\"flags\":870}", 
                    publisher, extensionName);
                
                return webClient.post()
                    .uri(marketplaceUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .flatMap((String responseBody) -> {
                        try {
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode root = mapper.readTree(responseBody);
                            
                            String latestVersion = root.path("results").get(0).path("extensions").get(0)
                                .path("versions").get(0).path("version").asText();
                            String downloadUrl = String.format("https://%s.gallery.vsassets.io/_apis/public/gallery/publisher/%s/extension/%s/%s/assetbyname/Microsoft.VisualStudio.Services.VSIXPackage", 
                                publisher, publisher, extensionName, latestVersion);
                            String targetPath = baseUrl + publisher + "." + extensionName + "." + latestVersion + ".vsix";
                            
                            return downloadFile(downloadUrl, targetPath, true);
                        } catch (Exception e) {
                            return Mono.error(e);
                        }
                    });
            });
    }

    private Mono<Void> downloadFile(String url, String targetPath, Boolean isExtensions)
    {
        String decodeUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);
        
        log.info("DOWNLOAD URL : {}", decodeUrl);
        log.info("Determining final file path for: {}", targetPath); 
        
        return webClient.get()
            .uri(decodeUrl)
            .retrieve()
            .toEntity(Void.class)
            .flatMap(responseEntity -> {
                // 파일명 결정 로직 (기존과 동일)
                String filename = null;
                HttpHeaders headers = responseEntity.getHeaders();
                String contentDisposition = headers.getFirst("Content-Disposition");
    
                if (isExtensions) {
                    filename = Paths.get(targetPath).getFileName().toString();
                } else if (contentDisposition != null && !contentDisposition.isEmpty()) {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("filename\\*?=['\"]?(?:UTF-8''|utf-8'')?([^;\"\\n]+)['\"]?").matcher(contentDisposition);
                    if (matcher.find()) {
                        filename = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
                    }
                } 
                
                if (filename == null || filename.isEmpty()) {
                    int lastSlashIndex = decodeUrl.lastIndexOf("/");
                    if (lastSlashIndex >= 0 && lastSlashIndex < decodeUrl.length() - 1) {
                        filename = decodeUrl.substring(lastSlashIndex + 1);
                        int questionMarkIndex = filename.indexOf("?");
                        if (questionMarkIndex > -1) {
                            filename = filename.substring(0, questionMarkIndex);
                        }
                        int hashIndex = filename.indexOf("#");
                        if (hashIndex > -1) {
                            filename = filename.substring(0, hashIndex);
                        }
                    }
                }
    
                if (filename == null || filename.isEmpty()) {
                    return Mono.error(new IOException("Cannot determine filename from the response or URL for: " + decodeUrl));
                }
    
                Path finalTargetPath;
                if (isExtensions) {
                    finalTargetPath = Paths.get(targetPath);
                } else {
                    finalTargetPath = Paths.get(targetPath, filename);
                }
    
                log.info("Final file will be written to: {}", finalTargetPath.toAbsolutePath());
    
                try {
                    Files.createDirectories(finalTargetPath.getParent());
                } catch (IOException e) {
                    return Mono.error(new RuntimeException("Failed to create directories for " + finalTargetPath.getParent(), e));
                }
                
                // 디버깅을 위한 상세 로깅 추가
                return webClient.get()
                    .uri(decodeUrl)
                    .retrieve()
                    .bodyToFlux(DataBuffer.class)
                    .doOnNext(dataBuffer -> {
                        log.debug("Received DataBuffer of size: {} bytes", dataBuffer.readableByteCount());
                    })
                    .doOnComplete(() -> {
                        log.info("DataBuffer flux completed for URL: {}", decodeUrl);
                    })
                    .doOnError(e -> {
                        log.error("Error in DataBuffer flux for URL: {}", decodeUrl, e);
                    })
                    .as(dataBufferFlux -> {
                        log.info("Starting file write to: {}", finalTargetPath);
                        return DataBufferUtils.write(dataBufferFlux, finalTargetPath)
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnSuccess(v -> {
                                log.info("DataBufferUtils.write completed for: {}", finalTargetPath);
                            })
                            .doOnError(e -> {
                                log.error("Error in DataBufferUtils.write for: {}", finalTargetPath, e);
                            });
                    })
                    .then(Mono.fromCallable(() -> {
                        // 파일 쓰기 완료 후 크기 확인
                        try {
                            long fileSize = Files.size(finalTargetPath);
                            log.info("Final file size check - {} : {} bytes", finalTargetPath, fileSize);
                            
                            if (fileSize == 0) {
                                log.warn("File is empty! Checking if file exists and is readable: exists={}, readable={}", 
                                    Files.exists(finalTargetPath), Files.isReadable(finalTargetPath));
                            }
                            
                            return fileSize;
                        } catch (IOException e) {
                            log.error("Error checking file size for {}: {}", finalTargetPath, e.getMessage(), e);
                            return 0L;
                        }
                    }).subscribeOn(Schedulers.boundedElastic()))
                    .then()
                    .doOnError(e -> log.error("Overall error during file download for URL: {} and targetPath: {}", decodeUrl, finalTargetPath, e));
            })
            .onErrorResume(WebClientResponseException.class, e -> {
                log.error("WebClient HTTP error during download from {}. Status: {}, Body: {}", decodeUrl, e.getStatusCode(), e.getResponseBodyAsString(), e);
                return Mono.error(new RuntimeException("Failed to download file from " + decodeUrl + " due to HTTP error: " + e.getStatusCode(), e));
            })
            .onErrorResume(Exception.class, e -> {
                log.error("An unexpected error occurred during download from {}: {}", decodeUrl, e.getMessage(), e);
                return Mono.error(new RuntimeException("An unexpected error occurred during download from " + decodeUrl, e));
            });
    }
}