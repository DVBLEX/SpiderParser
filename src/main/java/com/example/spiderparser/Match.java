package com.example.spiderparser;

import java.util.List;
import java.util.ArrayList;

public class Match {
    private String id;
    private String name;
    private String startTime;
    private String league;
    private String sport;
    private List<Market> markets;

    public Match(String id, String name, String startTime, String league, String sport, List<Market> markets) {
        this.id = id;
        this.name = name;
        this.startTime = startTime;
        this.league = league;
        this.sport = sport;
        this.markets = markets != null ? markets : new ArrayList<>();
    }


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getLeague() {
        return league;
    }

    public void setLeague(String league) {
        this.league = league;
    }

    public String getSport() {
        return sport;
    }

    public void setSport(String sport) {
        this.sport = sport;
    }

    public List<Market> getMarkets() {
        return markets;
    }

    public void setMarkets(List<Market> markets) {
        this.markets = markets;
    }
}

