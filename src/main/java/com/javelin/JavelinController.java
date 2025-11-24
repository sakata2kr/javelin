package com.javelin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        List<String> fileNames = new ArrayList<>();
        try
        {
            Files.walk(Paths.get(javelinConfig.getDownload().getPath()))
                .filter(Files::isRegularFile)
                .forEach(path -> fileNames.add(Paths.get(javelinConfig.getDownload().getPath()).relativize(path).toString()));
        }
        catch (Exception e)
        {
            log.error("FILE FETCH ERROR!!");
        }

        Map<String, Object>tempMap = new HashMap<>();

        Set<Map<String, Object>> jdkSet        = new LinkedHashSet<>();
        Set<Map<String, Object>> commonExtSet  = new LinkedHashSet<>();
        Set<Map<String, Object>> javaExtSet    = new LinkedHashSet<>();
        Set<Map<String, Object>> springExtSet  = new LinkedHashSet<>();
        Set<Map<String, Object>> openapiExtSet = new LinkedHashSet<>();
        Set<Map<String, Object>> expensionSet  = new LinkedHashSet<>();
        Set<Map<String, Object>> baseSet       = new LinkedHashSet<>();

        String[] parts = null;

        for (String fileName : fileNames)
        {
            tempMap = new HashMap<>();
            parts = fileName.split("/");
            if ( parts.length > 1)
            {
                // Extension에 대해서만 처리함
                switch( parts[1].toLowerCase() )
                {
                    case "common" :
                    case "remote" :
                        tempMap.put("category", "VS CODE 확장");
                        tempMap.put("subcategory", "공통");
                        tempMap.put("filename", parts[2]);
                        tempMap.put("url", "/getFile/" + fileName);
                        commonExtSet.add(tempMap);
                        break;

                    case "java" :
                        tempMap.put("category", "VS CODE 확장");
                        tempMap.put("subcategory", "Java");
                        tempMap.put("filename", parts[2]);
                        tempMap.put("url", "/getFile/" + fileName);
                        javaExtSet.add(tempMap);
                        break;

                    case "spring" :
                        tempMap.put("category", "VS CODE 확장");
                        tempMap.put("subcategory", "Spring");
                        tempMap.put("filename", parts[2]);
                        tempMap.put("url", "/getFile/" + fileName);
                        springExtSet.add(tempMap);
                        break;

                    case "openapi" :
                        tempMap.put("category", "VS CODE 확장");
                        tempMap.put("subcategory", "OpenAPI");
                        tempMap.put("filename", parts[2]);
                        tempMap.put("url", "/getFile/" + fileName);
                        openapiExtSet.add(tempMap);
                        break;
                }

                if ( !tempMap.isEmpty() )
                {
                    expensionSet.add(tempMap);
                }
            }
            else if ( fileName.toLowerCase().contains("jdk") )
            {
                // 파일명에서 버전 추출 (예: amazon-corretto-21-x64-windows-jdk.msi -> 21)
                String version = "Unknown";
                try {
                    String[] fileNameParts = fileName.split("-");
                    for (String part : fileNameParts) {
                        if (part.matches("\\d+")) {
                            version = part;
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.warn("JDK 파일에서 버전 추출 실패: {}", fileName);
                }
                
                tempMap.put("category", "Amazon Corretto JDK");
                tempMap.put("subcategory", version);
                tempMap.put("filename", fileName);
                tempMap.put("url", "/getFile/" + fileName);
                jdkSet.add(tempMap);
            }
            else if ( fileName.toLowerCase().startsWith("apache-maven") )
            {
                tempMap.put("category", "Apache Maven");
                tempMap.put("filename", fileName);
                tempMap.put("url", "/getFile/" + fileName);
                baseSet.add(tempMap);
            }
            else if ( fileName.toLowerCase().startsWith("gradle") )
            {
                tempMap.put("category", "Gradle");
                tempMap.put("filename", fileName);
                tempMap.put("url", "/getFile/" + fileName);
                baseSet.add(tempMap);
            }
            else if ( fileName.toLowerCase().startsWith("git") )
            {
                tempMap.put("category", "Git 설치 파일");
                tempMap.put("filename", fileName);
                tempMap.put("url", "/getFile/" + fileName);
                baseSet.add(tempMap);
            }
            else if ( fileName.toLowerCase().startsWith("vscode") )
            {
                tempMap.put("category", "VSCodium (Visual Studio Code 기반 IDE)");
                tempMap.put("filename", fileName);
                tempMap.put("url", "/getFile/" + fileName);
                baseSet.add(tempMap);
            }
            else if ( fileName.toLowerCase().contains("spring-tools") )
            {
                tempMap.put("category", "Spring Tool Suite (Eclipse 기반 IDE)");
                tempMap.put("filename", fileName);
                tempMap.put("url", "/getFile/" + fileName);
                baseSet.add(tempMap);
            }
            else if ( fileName.toLowerCase().startsWith("postman") )
            {
                tempMap.put("category", "Postman 설치 파일");
                tempMap.put("filename", fileName);
                tempMap.put("url", "/getFile/" + fileName);
                baseSet.add(tempMap);
            }
        }

        model.addAttribute("basefiles", baseSet);
        model.addAttribute("jdkfiles", jdkSet);
        model.addAttribute("commexts", commonExtSet);
        model.addAttribute("javaexts", javaExtSet);
        model.addAttribute("springexts", springExtSet);
        model.addAttribute("openapiexts", openapiExtSet);

        return "index";
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