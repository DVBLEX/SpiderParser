package com.example.spiderparser;

import java.util.List;
import java.util.ArrayList;

public class Market {
    private String name;
    private List<Outcome> outcomes;

    public Market(String name, List<Outcome> outcomes) {
        this.name = name;
        this.outcomes = outcomes != null ? outcomes : new ArrayList<>();
    }


    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Outcome> getOutcomes() {
        return outcomes;
    }

    public void setOutcomes(List<Outcome> outcomes) {
        this.outcomes = outcomes;
    }
}
