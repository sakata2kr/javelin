package com.javelin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
@RequiredArgsConstructor
public class JavelinController
{    
    private final JavelinConfig javelinConfig;

    @GetMapping({"/", ""})
    public String showAll(Model model)
    {
        List<String> relativeFileNames = new ArrayList<>();
        try
        {
            Files.walk(Paths.get(javelinConfig.getDownload().getPath()))
                .filter(Files::isRegularFile)
                .forEach(path -> relativeFileNames.add(Paths.get(javelinConfig.getDownload().getPath()).relativize(path).toString()));
        }
        catch (Exception e)
        {
            log.error("FILE FETCH ERROR!!");
        }

        Map<String, Map<String, List<Map<String, Object>>>> allToolsCategories = new LinkedHashMap<>();
        Map<String, Map<String, List<Map<String, Object>>>> vscodeCategories = new LinkedHashMap<>();

        for (String relativeFileName : relativeFileNames)
        {
            String fullPath = Paths.get(javelinConfig.getDownload().getPath()).resolve(relativeFileName).normalize().toString();
            Map<String, String> metaData = getFileMetaData(fullPath, relativeFileName);
            String version = metaData.get("version");
            String fileSize = metaData.get("file_size");
            String description = null;

            String[] parts = relativeFileName.split("/");
            if (parts.length > 1)
            {
                String subCategory = "";
                String category = "VS CODE 확장";
                String file = parts[2];
                String url = "/getFile/" + relativeFileName;

                switch(parts[1].toLowerCase())
                {
                    case "common" :
                    case "remote" :
                        subCategory = "공통";
                        description = "VS Code 공통 확장팩";
                        break;
                    case "java" :
                        subCategory = "Java";
                        description = "VS Code Java 확장팩";
                        break;
                    case "spring" :
                        subCategory = "Spring";
                        description = "VS Code Spring 확장팩";
                        break;
                    case "openapi" :
                        subCategory = "OpenAPI";
                        description = "VS Code OpenAPI 확장팩";
                        break;
                }
                if (!subCategory.isEmpty()) {
                    addFileToCategory(vscodeCategories, category, subCategory, file, url, version, fileSize, description);
                }
            }
            else 
            {
                String actualCategory = "";
                String subCategory = "파일";

                 if (relativeFileName.toLowerCase().contains("jdk")) {
                    actualCategory = "Amazon Corretto JDK";
                    description = "Java 개발 키트";
                    subCategory = version;
                 }
                else if ( relativeFileName.toLowerCase().startsWith("git") )
                {
                    actualCategory = "Git";
                    description = "소스 코드 버전 관리 시스템";
                }
                else if ( relativeFileName.toLowerCase().startsWith("apache-maven") )
                {
                    actualCategory = "Apache Maven";
                    description = "아파치 소프트웨어 재단에서 제공하는 Java 빌드 도구";
                }
                else if ( relativeFileName.toLowerCase().startsWith("gradle") )
                {
                    actualCategory = "Gradle";
                    description = "Java, Android, Kotlin 언어에 대한 오픈 소스 빌드 자동화 도구";
                }
                else if ( relativeFileName.toLowerCase().startsWith("vscode") )
                {
                    actualCategory = "Microsoft Visual Studio Code";
                    description = "마이크로소프트에서 개발한 무료 오픈소스 소스 코드 편집기";
                }
                else if ( relativeFileName.toLowerCase().contains("spring-tools") )
                {
                    actualCategory = "Spring Tool Suite";
                    description = "Eclipse IDE 를 기반으로 Spring Framework을 지원하는 IDE";
                }
                else if ( relativeFileName.toLowerCase().startsWith("postman") )
                {
                    actualCategory = "Postman";
                    description = "API 개발 및 테스트 도구";
                }

                if (!actualCategory.isEmpty()) {
                    addFileToCategory(allToolsCategories, actualCategory, subCategory, relativeFileName, "/getFile/" + relativeFileName, version, fileSize, description);
                }
            }
        }

        Map<String, Map<String, List<Map<String, Object>>>> orderedFileCategories = new LinkedHashMap<>();
        List<String> toolOrder = List.of("Amazon Corretto JDK", "Git", "Apache Maven", "Gradle", "Microsoft Visual Studio Code", "Spring Tool Suite", "Postman");

        toolOrder.forEach(toolName -> {
            if (allToolsCategories.containsKey(toolName)) {
                orderedFileCategories.put(toolName, allToolsCategories.get(toolName));
            }
        });

        if (!vscodeCategories.isEmpty()) {
            orderedFileCategories.put("VS CODE 확장", vscodeCategories.get("VS CODE 확장"));
        }

        model.addAttribute("fileCategories", orderedFileCategories);

        return "index";
    }

    private void addFileToCategory(Map<String, Map<String, List<Map<String, Object>>>> fileCategories,
                                   String category, String subcategory, String fileName, String url, String version, String fileSize, String description) {
        Map<String, Object> fileDetails = new HashMap<>();
        fileDetails.put("filename", fileName);
        fileDetails.put("url", url);
        if (version != null) {
            fileDetails.put("version", version);
        }
        if (fileSize != null) {
            fileDetails.put("file_size", fileSize);
        }
        if (description != null) {
            fileDetails.put("description", description);
        }

        fileCategories.computeIfAbsent(category, k -> new LinkedHashMap<>())
                      .computeIfAbsent(subcategory, k -> new ArrayList<>())
                      .add(fileDetails);
    }

    private Map<String, String> getFileMetaData(String fullPath, String fileName) {
        Map<String, String> metaData = new HashMap<>();
        String version = "Unknown";
        String fileSize = "Unknown";

        // Extract version
        if (fileName.toLowerCase().contains("jdk")) {
            try {
                String[] fileNameParts = fileName.split("-");
                for (String part : fileNameParts) {
                    if (part.matches("\\d+")) { // Check for digits
                        version = part;
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("JDK 파일에서 버전 추출 실패: {}", fileName);
            }
        } else {
            // General version extraction for other files
            // This is a simple regex, might need to be refined for specific patterns
            // Example: "product-1.2.3-final.zip" => "1.2.3"
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+\\.\\d+(\\.\\d+)*)").matcher(fileName);
            if (matcher.find()) {
                version = matcher.group(1);
            }
        }

        // Get file size
        try {
            Path path = Paths.get(fullPath);
            fileSize = formatFileSize(Files.size(path));
        } catch (Exception e) {
            log.warn("파일 크기 측정 실패: {}", fullPath);
        }

        metaData.put("version", version);
        metaData.put("file_size", fileSize);
        return metaData;
    }

    private String formatFileSize(long bytes) {
        String[] units = new String[] { "B", "KB", "MB", "GB", "TB" };
        int unitIndex = 0;
        double size = bytes;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return String.format("%.1f %s", size, units[unitIndex]);
    }

    @GetMapping("/ide-download")
    public String ideDownload() {
        return "ide-download";
    }

    @GetMapping("/getAll")
    @ResponseBody
    public ResponseEntity<List<String>> getAllFiles()
    {
        try
        {
            List<String> fileNames = new ArrayList<>();
            Files.walk(Paths.get(javelinConfig.getDownload().getPath()))
                .filter(Files::isRegularFile)
                .forEach(path -> fileNames.add(Paths.get(javelinConfig.getDownload().getPath()).relativize(path).toString()));
            return ResponseEntity.ok(fileNames);
        }
        catch (Exception e)
        {
            return ResponseEntity.status(500).body(null);
        }
    }

    @GetMapping("/getFile/{fileName:.+}")
    @ResponseBody
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileName)
    {
        try
        {
            Path filePath = Paths.get(javelinConfig.getDownload().getPath()).resolve(fileName).normalize();
            Resource resource = new UrlResource(Objects.requireNonNull(filePath.toUri()));
            if (resource.exists())
            {
                // 파일명만 추출 (경로 제외)
                String downloadFileName = Paths.get(fileName).getFileName().toString();
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadFileName + "\"")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_OCTET_STREAM))
                        .body(resource);
            }
            else
            {
                log.error("파일을 찾을 수 없음: {}", filePath.toAbsolutePath());
                return ResponseEntity.status(404).body(null);
            }
        }
        catch (Exception e)
        {
            log.error("파일 다운로드 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(null);
        }
    }

    @GetMapping("/getFile/extension/{category}/{fileName:.+}")
    @ResponseBody
    public ResponseEntity<Resource> downloadExtensionFile(@PathVariable String category, @PathVariable String fileName)
    {
        try
        {
            Path filePath = Paths.get(javelinConfig.getDownload().getPath() + javelinConfig.getVscode().getExtension().getRoot() + category).resolve(fileName).normalize();
            Resource resource = new UrlResource(Objects.requireNonNull(filePath.toUri()));
            if (resource.exists())
            {
                String downloadFileName = Paths.get(fileName).getFileName().toString();
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadFileName + "\"")
                                .contentType(Objects.requireNonNull(MediaType.APPLICATION_OCTET_STREAM))
                                .body(resource);
            }
            else
            {
                log.error("파일을 찾을 수 없음: {}", filePath.toAbsolutePath());
                return ResponseEntity.status(404).body(null);
            }
        }
        catch (Exception e)
        {
            log.error("파일 다운로드 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(null);
        }
    }
}