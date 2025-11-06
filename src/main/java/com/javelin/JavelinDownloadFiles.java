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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
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
        // 다운로드 기능이 비활성화된 경우 실행하지 않음
        if (!javelinConfig.getDownload().isEnable()) {
            log.info("다운로드 기능이 비활성화되어 있습니다. (javelin.download.enable=false)");
            return;
        }
        
        log.info("다운로드 기능이 활성화되어 있습니다. 다운로드를 시작합니다.");
        
        // 디렉토리 삭제
        deleteDirectory();

        // 다운로드 작업 리스트 생성
        java.util.List<Mono<Void>> downloadTasks = new java.util.ArrayList<>();
        downloadTasks.add(downloadAmazonCorrettoJDK());
        downloadTasks.add(downloadApacheMaven());
        downloadTasks.add(downloadGradle());
        downloadTasks.add(downloadGit());
        downloadTasks.add(downloadVscodium());
        downloadTasks.add(downloadSpringToolSuite());
        downloadTasks.add(downloadExtension());
        
        // Postman이 활성화된 경우에만 다운로드 목록에 추가
        if (javelinConfig.getPostman().isEnabled()) {
            log.info("Postman 다운로드가 활성화되어 있습니다.");
            downloadTasks.add(downloadPostman());
        } else {
            log.info("Postman 다운로드가 비활성화되어 있습니다.");
        }
        
        // 다운로드 작업 비동기로 실행, 블로킹 하지 않고 구독 시작
        Flux<Mono<Void>> downloads = Flux.fromIterable(downloadTasks);

        downloads.flatMap(mono -> mono.onErrorContinue((e, obj) -> {
                    log.error("Download error on task: {}", obj, e);
                }))
                .then()
                .doOnSuccess(v -> {
                    log.info("FINISH DOWNLOAD !!");
                })
                .subscribe();
    }

    private void deleteDirectory()
    {
        Path dirPath = Paths.get(javelinConfig.getDownload().getPath());
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
                .uri(Objects.requireNonNull(apiUrl))
                .retrieve()
                .bodyToMono(Objects.requireNonNull(responseType))
                .flatMap(response -> {
                    try {
                        String downloadUrl = urlExtractor.extractDownloadUrl(response);
                        if (downloadUrl == null || downloadUrl.isEmpty()) {
                            log.warn("No download URL extracted from API response: {}", apiUrl);
                            return Mono.empty();
                        }
                        return downloadFile(downloadUrl, javelinConfig.getDownload().getPath(), false);
                    } catch (Exception e) {
                        log.error("Error extracting download URL from API response", e);
                        return Mono.error(e);
                    }
                });
    }

    // Amazon Corretto JDK 다운로드 (복수 버전)
    private Mono<Void> downloadAmazonCorrettoJDK() {
        log.info("Amazon Corretto JDK 다운로드 (복수 버전)");

        java.util.List<Integer> versions = javelinConfig.getAmazonCorretto().getVersions();
        if (versions == null || versions.isEmpty()) {
            log.warn("Amazon Corretto 버전이 설정되지 않았습니다.");
            return Mono.empty();
        }

        // 각 버전별로 다운로드 작업 생성
        return Flux.fromIterable(versions)
                .flatMap(version -> {
                    String downloadUrl = javelinConfig.getAmazonCorretto().getUrl()
                            .replace("{version}", String.valueOf(version));
                    
                    log.info("Amazon Corretto {} 다운로드 URL: {}", version, downloadUrl);
                    
                    return downloadFile(downloadUrl, javelinConfig.getDownload().getPath(), false)
                            .onErrorResume(Exception.class, e -> {
                                log.error("Amazon Corretto {} 다운로드 중 오류: {}", version, e.getMessage());
                                return Mono.empty();
                            });
                })
                .then();
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
                    .uri(Objects.requireNonNull(javelinConfig.getApacheMaven().getUrl()))
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

            return downloadFile(downloadUrl, javelinConfig.getDownload().getPath(), false);
        })
        .onErrorResume(WebClientResponseException.class, e -> {
            if (e.getStatusCode().value() == 401) {
                log.warn("GitHub API 인증 실패 (401). Apache Maven 다운로드를 건너뜁니다.");
            } else {
                log.error("Apache Maven API 호출 실패: {}", e.getMessage());
            }
            return Mono.empty();
        })
        .onErrorResume(Exception.class, e -> {
            log.error("Apache Maven 다운로드 중 예상치 못한 오류: {}", e.getMessage());
            return Mono.empty();
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
                    .uri(Objects.requireNonNull(javelinConfig.getGradle().getUrl()))
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

            return downloadFile(downloadUrl, javelinConfig.getDownload().getPath(), false);
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
                    .uri(Objects.requireNonNull(javelinConfig.getGit().getUrl()))
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
                                return downloadFile(downloadUrl, javelinConfig.getDownload().getPath(), false);
                            }
                        }
                    }
                    return Mono.empty();
                })
        )
        .onErrorResume(WebClientResponseException.class, e -> {
            if (e.getStatusCode().value() == 401) {
                log.warn("GitHub API 인증 실패 (401). Git 다운로드를 건너뜁니다.");
            } else {
                log.error("Git API 호출 실패: {}", e.getMessage());
            }
            return Mono.empty();
        })
        .onErrorResume(Exception.class, e -> {
            log.error("Git 다운로드 중 예상치 못한 오류: {}", e.getMessage());
            return Mono.empty();
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
                })
                .onErrorResume(WebClientResponseException.class, e -> {
                    if (e.getStatusCode().value() == 401) {
                        log.warn("GitHub API 인증 실패 (401). VSCodium 다운로드를 건너뜁니다.");
                    } else {
                        log.error("VSCodium API 호출 실패: {}", e.getMessage());
                    }
                    return Mono.empty();
                })
                .onErrorResume(Exception.class, e -> {
                    log.error("VSCodium 다운로드 중 예상치 못한 오류: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    // Spring Tool Suite 다운로드
    private Mono<Void> downloadSpringToolSuite() {
        log.info("Spring Tool Suite 다운로드");

        String stsVersion = javelinConfig.getSpringToolSuite().getStsVersion();
        String eclipseVersion = javelinConfig.getSpringToolSuite().getEclipseVersion();
        
        log.info("Spring Tool Suite 버전: {}", stsVersion);
        log.info("Eclipse 버전: {}", eclipseVersion);
        
        // STS 버전의 첫 번째 문자를 추출하여 STS 접두사 생성 (예: "4.32.1.RELEASE" -> "STS4")
        String stsPrefix = "STS" + stsVersion.charAt(0);
        
        // 다운로드 URL 생성
        String downloadUrl = javelinConfig.getSpringToolSuite().getUrl()
                .replace("{sts-prefix}", stsPrefix)
                .replace("{sts-version}", stsVersion)
                .replace("{eclipse-version}", eclipseVersion);
        
        log.info("Spring Tool Suite 다운로드 URL: {}", downloadUrl);
        
        return downloadFile(downloadUrl, javelinConfig.getDownload().getPath(), false)
                .onErrorResume(Exception.class, e -> {
                    log.error("Spring Tool Suite 다운로드 중 오류: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    // Postman 다운로드
    private Mono<Void> downloadPostman() {
        log.info("Postman 다운로드");

        String downloadUrl = javelinConfig.getPostman().getPrefix()
                + javelinConfig.getPostman().getFixedVersion()
                + javelinConfig.getPostman().getSuffix();

        return downloadFile(downloadUrl, javelinConfig.getDownload().getPath(), false);
    }

    // Extension 다운로드
    private Mono<Void> downloadExtension() {
        log.info("Extension 다운로드");

        String extensionBasePath = javelinConfig.getVscodium().getExtension().getClass().getSimpleName();
        Path extensionRootPath = Paths.get(javelinConfig.getDownload().getPath(), extensionBasePath);

        return Mono.fromCallable(() -> {
            Files.createDirectories(extensionRootPath);
            return extensionRootPath.toString();
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(extensionRoot -> 
            Flux.fromIterable(javelinConfig.getVscodium().getExtension().getCategory().entrySet())
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
                });
    }

    // 파일 다운로드 및 저장 - hang 방지를 위한 개선된 버전
    private Mono<Void> downloadFile(String url, String targetPath, Boolean isExtension) {
        String decodeUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);
        
        log.info("DOWNLOAD URL : {}", decodeUrl);
        log.info("Determining final file path for: {}", targetPath); 
        
        return Mono.fromCallable(() -> {
            // 파일명 결정 로직을 먼저 처리
            String filename = null;
            
            if (isExtension) {
                filename = Paths.get(targetPath).getFileName().toString();
            } else {
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
                throw new RuntimeException("Cannot determine filename from URL: " + decodeUrl);
            }
            
            Path finalTargetPath = isExtension ? Paths.get(targetPath) : Paths.get(targetPath, filename);
            
            try {
                Files.createDirectories(finalTargetPath.getParent());
            } catch (IOException e) {
                throw new RuntimeException("Failed to create directories for " + finalTargetPath.getParent(), e);
            }
            
            return finalTargetPath;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(finalTargetPath -> {
            log.info("Final file will be written to: {}", finalTargetPath.toAbsolutePath());
            
            // 스트리밍 다운로드로 메모리 사용량 최적화
            return webClient.get()
                .uri(Objects.requireNonNull(decodeUrl))
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .timeout(Duration.ofMinutes(30)) // 전체 다운로드 타임아웃을 30분으로 증가
                .doOnNext(dataBuffer -> {
                    if (log.isDebugEnabled()) {
                        log.debug("Received DataBuffer of size: {} bytes", dataBuffer.readableByteCount());
                    }
                })
                .doOnComplete(() -> {
                    log.info("DataBuffer flux completed for URL: {}", decodeUrl);
                })
                .doOnError(e -> {
                    log.error("Error in DataBuffer flux for URL: {}", decodeUrl, e);
                })
                .transform(dataBufferFlux -> 
                    DataBufferUtils.write(Objects.requireNonNull(dataBufferFlux), finalTargetPath)
                        .subscribeOn(Schedulers.boundedElastic())
                        .doOnSuccess(v -> {
                            log.info("DataBufferUtils.write completed for: {}", finalTargetPath);
                        })
                        .doOnError(e -> {
                            log.error("Error in DataBufferUtils.write for: {}", finalTargetPath, e);
                        })
                )
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
                }).subscribeOn(Schedulers.boundedElastic()).then())
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