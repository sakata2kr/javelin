package com.javelin;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

import org.springframework.web.reactive.function.client.WebClient.Builder;

@RestController
@RequestMapping("/api/nexus")
public class NexusController {

    private final WebClient webClient;
    private final JavelinConfig javelinConfig;

    public NexusController(WebClient.Builder webClientBuilder, JavelinConfig javelinConfig) {
        this.javelinConfig = javelinConfig;
        
        Builder builder = webClientBuilder.clone();
        JavelinConfig.Nexus nexusConfig = javelinConfig.getNexus();
        
        if (nexusConfig != null && nexusConfig.getUsername() != null && nexusConfig.getPassword() != null &&
            !nexusConfig.getUsername().trim().isEmpty() && !nexusConfig.getPassword().trim().isEmpty()) {
            
            String auth = nexusConfig.getUsername() + ":" + nexusConfig.getPassword();
            String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            builder.defaultHeader("PRIVATE-TOKEN", "Basic " + encodedAuth);
        }
        
        this.webClient = builder.build();
    }

    // DTO for Nexus Search API Response
    public record NexusSearchResponse(List<Item> items, String continuationToken) {}
    public record Item(String id, String repository, String format, String group, String name, String version, List<Asset> assets) {}
    public record Asset(String downloadUrl, String path, String id, String repository, String format, Checksum checksum) {}
    public record Checksum(String sha1, String md5) {}


    @GetMapping("/search")
    public Mono<NexusSearchResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String group,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String repository) {
        
        String nexusUrl = javelinConfig.getNexus().getUrl();
        
        // Scenario 1: Version History Search (group and name are provided)
        if (group != null && name != null) {
            Mono<NexusSearchResponse> releaseSearch = performNexusSearch(
                nexusUrl, javelinConfig.getNexus().getReleaseRepositoryName(), group, name, null);
            Mono<NexusSearchResponse> snapshotSearch = performNexusSearch(
                nexusUrl, javelinConfig.getNexus().getSnapshotRepositoryName(), group, name, null);

            return Mono.zip(releaseSearch, snapshotSearch)
                .map(tuple -> {
                    List<Item> combinedItems = Stream.concat(
                        tuple.getT1().items().stream(),
                        tuple.getT2().items().stream())
                        .distinct()
                        .collect(Collectors.toList());
                    return new NexusSearchResponse(combinedItems, null);
                });
        }
        // Scenario 2: General keyword search
        else if (q != null) {
            // If a specific repository is requested for a keyword search
            if (repository != null) {
                return performNexusSearch(nexusUrl, repository, null, null, q);
            }
            // Else, perform keyword search across both release and snapshot repositories
            else {
                Mono<NexusSearchResponse> releaseSearch = performNexusSearch(
                    nexusUrl, javelinConfig.getNexus().getReleaseRepositoryName(), null, null, q);
                Mono<NexusSearchResponse> snapshotSearch = performNexusSearch(
                    nexusUrl, javelinConfig.getNexus().getSnapshotRepositoryName(), null, null, q);

                return Mono.zip(releaseSearch, snapshotSearch)
                    .map(tuple -> {
                        List<Item> combinedItems = Stream.concat(
                            tuple.getT1().items().stream(),
                            tuple.getT2().items().stream())
                            .distinct()
                            .collect(Collectors.toList());
                        return new NexusSearchResponse(combinedItems, null);
                    });
            }
        }
        // Scenario 3: Initial page load (q is empty, search all)
        else {
             Mono<NexusSearchResponse> releaseSearch = performNexusSearch(
                nexusUrl, javelinConfig.getNexus().getReleaseRepositoryName(), null, null, ""); 
            Mono<NexusSearchResponse> snapshotSearch = performNexusSearch(
                nexusUrl, javelinConfig.getNexus().getSnapshotRepositoryName(), null, null, "");

            return Mono.zip(releaseSearch, snapshotSearch)
                .map(tuple -> {
                    List<Item> combinedItems = Stream.concat(
                        tuple.getT1().items().stream(),
                        tuple.getT2().items().stream())
                        .distinct()
                        .collect(Collectors.toList());
                    return new NexusSearchResponse(combinedItems, null);
                });
        }
    }

    private Mono<NexusSearchResponse> performNexusSearch(String nexusUrl, String repository, String group, String name, String q) {
        return webClient.get()
                .uri(nexusUrl + "/service/rest/v1/search", uriBuilder -> {
                    uriBuilder.queryParam("repository", repository);
                    if (group != null && name != null) {
                        uriBuilder.queryParam("group", group);
                        uriBuilder.queryParam("name", name);
                    } else if (q != null && !q.isEmpty()) { // Only add 'q' if not null or empty
                        uriBuilder.queryParam("q", "*" + q + "*");
                    } else if (q == null) { // If q is null (initial search, effectively q='*')
                        uriBuilder.queryParam("q", "*");
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .bodyToMono(NexusSearchResponse.class)
                .onErrorResume(e -> {
                    // Log the error and return an empty response to avoid breaking the UI
                    System.err.println("Error during Nexus search for repo " + repository + ": " + e.getMessage());
                    return Mono.just(new NexusSearchResponse(Collections.emptyList(), null));
                });
    }

    @GetMapping("/dependency")
    public Mono<Map<String, String>> getDependencyInfo(
            @RequestParam("g") String groupId,
            @RequestParam("a") String artifactId,
            @RequestParam("v") String version) {

        String pom = String.format("""
                <dependency>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                </dependency>""", groupId, artifactId, version);

        String gradle = String.format("implementation '%s:%s:%s'", groupId, artifactId, version);

        return Mono.just(Map.of("pom", pom, "gradle", gradle));
    }
}
