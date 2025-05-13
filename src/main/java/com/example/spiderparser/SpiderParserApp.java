package com.example.spiderparser;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.DisposableBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;
import okhttp3.ConnectionSpec;
import okhttp3.Protocol;
import java.util.Arrays;

@SpringBootApplication
public class SpiderParserApp {
    public static void main(String[] args) {
        SpringApplication.run(SpiderParserApp.class, args);
    }
}

@RestController
class SpiderParserController {
    private final SpiderParser parser;

    public SpiderParserController(SpiderParser parser) {
        this.parser = parser;
    }

    @GetMapping("/parse")
    public CompletableFuture<List<Match>> parseData() {
        return parser.parseData();
    }
}

@Component
class SpiderParser implements DisposableBean {
    private final List<String> sports = List.of("football", "tennis", "hockey", "basketball");
    private final ExecutorService executorService = Executors.newFixedThreadPool(3);
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .protocols(Arrays.asList(Protocol.HTTP_1_1, Protocol.HTTP_2))
        .connectionSpecs(Arrays.asList(
                ConnectionSpec.MODERN_TLS,
                ConnectionSpec.COMPATIBLE_TLS,
                ConnectionSpec.CLEARTEXT
        ))
        .build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    public CompletableFuture<List<Match>> parseData() {
        System.out.println("Starting data parsing...");
        List<CompletableFuture<List<Match>>> futures = sports.stream()
                .map(sport -> CompletableFuture.supplyAsync(() -> {
                    try {
                        System.out.println("Processing " + sport + "...");
                        List<Match> matches = fetchMatches(sport);
                        System.out.println("Found " + matches.size() + " matches for " + sport);

                        matches.forEach(match -> {
                            System.out.println("\n" + capitalize(match.getSport()) + ", " + match.getLeague());
                            System.out.println("\t" + match.getName() + ", " + match.getStartTime() + " UTC, " + match.getId());
                            match.getMarkets().forEach(market -> {
                                System.out.println("\t\t" + market.getName());
                                market.getOutcomes().forEach(outcome -> {
                                    System.out.println("\t\t\t" + outcome.getName() + ", " + outcome.getOdds() + ", " + outcome.getId());
                                });
                            });
                        });
                        return matches;
                    } catch (Exception e) {
                        System.out.println("Error processing " + sport + ": " + e.getMessage());
                        e.printStackTrace();
                        return new ArrayList<Match>();
                    }
                }))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(List::stream)
                        .collect(Collectors.toList()));
    }

    private List<Match> fetchMatches(String sport) throws Exception {
        String url = "https://leonbets.com/api-2/betline/changes/all?ctag=en-US&vtag=9c2cd386-31e1-4ce9-a140-" +
                "28e9b63a9300&sport=" + sport + "&hideClosed=true&flags=reg,urlv2,mm2,rrc,nodup";
        System.out.println("Fetching matches for " + sport + "...");
        String response = fetchData(url);
        JsonNode json = objectMapper.readTree(response);

        if (!json.has("data")) {
            System.out.println("No 'data' field in response for " + sport);
            return new ArrayList<>();
        }

        List<String> topLeagues = StreamSupport.stream(json.get("data").spliterator(), false)
                .map(matchJson -> {
                    try {
                        JsonNode league = matchJson.get("league");
                        if (league != null && league.has("top") && league.get("top").asBoolean()) {
                            return league.get("id").asText();
                        }
                    } catch (Exception ignored) {}
                    return null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .limit(2)
                .collect(Collectors.toList());

        if (topLeagues.isEmpty()) {
            System.out.println("No top leagues found for " + sport);
            return new ArrayList<>();
        }

        List<Match> matches = new ArrayList<>();
        for (String leagueId : topLeagues) {
            StreamSupport.stream(json.get("data").spliterator(), false)
                    .filter(matchJson -> {
                        try {
                            JsonNode league = matchJson.get("league");
                            return league != null && league.has("id") &&
                                    league.get("id").asText().equals(leagueId);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .limit(1)
                    .forEach(matchJson -> {
                        try {
                            JsonNode league = matchJson.get("league");
                            Match match = new Match(
                                    matchJson.get("id").asText(),
                                    matchJson.get("name").asText(),
                                    formatTimestamp(matchJson.get("kickoff").asLong()),
                                    league.get("name").asText(),
                                    sport,
                                    parseMarkets(matchJson.get("markets"))
                            );
                            matches.add(match);
                        } catch (Exception e) {
                            System.out.println("Error parsing match: " + e.getMessage());
                        }
                    });
        }
        return matches;
    }

    private List<Market> parseMarkets(JsonNode marketsNode) {
        List<Market> markets = new ArrayList<>();
        marketsNode.forEach(marketJson -> {
            try {
                JsonNode runners = marketJson.get("runners");
                if (runners != null) {
                    List<Outcome> outcomes = new ArrayList<>();
                    runners.forEach(outcomeJson -> {
                        try {
                            outcomes.add(new Outcome(
                                    outcomeJson.get("id").asText(),
                                    outcomeJson.get("name").asText(),
                                    outcomeJson.get("price").asDouble()
                            ));
                        } catch (Exception e) {
                            System.out.println("Error parsing outcome: " + e.getMessage());
                        }
                    });
                    markets.add(new Market(marketJson.get("name").asText(), outcomes));
                }
            } catch (Exception e) {
                System.out.println("Error parsing market: " + e.getMessage());
            }
        });
        return markets;
    }

    private String formatTimestamp(long timestamp) {
        return dateFormatter.format(Instant.ofEpochMilli(timestamp));
    }

    private String fetchData(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (HTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Accept-Encoding", "gzip, deflate, br")
                .addHeader("Referer", "https://leonbets.com/")
                .addHeader("Origin", "https://leonbets.com")
                .addHeader("Connection", "keep-alive")
                .addHeader("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                .addHeader("sec-ch-ua-mobile", "?0")
                .addHeader("sec-ch-ua-platform", "\"Windows\"")
                .addHeader("Sec-Fetch-Dest", "empty")
                .addHeader("Sec-Fetch-Mode", "cors")
                .addHeader("Sec-Fetch-Site", "same-origin")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Authorization", "Bearer null")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("Pragma", "no-cache")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Request failed with code: " + response.code() + " for URL: " + url);
            }

            assert response.body() != null;
            byte[] body = response.body().bytes();
            String contentEncoding = response.header("Content-Encoding");

            byte[] decompressedBody;
            if ("gzip".equals(contentEncoding)) {
                try (GZIPInputStream gzipStream = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    decompressedBody = gzipStream.readAllBytes();
                }
            } else {
                decompressedBody = body;
            }

            return new String(decompressedBody, StandardCharsets.UTF_8);
        }
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    @Override
    public void destroy() throws Exception {
        executorService.shutdown();
        if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
            executorService.shutdownNow();
        }
    }
}