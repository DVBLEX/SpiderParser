package model

data class Match(
    val id: String,
    val name: String,
    val startTime: String,
    val league: String,
    val sport: String,
    val markets: List<Market> = emptyList()
)

data class Market(
    val name: String,
    val outcomes: List<Outcome> = emptyList()
)

data class Outcome(
    val id: String,
    val name: String,
    val odds: Double
) 