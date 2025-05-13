package com.example.spiderparser;

public class Outcome {
    private String id;
    private String name;
    private double odds;

    public Outcome(String id, String name, double odds) {
        this.id = id;
        this.name = name;
        this.odds = odds;
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

    public double getOdds() {
        return odds;
    }

    public void setOdds(double odds) {
        this.odds = odds;
    }
}
