package com.example.ev.model;

import lombok.Data;
import java.util.List;

@Data
public class FindRequest {
    public static class LatLng {
        public double latitude;
        public double longitude;
    }
    private LatLng origin;
    private LatLng destination;
    private List<LatLng> intermediates; // Optional intermediate stops
    /** current available range in miles (based on current SOC) */
    private double currentRangeMiles;
    /** state of charge 0-100 (percentage) */
    private Double soc;
}
