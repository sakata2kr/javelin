package com.javelin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
@RequestMapping("/javelin")
@RequiredArgsConstructor
public class JavelinController
{    
    private final JavelinConfig JavelinConfig;

    @GetMapping({"/", ""})
    public String showAll(Model model)
    {
        List<String> fileNames = new ArrayList<>();
        try
        {
            Files.walk(Paths.get(JavelinConfig.getRoot()))
                .filter(Files::isRegularFile)
                .forEach(path -> fileNames.add(Paths.get(JavelinConfig.getRoot()).relativize(path).toString()));
        }
        catch (Exception e)
        {
            log.error("FILE FETCH ERROR!!");
        }

        Map<String, Object>tempMap = new HashMap<>();

        Set<Map<String, Object>> jdkSet       = new LinkedHashSet<>();
        Set<Map<String, Object>> commonExtSet = new LinkedHashSet<>();
        Set<Map<String, Object>> javaExtSet   = new LinkedHashSet<>();
        Set<Map<String, Object>> springExtSet = new LinkedHashSet<>();
        Set<Map<String, Object>> golangExtSet = new LinkedHashSet<>();
        Set<Map<String, Object>> expensionSet = new LinkedHashSet<>();
        Set<Map<String, Object>> baseSet    = new LinkedHashSet<>();

        for (String jdkVersion : JavelinConfig.getMicrosoftJdk() )
        {
            log.debug("JDK VERSION : {}", jdkVersion);
            tempMap = new HashMap<>();
            tempMap.put("category", "Microsoft JDK");
            tempMap.put("subcategory", jdkVersion);
            jdkSet.add(tempMap);
        }

        String[] parts = null;

        for (String fileName : fileNames)
        {
            tempMap = new HashMap<>();
            parts = fileName.split("/");
            if ( parts.length > 1)
            {
                // extensions에 대해서만 처리함
                switch( parts[1].toLowerCase() )
                {
                    case "common" :
                    case "remote" :
                        tempMap.put("category", "VS CODE 확장");
                        tempMap.put("subcategory", "공통");
                        tempMap.put("filename", parts[2]);
                        tempMap.put("url", "/javelin/getFile/" + fileName);
                        commonExtSet.add(tempMap);
                        break;

                    case "java" :
                        tempMap.put("category", "VS CODE 확장");
                        tempMap.put("subcategory", "Java");
                        tempMap.put("filename", parts[2]);
                        tempMap.put("url", "/javelin/getFile/" + fileName);
                        javaExtSet.add(tempMap);
                        break;

                    case "spring" :
                        tempMap.put("category", "VS CODE 확장");
                        tempMap.put("subcategory", "Spring");
                        tempMap.put("filename", parts[2]);
                        tempMap.put("url", "/javelin/getFile/" + fileName);
                        springExtSet.add(tempMap);
                        break;

                    case "golang" :
                        tempMap.put("category", "VS CODE 확장");
                        tempMap.put("subcategory", "Golang");
                        tempMap.put("filename", parts[2]);
                        tempMap.put("url", "/javelin/getFile/" + fileName);
                        golangExtSet.add(tempMap);
                        break;
                }

                if ( !tempMap.isEmpty() )
                {
                    expensionSet.add(tempMap);
                }
            }
            else if ( fileName.toLowerCase().startsWith("vscode") )
            {
                tempMap.put("category", "VS CODE 설치 파일");
                tempMap.put("filename", fileName);
                tempMap.put("url", "/javelin/getFile/" + fileName);
                baseSet.add(tempMap);
            }
            else if ( fileName.toLowerCase().startsWith("apache-maven") )
            {
                tempMap.put("category", "Apache Maven");
                tempMap.put("filename", fileName);
                tempMap.put("url", "/javelin/getFile/" + fileName);
                baseSet.add(tempMap);
            }
            else if ( fileName.toLowerCase().startsWith("gradle") )
            {
                tempMap.put("category", "Gradle");
                tempMap.put("filename", fileName);
                tempMap.put("url", "/javelin/getFile/" + fileName);
                baseSet.add(tempMap);
            }
            else if ( fileName.toLowerCase().startsWith("git") )
            {
                tempMap.put("category", "Git 설치 파일");
                tempMap.put("filename", fileName);
                tempMap.put("url", "/javelin/getFile/" + fileName);
                baseSet.add(tempMap);
            }
            else if ( fileName.toLowerCase().startsWith("postman") )
            {
                tempMap.put("category", "Postman 설치 파일");
                tempMap.put("filename", fileName);
                tempMap.put("url", "/javelin/getFile/" + fileName);
                baseSet.add(tempMap);
            }
            else
            {
                for (Map<String, Object> jdkInfo : jdkSet)
                {
                    if ( fileName.toLowerCase().startsWith("microsoft-jdk-" + jdkInfo.get("subcategory").toString()) )
                    {
                        jdkInfo.put("filename", fileName);
                        jdkInfo.put("url", "/javelin/getFile/" + fileName);
                        break;
                    }
                }
            }
        }

        model.addAttribute("basefiles", baseSet);
        model.addAttribute("jdkfiles", jdkSet);
        model.addAttribute("commexts", commonExtSet);
        model.addAttribute("javaexts", javaExtSet);
        model.addAttribute("springexts", springExtSet);
        model.addAttribute("golangexts", golangExtSet);

        return "index";
    }

    @GetMapping("/getAll")
    @ResponseBody
    public ResponseEntity<List<String>> getAllFiles()
    {
        try
        {
            List<String> fileNames = new ArrayList<>();
            Files.walk(Paths.get(JavelinConfig.getRoot()))
                .filter(Files::isRegularFile)
                .forEach(path -> fileNames.add(Paths.get(JavelinConfig.getRoot()).relativize(path).toString()));
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
            Path filePath = Paths.get(JavelinConfig.getRoot()).resolve(fileName).normalize();
            Resource resource = new UrlResource(filePath.toUri());
            if (JavelinConfig.isDownloadComplete() && resource.exists())
            {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(resource);
            }
            else
            {
                return ResponseEntity.status(404).body(null);
            }
        }
        catch (Exception e)
        {
            return ResponseEntity.status(500).body(null);
        }
    }

    @GetMapping("/getFile/extensions/{category}/{fileName:.+}")
    @ResponseBody
    public ResponseEntity<Resource> downloadExtensionsFile(@PathVariable String category, @PathVariable String fileName)
    {
        try
        {
            Path filePath = Paths.get(JavelinConfig.getRoot() + JavelinConfig.getVscode().getExtensions().getRoot() + category).resolve(fileName).normalize();
            Resource resource = new UrlResource(filePath.toUri());
            if (JavelinConfig.isDownloadComplete() && resource.exists())
            {
                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
            }
            else
            {
                return ResponseEntity.status(404).body(null);
            }
        }
        catch (Exception e)
        {
            return ResponseEntity.status(500).body(null);
        }
    }
}