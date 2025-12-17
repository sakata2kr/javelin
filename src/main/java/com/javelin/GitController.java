package com.javelin;

import java.util.List;

import org.springframework.http.MediaType;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.Builder;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Controller
public class GitController {

    private final WebClient webClient;
    private final JavelinConfig javelinConfig;

    public GitController(WebClient.Builder webClientBuilder, JavelinConfig javelinConfig) {
        this.javelinConfig = javelinConfig;

        Builder builder = webClientBuilder.clone();
        JavelinConfig.GitLab gitlabConfig = javelinConfig.getGitlab();

        if (gitlabConfig != null && gitlabConfig.getPrivateToken() != null && !gitlabConfig.getPrivateToken().trim().isEmpty()) {
            builder.defaultHeader("PRIVATE-TOKEN", gitlabConfig.getPrivateToken());
        }

        this.webClient = builder.build();
    }
    
    // DTO for GitLab Project
    public record GitLabProject(
        String id,
        String name,
        String description,
        @JsonProperty("web_url") String webUrl,
        @JsonProperty("last_activity_at") String lastActivityAt,
        @JsonProperty("star_count") int starCount,
        @JsonProperty("forks_count") int forksCount,
        @JsonProperty("tag_list") List<String> tagList
    ) {}

    // DTO for GitLab Repository Tree Item
    public record GitLabRepoTreeItem(
        String id,
        String name,
        String type, // "tree" or "blob"
        String path,
        String mode
    ) {}

    @GetMapping("/api/gitlab/projects")
    @ResponseBody
    public Mono<List<GitLabProject>> getProjects() {
        JavelinConfig.GitLab gitlabConfig = javelinConfig.getGitlab();
        
        String projectsApiUrl;
        if (gitlabConfig.getGroupId() != null && !gitlabConfig.getGroupId().isEmpty()) {
            projectsApiUrl = String.format("%s/api/v4/groups/%s/projects", gitlabConfig.getUrl(), gitlabConfig.getGroupId());
        } else {
            projectsApiUrl = String.format("%s/api/v4/projects", gitlabConfig.getUrl());
        }

        if (projectsApiUrl == null) {
            throw new IllegalStateException("GitLab URL is not configured");
        }
        
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(projectsApiUrl)
            .queryParam("simple", true)
            .queryParam("order_by", "last_activity_at")
            .queryParam("sort", "desc");
        
        if (gitlabConfig.getIncludeSubgroups() != null) {
            uriBuilder.queryParam("include_subgroups", gitlabConfig.getIncludeSubgroups());
        }
        if (gitlabConfig.getArchived() != null) {
            uriBuilder.queryParam("archived", gitlabConfig.getArchived());
        }
        if (gitlabConfig.getPerPage() != null) {
            uriBuilder.queryParam("per_page", gitlabConfig.getPerPage());
        }

        String finalUrl = uriBuilder.build().toUriString();
        log.info("Fetching GitLab projects from URL: {}", finalUrl);

        return webClient.get()
                .uri(finalUrl)
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        // Check content type BEFORE trying to decode
                        if (response.headers().contentType().map(t -> t.isCompatibleWith(MediaType.APPLICATION_JSON)).orElse(false)) {
                            return response.bodyToFlux(GitLabProject.class).collectList();
                        } else {
                            return response.bodyToMono(String.class).flatMap(body -> {
                                log.error("GitLab API returned a non-JSON Content-Type. Body: {}", body);
                                return Mono.error(new IllegalStateException("Authentication or URL is likely incorrect. GitLab returned an HTML page."));
                            });
                        }
                    } else {
                        return response.bodyToMono(String.class).flatMap(body -> {
                            log.error("GitLab API returned an error. Status: {}. Body: {}", response.statusCode(), body);
                            return Mono.error(new IllegalStateException("Error fetching projects from GitLab. Status: " + response.statusCode()));
                        });
                    }
                });
    }

    @GetMapping("/api/gitlab/projects/{projectId}/repository/tree")
    @ResponseBody
    public Mono<List<GitLabRepoTreeItem>> getRepositoryTree(
            @PathVariable String projectId,
            @RequestParam(required = false) String path) {
        String gitlabUrl = javelinConfig.getGitlab().getUrl();
        String finalUrl = gitlabUrl + "/api/v4/projects/" + projectId + "/repository/tree";
        log.info("Fetching repository tree from URL: {}", finalUrl);
        
        return webClient.get()
                .uri(finalUrl, uriBuilder -> {
                    if (path != null && !path.isEmpty()) {
                        uriBuilder.queryParam("path", path);
                    }
                    return uriBuilder.build();
                })
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful() && response.headers().contentType().map(t -> t.isCompatibleWith(MediaType.APPLICATION_JSON)).orElse(false)) {
                        return response.bodyToFlux(GitLabRepoTreeItem.class).collectList();
                    } else {
                         return response.bodyToMono(String.class).flatMap(body -> {
                            log.error("GitLab API returned an unexpected response for tree. Status: {}. Body: {}", response.statusCode(), body);
                            return Mono.error(new IllegalStateException("Error fetching repository tree from GitLab."));
                        });
                    }
                });
    }

    @GetMapping("/api/gitlab/projects/{projectId}/repository/files/raw")
    @ResponseBody
    public Mono<String> getRawFile(
            @PathVariable String projectId,
            @RequestParam("path") String filePath) {
        String gitlabUrl = javelinConfig.getGitlab().getUrl();
        // The file path needs to be URL-encoded, especially for slashes.
        String encodedFilePath = java.net.URLEncoder.encode(filePath, java.nio.charset.StandardCharsets.UTF_8);

        return webClient.get()
                // The ref (branch/tag) is a required parameter for this GitLab API call. Defaulting to 'master'.
                .uri(gitlabUrl + "/api/v4/projects/" + projectId + "/repository/files/" + encodedFilePath + "/raw?ref=master") 
                .accept(MediaType.APPLICATION_JSON) // Add Accept header here
                .retrieve()
                .bodyToMono(String.class);
    }

    @GetMapping("/git")
    public Mono<String> git(Model model) {
        return getProjects().map(projects -> {
            model.addAttribute("projects", projects);
            return "git";
        });
    }
}
