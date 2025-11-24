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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Component
@RequiredArgsConstructor
public class JavelinDownloadFiles
{
    private final WebClient webClient;
    private final JavelinConfig javelinConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();  // ObjectMapper 재사용

    @PostConstruct
    public void init()
    {
        // 다운로드 기능이 비활성화된 경우 실행하지 않음
        if (!javelinConfig.getDownload().isEnable())
        {
            log.info("다운로드 기능이 비활성화되어 있습니다. (javelin.download.enable=false)");
            return;
        }
        else if (javelinConfig.getDownload().isClear())
        {
            log.warn("다운로드 파일 초기화 기능이 활성화 되어 있습니다. (javelin.download.clear=true)");

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
                log.error("다운로드 디렉토리 초기화 실패");
            }
        }
    }

    // 매일 오전 2시에 실행 (cron: 초 분 시 일 월 요일)
    @Scheduled(fixedDelay=200000)
    private void executeDownload()
    {
        // 다운로드 기능이 비활성화된 경우 실행하지 않음
        if (!javelinConfig.getDownload().isEnable()) {
            log.info("다운로드 기능이 비활성화되어 있습니다. (javelin.download.enable=false)");
            return;
        }

        // 다운로드 작업 리스트 생성
        java.util.List<Mono<Void>> downloadTasks = new java.util.ArrayList<>();
        downloadTasks.add(downloadAmazonCorrettoJDK());
        downloadTasks.add(downloadApacheMaven());
        downloadTasks.add(downloadGradle());
        downloadTasks.add(downloadGit());
        downloadTasks.add(downloadVscode());
        downloadTasks.add(downloadSpringToolSuite());
        downloadTasks.add(downloadExtension());
        
        // Postman이 활성화된 경우에만 다운로드 목록에 추가
        if (javelinConfig.getPostman().isEnabled()) {
            log.info("Postman 다운로드가 활성화되어 있습니다.");
            downloadTasks.add(downloadPostman());
        } else {
            log.info("Postman 다운로드가 비활성화되어 있습니다.");
        }
        
        // 다운로드 작업을 순차적으로 하나씩 실행 (concurrency = 1)
        Flux.fromIterable(downloadTasks)
                .concatMap(mono -> mono
                    .doOnError(e -> log.error("Download error on task", e))
                    .onErrorResume(e -> {
                        log.warn("작업 실패, 다음 작업을 계속 진행합니다.");
                        return Mono.empty();
                    })
                )
                .then()
                .doOnSuccess(v -> {
                    log.info("FINISH DOWNLOAD !!");
                })
                .doOnError(e -> {
                    log.error("전체 다운로드 프로세스 오류", e);
                })
                .subscribe();
    }

    // Amazon Corretto JDK 다운로드 (복수 버전)
    private Mono<Void> downloadAmazonCorrettoJDK() {
        log.info("Amazon Corretto JDK 다운로드 (복수 버전)");

        java.util.List<Integer> versions = javelinConfig.getAmazonCorretto().getVersions();
        if (versions == null || versions.isEmpty()) {
            log.warn("Amazon Corretto 버전이 설정되지 않았습니다.");
            return Mono.empty();
        }

        // 각 버전별로 순차적으로 다운로드 (concatMap 사용)
        return Flux.fromIterable(versions)
                .concatMap(version -> {
                    String downloadUrl = javelinConfig.getAmazonCorretto().getUrl()
                            .replace("{version}", String.valueOf(version));
                    
                    log.info("Amazon Corretto {} 다운로드 시작", version);
                    log.info("Amazon Corretto {} 다운로드 URL: {}", version, downloadUrl);
                    
                    return downloadFile(downloadUrl, javelinConfig.getDownload().getPath(), false)
                            .doOnSuccess(v -> log.info("Amazon Corretto {} 다운로드 완료", version))
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
                log.warn("Gradle 최신 버전 확인 불가");
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

    // VSCode 다운로드
    private Mono<Void> downloadVscode() {
        log.info("VSCode 다운로드");
        String apiUrl = javelinConfig.getVscode().getUrl();

        return webClient.get()
                .uri(Objects.requireNonNull(apiUrl))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .flatMap(response -> {
                    if (response != null && response.containsKey("name") && response.containsKey("url")) {
                        String version = response.get("name").toString();
                        String downloadUrl = response.get("url").toString();
                        
                        javelinConfig.getVscode().setVersion(version);
                        
                        log.info("VSCode 버전: {}", version);
                        log.info("VSCode 다운로드 URL: {}", downloadUrl);
                        
                        return downloadFile(downloadUrl, javelinConfig.getDownload().getPath(), false);
                    } else {
                        log.warn("VSCode API 응답에 필요한 정보가 없습니다.");
                        return Mono.empty();
                    }
                })
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.error("VSCode API 호출 실패: {}", e.getMessage());
                    return Mono.empty();
                })
                .onErrorResume(Exception.class, e -> {
                    log.error("VSCode 다운로드 중 예상치 못한 오류: {}", e.getMessage());
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

        String extensionBasePath = javelinConfig.getVscode().getExtension().getClass().getSimpleName();
        Path extensionRootPath = Paths.get(javelinConfig.getDownload().getPath(), extensionBasePath);

        return Mono.fromCallable(() -> {
            Files.createDirectories(extensionRootPath);
            return extensionRootPath.toString();
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(extensionRoot -> 
            Flux.fromIterable(javelinConfig.getVscode().getExtension().getCategory().entrySet())
                .concatMap(entry -> {
                    String categoryPath = extensionRoot + "/" + entry.getKey();
                    log.info("Extension 카테고리 처리 시작: {}", entry.getKey());
                    
                    try
                    {
                        Files.createDirectories(Paths.get(categoryPath));
                    }
                    catch (Exception e)
                    {
                        log.error("다운로드 디렉토리 초기화 실패");
                        return Mono.empty();
                    }

                    return Flux.fromIterable(entry.getValue())
                            .concatMap(category -> {
                                log.info("Extension 다운로드 시작: {}.{}", category.getPublisher(), category.getExtensionName());
                                return getLatestVersion(category.getPublisher(), category.getExtensionName(), categoryPath + "/")
                                    .doOnSuccess(v -> log.info("Extension 다운로드 완료: {}.{}", category.getPublisher(), category.getExtensionName()));
                            })
                            .then()
                            .doOnSuccess(v -> log.info("Extension 카테고리 완료: {}", entry.getKey()));
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

    // 파일 다운로드 및 저장 - 메타데이터 캐시를 활용한 중복 다운로드 방지
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
            
            // 파일이 이미 존재하는지 확인
            return Mono.fromCallable(() -> Files.exists(finalTargetPath))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(fileExists -> {
                    if (fileExists) {
                        log.info("파일이 이미 존재합니다. 다운로드를 건너뜁니다: {}", finalTargetPath);
                        return Mono.empty();
                    }
                    
                    // 파일이 없으면 다운로드 수행
                    log.info("파일을 다운로드합니다: {}", finalTargetPath);
                    return performDownload(decodeUrl, finalTargetPath);
                });
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
    
    // 실제 다운로드 수행
    private Mono<Void> performDownload(String decodeUrl, Path finalTargetPath) {
        return webClient.get()
            .uri(Objects.requireNonNull(decodeUrl))
            .retrieve()
            .bodyToFlux(DataBuffer.class)
            .timeout(Duration.ofMinutes(30))
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
                DataBufferUtils.write(Objects.requireNonNull(dataBufferFlux), Objects.requireNonNull(finalTargetPath))
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
                    log.info("다운로드 완료 - {} : {} bytes", finalTargetPath, fileSize);
                    
                    if (fileSize == 0) {
                        log.warn("파일이 비어있습니다: {}", finalTargetPath);
                    }
                    
                    return fileSize;
                } catch (IOException e) {
                    log.error("파일 크기 확인 실패: {}", finalTargetPath, e);
                    return 0L;
                }
            }).subscribeOn(Schedulers.boundedElastic()).then())
            .doOnError(e -> log.error("다운로드 중 오류 발생 - URL: {}, Path: {}", decodeUrl, finalTargetPath, e));
    }
}