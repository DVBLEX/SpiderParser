package com.example.leonparser

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.stereotype.Component
import com.fasterxml.jackson.databind.ObjectMapper
import model.Match
import model.Market
import model.Outcome
import org.springframework.web.bind.annotation.ResponseBody
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream

@SpringBootApplication
class SpiderParserApp

fun main(args: Array<String>) {
    runApplication<SpiderParserApp>(*args)
}

@RestController
class SpiderParserController(private val parser: SpiderParser) {
    @GetMapping("/parse")
    @ResponseBody
    suspend fun parseData(): List<Match> = parser.parseData()
}

@OptIn(ExperimentalCoroutinesApi::class)
@Component
class SpiderParser {
    private val sports = listOf("football", "tennis", "hockey", "basketball")
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).build()
    private val objectMapper = ObjectMapper()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneOffset.UTC)

    suspend fun parseData(): List<Match> = coroutineScope {
        println("Starting data parsing...")
        val jobs = sports.map { sport ->
            async(Dispatchers.IO.limitedParallelism(3)) {
                try {
                    println("Processing $sport...")
                    val matches = fetchMatches(sport)
                    println("Found ${matches.size} matches for $sport")

                    matches.forEach { match ->
                        println("\n${match.sport.capitalize()}, ${match.league}")
                        println("\t${match.name}, ${match.startTime} UTC, ${match.id}")
                        match.markets.forEach { market ->
                            println("\t\t${market.name}")
                            market.outcomes.forEach { outcome ->
                                println("\t\t\t${outcome.name}, ${outcome.odds}, ${outcome.id}")
                            }
                        }
                    }
                    matches
                } catch (e: Exception) {
                    println("Error processing $sport: ${e.message}")
                    e.printStackTrace()
                    emptyList()
                }
            }
        }
        jobs.awaitAll().flatten()
    }

    private suspend fun fetchMatches(sport: String): List<Match> {
        val url =
            "https://leonbets.com/api-2/betline/changes/all?ctag=en-US&vtag=9c2cd386-31e1-4ce9-a140-" +
                    "28e9b63a9300&sport=$sport&hideClosed=true&flags=reg,urlv2,mm2,rrc,nodup"
        println("Fetching matches for $sport...")
        val response = fetchData(url)
        val json = objectMapper.readTree(response)

        if (!json.has("data")) {
            println("No 'data' field in response for $sport")
            return emptyList()
        }


        val topLeagues = json.get("data").mapNotNull { matchJson ->
                try {
                    val league = matchJson.get("league")
                    if (league != null && league.has("top") && league.get("top").asBoolean()) {
                        league.get("id").asText()
                    } else null
                } catch (_: Exception) {
                    null
                }
            }.distinct().take(2)

        if (topLeagues.isEmpty()) {
            println("No top leagues found for $sport")
            return emptyList()
        }


        return topLeagues.flatMap { leagueId ->
            json.get("data").filter { matchJson ->
                    try {
                        val league = matchJson.get("league")
                        league != null && league.has("id") && league.get("id").asText() == leagueId
                    } catch (_: Exception) {
                        false
                    }
                }.take(1).mapNotNull { matchJson ->
                    try {
                        val league = matchJson.get("league")
                        Match(
                            id = matchJson.get("id").asText(),
                            name = matchJson.get("name").asText(),
                            startTime = formatTimestamp(matchJson.get("kickoff").asLong()),
                            league = league.get("name").asText(),
                            sport = sport,
                            markets = parseMarkets(matchJson.get("markets"))
                        )
                    } catch (e: Exception) {
                        println("Error parsing match: ${e.message}")
                        null
                    }
                }
        }
    }

    private fun parseMarkets(marketsNode: com.fasterxml.jackson.databind.JsonNode): List<Market> {
        return marketsNode.mapNotNull { marketJson ->
            try {
                val runners = marketJson.get("runners") ?: return@mapNotNull null
                Market(
                    name = marketJson.get("name").asText(), outcomes = runners.mapNotNull { outcomeJson ->
                        try {
                            Outcome(
                                id = outcomeJson.get("id").asText(),
                                name = outcomeJson.get("name").asText(),
                                odds = outcomeJson.get("price").asDouble()
                            )
                        } catch (e: Exception) {
                            println("Error parsing outcome: ${e.message}")
                            null
                        }
                    })
            } catch (e: Exception) {
                println("Error parsing market: ${e.message}")
                null
            }
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        return dateFormatter.format(Instant.ofEpochMilli(timestamp))
    }

    private suspend fun fetchData(url: String): String {
        val request = Request.Builder().url(url).addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (HTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            ).addHeader("Accept", "application/json, text/plain, */*").addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Accept-Encoding", "gzip, deflate, br").addHeader("Referer", "https://leonbets.com/")
            .addHeader("Origin", "https://leonbets.com").addHeader("Connection", "keep-alive")
            .addHeader("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
            .addHeader("sec-ch-ua-mobile", "?0").addHeader("sec-ch-ua-platform", "\"Windows\"")
            .addHeader("Sec-Fetch-Dest", "empty").addHeader("Sec-Fetch-Mode", "cors")
            .addHeader("Sec-Fetch-Site", "same-origin").addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Authorization", "Bearer null").addHeader("Cache-Control", "no-cache")
            .addHeader("Pragma", "no-cache").build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Request failed with code: ${response.code} for URL: $url")
            }

            val body = response.body?.bytes() ?: throw Exception("Empty response from URL: $url")
            val contentEncoding = response.header("Content-Encoding")

            val decompressedBody = if (contentEncoding == "gzip") {
                GZIPInputStream(ByteArrayInputStream(body)).use { it.readBytes() }
            } else {
                body
            }

            String(decompressedBody, Charsets.UTF_8)
        }
    }
}

fun String.capitalize(): String {
    return this.replaceFirstChar { it.uppercase() }
}