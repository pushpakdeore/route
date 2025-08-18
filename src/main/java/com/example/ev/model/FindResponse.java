package com.example.ev.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
public class FindResponse {
    private boolean reachableWithoutCharging;
    private double totalRouteDistanceMiles;
    private double remainingRangeAfterRoute;
    private List<Stop> stops = new ArrayList<>();
    private String encodedPolyline; // Add encoded polyline to response
    private List<RoutePoint> routeSequence = new ArrayList<>(); // Sequential route points for frontend mapping

    // Constructor for backward compatibility
    public FindResponse(boolean reachableWithoutCharging, double totalRouteDistanceMiles,
                       double remainingRangeAfterRoute, List<Stop> stops) {
        this.reachableWithoutCharging = reachableWithoutCharging;
        this.totalRouteDistanceMiles = totalRouteDistanceMiles;
        this.remainingRangeAfterRoute = remainingRangeAfterRoute;
        this.stops = stops;
        this.encodedPolyline = null;
        this.routeSequence = new ArrayList<>();
    }

    @Data
    public static class Stop {
        private double lat;
        private double lon;
        private String stationName;
        private double distanceFromRoutePointMiles;
        private Integer deviceId; // Add device_id from ChargePoint API
    }

    @Data
    public static class RoutePoint {
        private double lat;
        private double lon;
        private String type; // "origin", "intermediate", "charging_station", "destination"

        public RoutePoint(double lat, double lon, String type) {
            this.lat = lat;
            this.lon = lon;
            this.type = type;
        }
    }
}
