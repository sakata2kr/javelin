package com.javelin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/javelin")
@RequiredArgsConstructor
public class JavelinController
{
    private final JavelinConfig JavelinConfig;

    @GetMapping("/getAll")
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

    @SuppressWarnings("null")
    @GetMapping("/getFile/{fileName:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileName)
    {
        try
        {
            Path filePath = Paths.get(JavelinConfig.getRoot()).resolve(fileName).normalize();
            Resource resource = new UrlResource(filePath.toUri());
            if (JavelinConfig.isDownloadComplete() && resource.exists())
            {
                return ResponseEntity.ok().body(resource);
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

    @SuppressWarnings("null")
    @GetMapping("/getFile/extensions/{category}/{fileName:.+}")
    public ResponseEntity<Resource> downloadExtensionsFile(@PathVariable String category, @PathVariable String fileName)
    {
        try
        {
            Path filePath = Paths.get(JavelinConfig.getRoot() + JavelinConfig.getVscode().getExtensions().getRoot() + category).resolve(fileName).normalize();
            Resource resource = new UrlResource(filePath.toUri());
            if (JavelinConfig.isDownloadComplete() && resource.exists())
            {
                return ResponseEntity.ok().body(resource);
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


    /*
    @SuppressWarnings("null")
    @GetMapping("/{keyword}")
    public ResponseEntity<String> getFileName(@PathVariable String keyword)
    {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(JavelinConfig.getRoot())))
        {
            for (Path path : stream)
            {
                if (!Files.isDirectory(path))
                {
                    String fileName = StringUtils.cleanPath(path.getFileName().toString()).toLowerCase();
                    switch (keyword.toLowerCase())
                    {
                        case "vscode":
                            if (fileName.startsWith(keyword))
                            {
                                return ResponseEntity.ok(fileName);
                            }
                            break;
                        case "maven":
                        case "gradle":
                        case "git":
                            if (fileName.contains(keyword))
                            {
                                return ResponseEntity.ok(fileName);
                            }
                            break;
                        case "jdk/11":
                        case "jdk/17":
                        case "jdk/21":
                            if (fileName.startsWith("microsoft-" + keyword.replace("/", "-")))
                            {
                                return ResponseEntity.ok(fileName);
                            }
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        catch (Exception e)
        {
            return ResponseEntity.status(500).body("Server error");
        }

        return ResponseEntity.status(404).body("File not found");
    }

    @GetMapping("/extensions")
    public ResponseEntity<List<String>> getCommonExtensionFiles()
    {
        return getExtensionFiles("common");
    }

    @SuppressWarnings("null")
    @GetMapping("/extensions/{subfolder}")
    public ResponseEntity<List<String>> getExtensionFiles(@PathVariable String subfolder)
    {
        try {
            Path dirPath = Paths.get(
                    JavelinConfig.getRoot() + JavelinConfig.getVscode().getExtensions().getRoot() + "/" + subfolder);
            if (!Files.isDirectory(dirPath)) {
                return ResponseEntity.status(404).body(null);
            }
            List<String> fileNames = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
                for (Path path : stream) {
                    fileNames.add(StringUtils.cleanPath(path.getFileName().toString()));
                }
            }
            return ResponseEntity.ok(fileNames);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(null);
        }
    }
    */
}