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
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Component
@RequiredArgsConstructor
public class JavelinDownloadFiles {

    private final WebClient webClient;
    private final JavelinConfig javelinConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();  // ObjectMapper 재사용

    // 공통으로 사용하는 함수형 인터페이스 정의
    @FunctionalInterface
    public interface DownloadUrlExtractor<T> {
        String extractDownloadUrl(T response) throws Exception;
    }

    @PostConstruct
    public void init() {
        javelinConfig.setDownloadComplete(false);

        // 디렉토리 삭제
        deleteDirectory();

        // 다운로드 작업 비동기로 실행, 블로킹 하지 않고 구독 시작
        Flux<Mono<Void>> downloads = Flux.just(
            downloadVscodium(),
            downloadEclipseTemurinJDK(),
            downloadApacheMaven(),
            downloadGradle(),
            downloadGit(),
            downloadPostman(),
            downloadExtensions()
        );

        downloads.flatMap(mono -> mono.onErrorContinue((e, obj) -> {
                    log.error("Download error on task: {}", obj, e);
                }))
                .then()
                .doOnSuccess(v -> {
                    javelinConfig.setDownloadComplete(true);
                    log.info("FINISH DOWNLOAD !!");
                })
                .subscribe();
    }

    private void deleteDirectory()
    {
        Path dirPath = Paths.get(javelinConfig.getRoot());
        try
        {
            if (Files.exists(dirPath))
            {
                Files.walk(dirPath)
                    .map(Path::toFile)
                    .sorted((o1, o2) -> -o1.compareTo(o2))
                    .forEach(File::delete);
            }
        }
        catch (Exception e)
        {
            log.error("디렉토리 삭제 실패");
        }
    }

    // 공통 API 호출 -> 다운로드 처리
    private <T> Mono<Void> downloadResource(String apiUrl, ParameterizedTypeReference<T> responseType,
                                           DownloadUrlExtractor<T> urlExtractor) {
        return webClient.get()
                .uri(apiUrl)
                .retrieve()
                .bodyToMono(responseType)
                .flatMap(response -> {
                    try {
                        String downloadUrl = urlExtractor.extractDownloadUrl(response);
                        if (downloadUrl == null || downloadUrl.isEmpty()) {
                            log.warn("No download URL extracted from API response: {}", apiUrl);
                            return Mono.empty();
                        }
                        return downloadFile(downloadUrl, javelinConfig.getRoot(), false);
                    } catch (Exception e) {
                        log.error("Error extracting download URL from API response", e);
                        return Mono.error(e);
                    }
                });
    }

    // Vscodium 다운로드
    private Mono<Void> downloadVscodium() {
        log.info("VSCODIUM 다운로드");
        String apiUrl = javelinConfig.getVscodium().getUrl();

        return downloadResource(apiUrl,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {},
                responseList -> {
                    if (responseList != null && !responseList.isEmpty()) {
                        String version = javelinConfig.getVscodium().getVersion();
                        if (version == null || version.isEmpty()) {
                            for (Map<String, Object> tag : responseList) {
                                String tagName = (String) tag.get("name");
                                if (tagName != null && !tagName.contains("-") &&
                                        !tagName.contains("beta") &&
                                        !tagName.contains("alpha")) {
                                    version = tagName;
                                    break;
                                }
                            }
                        }
                        javelinConfig.getVscodium().setVersion(version);

                        return new StringBuilder()
                                .append(javelinConfig.getVscodium().getPrefix())
                                .append(version)
                                .append("/")
                                .append(String.format(javelinConfig.getVscodium().getFilenamePattern(), version))
                                .toString();
                    }
                    return null;
                });
    }

    // Eclipse Temurin JDK 다운로드
    private Mono<Void> downloadEclipseTemurinJDK() {
        log.info("Eclipse Temurin JDK 다운로드");

        String apiUrl = new StringBuilder()
                .append(javelinConfig.getEclipseTemurin().getUrl())
                .append(javelinConfig.getEclipseTemurin().getVersion())
                .append("/")
                .append(javelinConfig.getEclipseTemurin().getSuffix())
                .toString();

        return webClient.get()
                .uri(apiUrl)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .flatMap(responseList -> {
                    if (responseList != null && !responseList.isEmpty()) {
                        Map<String, Object> jdkAsset = responseList.get(0);
                        if (jdkAsset.containsKey("binary")) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> binary = (Map<String, Object>) jdkAsset.get("binary");
                            if (binary.containsKey("installer")) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> packageInfo = (Map<String, Object>) binary.get("installer");
                                if (packageInfo.containsKey("link")) {
                                    String downloadUrl = packageInfo.get("link").toString();
                                    return downloadFile(downloadUrl, javelinConfig.getRoot(), false);
                                }
                            }
                        }
                    }
                    return Mono.empty();
                });
    }

    // Apache Maven 다운로드
    private Mono<Void> downloadApacheMaven() {
        log.info("Apache Maven 다운로드");

        String fixedVersion = javelinConfig.getApacheMaven().getFixedVersion();

        Mono<String> versionMono;
        if (fixedVersion != null && !fixedVersion.isEmpty()) {
            versionMono = Mono.just(fixedVersion);
        } else {
            versionMono = webClient.get()
                    .uri(javelinConfig.getApacheMaven().getUrl())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                    .map(responseList -> {
                        for (Map<String, Object> tempRes : responseList) {
                            if (tempRes != null && tempRes.containsKey("name")
                                    && tempRes.get("name").toString().startsWith("maven-")
                                    && !tempRes.get("name").toString().contains("alpha")
                                    && !tempRes.get("name").toString().contains("beta")
                                    && !tempRes.get("name").toString().contains("rc")) {
                                return tempRes.get("name").toString().replace("maven-", "");
                            }
                        }
                        return "";
                    });
        }

        return versionMono.flatMap(latestVersion -> {
            if (latestVersion == null || latestVersion.isEmpty()) {
                log.warn("Apache Maven latest version not found");
                return Mono.empty();
            }

            String downloadUrl = new StringBuilder()
                    .append(javelinConfig.getApacheMaven().getPrefix())
                    .append(latestVersion.substring(0, 1)).append("/")
                    .append(latestVersion).append("/binaries/apache-maven-")
                    .append(latestVersion).append("-bin.tar.gz")
                    .toString();

            return downloadFile(downloadUrl, javelinConfig.getRoot(), false);
        });
    }

    // Gradle 다운로드
    private Mono<Void> downloadGradle() {
        log.info("Gradle 다운로드");

        String fixedVersion = javelinConfig.getGradle().getFixedVersion();

        Mono<String> versionMono;
        if (fixedVersion != null && !fixedVersion.isEmpty()) {
            versionMono = Mono.just(fixedVersion);
        } else {
            versionMono = webClient.get()
                    .uri(javelinConfig.getGradle().getUrl())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .map(response -> {
                        if (response != null && response.containsKey("version")) {
                            return response.get("version").toString();
                        }
                        return "";
                    });
        }

        return versionMono.flatMap(latestVersion -> {
            if (latestVersion == null || latestVersion.isEmpty()) {
                log.warn("Gradle latest version not found");
                return Mono.empty();
            }

            String downloadUrl = new StringBuilder()
                    .append(javelinConfig.getGradle().getPrefix())
                    .append(latestVersion).append("-bin.zip")
                    .toString();

            return downloadFile(downloadUrl, javelinConfig.getRoot(), false);
        });
    }

    // Git 다운로드
    private Mono<Void> downloadGit() {
        log.info("Git 다운로드");

        String fixedVersion = javelinConfig.getGit().getFixedVersion();

        Mono<String> versionMono;
        if (fixedVersion != null && !fixedVersion.isEmpty()) {
            versionMono = Mono.just(fixedVersion);
        } else {
            versionMono = webClient.get()
                    .uri(javelinConfig.getGit().getUrl())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                    .map(responseList -> {
                        for (Map<String, Object> tempRes : responseList) {
                            if (tempRes != null && tempRes.containsKey("name")
                                    && !tempRes.get("name").toString().contains("-rc")) {
                                return tempRes.get("name").toString();
                            }
                        }
                        return "";
                    });
        }

        return versionMono.flatMap(latestVersion ->
            webClient.get()
                .uri(javelinConfig.getGit().getPrefix() + latestVersion)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .flatMap(subResponse -> {
                    if (subResponse != null && subResponse.containsKey("assets")) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> responseList = (List<Map<String, Object>>) subResponse.get("assets");

                        for (Map<String, Object> assets : responseList) {
                            if (assets != null && assets.containsKey("browser_download_url")
                                    && assets.get("browser_download_url").toString().contains("64-bit.exe")) {
                                String downloadUrl = assets.get("browser_download_url").toString();
                                return downloadFile(downloadUrl, javelinConfig.getRoot(), false);
                            }
                        }
                    }
                    return Mono.empty();
                })
        );
    }

    // Postman 다운로드
    private Mono<Void> downloadPostman() {
        log.info("Postman 다운로드");

        String downloadUrl = javelinConfig.getPostman().getPrefix()
                + javelinConfig.getPostman().getFixedVersion()
                + javelinConfig.getPostman().getSuffix();

        return downloadFile(downloadUrl, javelinConfig.getRoot(), false);
    }

    // Extensions 다운로드
    private Mono<Void> downloadExtensions() {
        log.info("Extensions 다운로드");

        String extensionBasePath = javelinConfig.getVscodium().getExtensions().getClass().getSimpleName();
        Path extensionRootPath = Paths.get(javelinConfig.getRoot(), extensionBasePath);

        return Mono.fromCallable(() -> {
            Files.createDirectories(extensionRootPath);
            return extensionRootPath.toString();
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(extensionRoot -> 
            Flux.fromIterable(javelinConfig.getVscodium().getExtensions().getCategory().entrySet())
                .flatMap(entry -> {
                    String categoryPath = extensionRoot + "/" + entry.getKey();
                    try {
                        Files.createDirectories(Paths.get(categoryPath));
                    } catch (IOException e) {
                        return Mono.error(new RuntimeException("Failed to create extension category directory: " + categoryPath, e));
                    }

                    return Flux.fromIterable(entry.getValue())
                            .flatMap(category -> 
                                getLatestVersion(category.getPublisher(), category.getExtensionName(), categoryPath + "/")
                            ).then();
                })
                .then()
        );
    }

    // 확장 버전 조회, 다운로드 처리
    private Mono<Void> getLatestVersion(String publisher, String extensionName, String baseDir) {
        String openVsxApiUrl = "https://open-vsx.org/api/" + publisher + "/" + extensionName;

        return webClient.get()
                .uri(openVsxApiUrl)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(responseBody -> {
                    try {
                        JsonNode root = objectMapper.readTree(responseBody);
                        String latestVersion = root.path("version").asText();

                        String downloadUrl = String.format(
                            "https://open-vsx.org/api/%s/%s/%s/file/%s.%s-%s.vsix",
                            publisher, extensionName, latestVersion, publisher, extensionName, latestVersion
                        );

                        String targetPath = baseDir + publisher + "." + extensionName + "." + latestVersion + ".vsix";

                        return downloadFile(downloadUrl, targetPath, true);

                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                })
                .onErrorResume(throwable -> {
                    // Open VSX 실패 시 VSCode Marketplace API 대체 시도
                    String marketplaceUrl = "https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery?api-version=6.1-preview.1";
                    String body = String.format(
                            "{\"filters\":[{\"criteria\":[{\"filterType\":7,\"value\":\"%s.%s\"}]}],\"flags\":870}",
                            publisher, extensionName
                    );

                    return webClient.post()
                            .uri(marketplaceUrl)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(String.class)
                            .flatMap(responseBody -> {
                                try {
                                    JsonNode root = objectMapper.readTree(responseBody);

                                    String latestVersion = root.path("results").get(0)
                                            .path("extensions").get(0)
                                            .path("versions").get(0)
                                            .path("version").asText();

                                    String downloadUrl = String.format(
                                        "https://%s.gallery.vsassets.io/_apis/public/gallery/publisher/%s/extension/%s/%s/assetbyname/Microsoft.VisualStudio.Services.VSIXPackage",
                                        publisher, publisher, extensionName, latestVersion);

                                    String targetPath = baseDir + publisher + "." + extensionName + "." + latestVersion + ".vsix";

                                    return downloadFile(downloadUrl, targetPath, true);
                                } catch (Exception e) {
                                    return Mono.error(e);
                                }
                            });
                });
    }

    // 파일 다운로드 및 저장
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
                
                // WebClient 설정에서 리다이렉션이 자동 처리되므로 직접 다운로드
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