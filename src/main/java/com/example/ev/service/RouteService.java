package com.example.ev.service;

import com.example.ev.model.FindRequest;
import com.example.ev.model.FindResponse;
import com.example.ev.util.Polyline;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@RequiredArgsConstructor
public class RouteService {

    private final RestTemplate restTemplate;

    @Value("${google.routes.api.key}")
    private String googleApiKey;

    @Value("${chargepoint.map.api.url}")
    private String chargepointApiUrl;

    @Value("${ev.bufferPercent:0.30}")
    private double bufferPercent;

    private final double KM_PER_MILE = 1.609344;

    public FindResponse findChargingPlan(FindRequest req) throws Exception {
        double currentRange = req.getCurrentRangeMiles();
        double currentSoc = req.getSoc(); // SOC as percentage (0-100)

        // Calculate full range based on current range and SOC
        // If currentRangeMiles = 100 and SOC = 50%, then fullRange = 100 / 0.5 = 200
        double fullRange = currentRange / (currentSoc / 100.0);

        // Apply 30% buffer to current range - keep 30% as safety margin
        // If current range is 450 miles, effective usable range is 315 miles (70% of 450)
        double effectiveRange = currentRange * (1 - bufferPercent);

        // Call Google Routes API to get route polyline and total distance
        Map<String, Object> routeData = callGoogleRoutesApi(req);
        if (routeData == null) {
            throw new RuntimeException("No route returned from Google Routes API");
        }

        double totalMeters = ((Number) routeData.getOrDefault("distanceMeters", 0)).doubleValue();
        double totalMiles = totalMeters / 1609.344;
        List<double[]> polyline = (List<double[]>) routeData.get("polyline");
        String encodedPolyline = (String) routeData.get("encodedPolyline");

        FindResponse response = new FindResponse(false, totalMiles, 0.0, new ArrayList<>());
        response.setEncodedPolyline(encodedPolyline);

        // Initialize route sequence with origin
        response.getRouteSequence().add(new FindResponse.RoutePoint(
            req.getOrigin().latitude, req.getOrigin().longitude, "origin"
        ));

        // Check if destination is reachable without charging
        // Compare effective range (with buffer) against total distance for safety check
        if (effectiveRange >= totalMiles) {
            // Can reach destination without charging
            // But calculate remaining range and SOC based on actual current range, not effective range
            double actualRemainingRange = currentRange - totalMiles;
            response.setReachableWithoutCharging(true);
            response.setRemainingRangeAfterRoute(actualRemainingRange);

            // Calculate final SOC at destination based on actual current range
            double finalSOC = calculateFinalSOC(currentSoc, currentRange, totalMiles, fullRange);
            response.setFinalSOCAtDestination(finalSOC);

            // Add intermediate stops to route sequence if any
            if (req.getIntermediates() != null) {
                for (FindRequest.LatLng intermediate : req.getIntermediates()) {
                    response.getRouteSequence().add(new FindResponse.RoutePoint(
                        intermediate.latitude, intermediate.longitude, "intermediate"
                    ));
                }
            }

            // Add destination to route sequence
            response.getRouteSequence().add(new FindResponse.RoutePoint(
                req.getDestination().latitude, req.getDestination().longitude, "destination"
            ));

            return response;
        }

        // Need charging - recursively find charging stations and plan route
        // Track remaining intermediate stops (initially all of them)
        List<FindRequest.LatLng> remainingIntermediates = req.getIntermediates() != null ?
            new ArrayList<>(req.getIntermediates()) : new ArrayList<>();

        return findChargingStopsRecursively(req, polyline, effectiveRange, fullRange, response, remainingIntermediates);
    }

    /**
     * Calculate final SOC percentage when vehicle reaches destination
     */
    private double calculateFinalSOC(double startSOC, double startRange, double distanceTraveled, double fullRange) {
        // Calculate remaining range after travel
        double remainingRange = startRange - distanceTraveled;
        // Convert remaining range to SOC percentage
        double finalSOC = (remainingRange / fullRange) * 100.0;
        return Math.max(0.0, finalSOC); // Ensure not negative
    }

    private FindResponse findChargingStopsRecursively(FindRequest req, List<double[]> polyline,
                                                     double currentEffectiveRange, double fullRange,
                                                     FindResponse response, List<FindRequest.LatLng> remainingIntermediates) throws Exception {

        double accumulatedMiles = 0;
        int lastReachableIndex = 0;
        List<FindRequest.LatLng> reachedIntermediates = new ArrayList<>();

        // Iterate through polyline points
        for (int i = 1; i < polyline.size(); i++) {
            double[] previousPoint = polyline.get(i - 1);
            double[] currentPoint = polyline.get(i);
            double segmentMiles = haversineMiles(previousPoint[0], previousPoint[1],
                    currentPoint[0], currentPoint[1]);
            accumulatedMiles += segmentMiles;

            if (accumulatedMiles <= currentEffectiveRange) {
                lastReachableIndex = i;

                // Check if we've reached any intermediate stops at this point
                List<FindRequest.LatLng> justReached = checkAndUpdateReachedIntermediates(currentPoint, remainingIntermediates);
                reachedIntermediates.addAll(justReached);
            } else {
                // Add any intermediate stops we reached before needing to charge
                for (FindRequest.LatLng reached : reachedIntermediates) {
                    response.getRouteSequence().add(new FindResponse.RoutePoint(
                        reached.latitude, reached.longitude, "intermediate"
                    ));
                }

                // Cannot reach current point, search for charging station at last reachable point
                double[] searchPoint = polyline.get(lastReachableIndex);

                // Calculate battery percentage when reaching this charging station
                double distanceToStation = 0;
                for (int k = 1; k <= lastReachableIndex; k++) {
                    double[] prev = polyline.get(k - 1);
                    double[] curr = polyline.get(k);
                    distanceToStation += haversineMiles(prev[0], prev[1], curr[0], curr[1]);
                }

                // Calculate remaining range and battery percentage at station
                double remainingRangeAtStation = req.getCurrentRangeMiles() - distanceToStation;
                double batteryPercentageOnArrival = (remainingRangeAtStation / fullRange) * 100.0;
                // Apply buffer consideration - if we're using effective range, the actual battery % will be higher

                FindResponse.Stop chargingStation = searchForChargingStation(searchPoint[0], searchPoint[1]);

                if (chargingStation == null) {
                    // No station found, try previous polyline points with distance-based search to avoid gaps
                    // Instead of skipping fixed number of points, skip based on distance to ensure coverage
                    double searchRadiusKm = 14.0; // Our search radius is 10km (7km half-radius × 2)
                    double maxGapKm = searchRadiusKm * 0.8; // Allow 80% overlap (8km gaps max)

                    double accumulatedDistance = 0;
                    for (int j = lastReachableIndex - 1; j >= 0 && j >= lastReachableIndex - 300; j--) {
                        double[] currentSearchPoint = polyline.get(j);
                        double[] previousSearchPoint = polyline.get(j + 1);

                        // Calculate distance from previous search point
                        double segmentDistanceKm = haversineMiles(currentSearchPoint[0], currentSearchPoint[1],
                                previousSearchPoint[0], previousSearchPoint[1]) * KM_PER_MILE;
                        accumulatedDistance += segmentDistanceKm;

                        // Only search if we've moved far enough to avoid too much overlap
                        if (accumulatedDistance >= maxGapKm) {
                            chargingStation = searchForChargingStation(currentSearchPoint[0], currentSearchPoint[1]);
                            if (chargingStation != null) {
                                break;
                            }
                        }
                    }
                }

                if (chargingStation == null) {
                    response.setReachableWithoutCharging(false);
                    response.setRemainingRangeAfterRoute(0);
                    // Add final destination even if unreachable for route visualization
                    response.getRouteSequence().add(new FindResponse.RoutePoint(
                        req.getDestination().latitude, req.getDestination().longitude, "destination"
                    ));
                    return response;
                }

                // Set battery percentage information for this charging station
                chargingStation.setBatteryPercentageOnArrival(Math.max(0.0, batteryPercentageOnArrival)); // Ensure not negative
                chargingStation.setBatteryPercentageAfterCharging(90.0); // Always charge to 90%

                response.getStops().add(chargingStation);

                // Add charging station to route sequence
                response.getRouteSequence().add(new FindResponse.RoutePoint(
                    chargingStation.getLat(), chargingStation.getLon(), "charging_station"
                ));

                // Calculate new route from charging station to destination
                FindRequest newRequest = new FindRequest();
                FindRequest.LatLng stationLocation = new FindRequest.LatLng();
                stationLocation.latitude = chargingStation.getLat();
                stationLocation.longitude = chargingStation.getLon();
                newRequest.setOrigin(stationLocation);
                newRequest.setDestination(req.getDestination());

                // Only include remaining intermediate stops that haven't been reached yet
                if (!remainingIntermediates.isEmpty()) {
                    newRequest.setIntermediates(new ArrayList<>(remainingIntermediates));
                }

                // After charging, EV manufacturers recommend charging only up to 90% for battery health
                // So range after charging = fullRange * 0.90, then apply 30% buffer for safety
                double newRange = fullRange * 0.90; // Charge to 90% of full capacity
                double newEffectiveRange = newRange * (1 - bufferPercent); // Apply 30% buffer
                newRequest.setCurrentRangeMiles(newRange);
                // Set SOC to 90% (recommended max charge level)
                newRequest.setSoc(90.0);

                // Get new route from station to destination (with remaining intermediates)
                Map<String, Object> newRouteData = callGoogleRoutesApi(newRequest);
                if (newRouteData == null) {
                    throw new RuntimeException("No route from charging station to destination");
                }

                double remainingMeters = ((Number) newRouteData.getOrDefault("distanceMeters", 0)).doubleValue();
                double remainingMiles = remainingMeters / 1609.344;
                List<double[]> newPolyline = (List<double[]>) newRouteData.get("polyline");

                if (newEffectiveRange >= remainingMiles) {
                    // Can reach destination from this charging station
                    response.setReachableWithoutCharging(true);
                    // Use actual newRange (not newEffectiveRange) for consistent calculation
                    response.setRemainingRangeAfterRoute(newRange - remainingMiles);

                    // Calculate final SOC at destination
                    double finalSOC = calculateFinalSOC(90.0, newRange, remainingMiles, fullRange);
                    response.setFinalSOCAtDestination(finalSOC);

                    // Add any remaining intermediate stops to route sequence
                    if (remainingIntermediates != null) {
                        for (FindRequest.LatLng intermediate : remainingIntermediates) {
                            response.getRouteSequence().add(new FindResponse.RoutePoint(
                                intermediate.latitude, intermediate.longitude, "intermediate"
                            ));
                        }
                    }

                    // Add destination to route sequence
                    response.getRouteSequence().add(new FindResponse.RoutePoint(
                        req.getDestination().latitude, req.getDestination().longitude, "destination"
                    ));

                    return response;
                } else {
                    // Need more charging stations, continue recursively with remaining intermediates
                    return findChargingStopsRecursively(newRequest, newPolyline, newEffectiveRange, fullRange, response, remainingIntermediates);
                }
            }
        }

        // If we reach here, the route is complete without needing more charging
        // This means we've traversed the entire polyline within our effective range
        // Add any remaining intermediate stops that were reached
        for (FindRequest.LatLng reached : reachedIntermediates) {
            response.getRouteSequence().add(new FindResponse.RoutePoint(
                reached.latitude, reached.longitude, "intermediate"
            ));
        }

        // Add remaining intermediate stops if any
        if (remainingIntermediates != null) {
            for (FindRequest.LatLng intermediate : remainingIntermediates) {
                response.getRouteSequence().add(new FindResponse.RoutePoint(
                    intermediate.latitude, intermediate.longitude, "intermediate"
                ));
            }
        }

        // Add destination to route sequence
        response.getRouteSequence().add(new FindResponse.RoutePoint(
            req.getDestination().latitude, req.getDestination().longitude, "destination"
        ));

        response.setReachableWithoutCharging(true);
        return response;
    }

    /**
     * Check if the current point is close to any remaining intermediate stops
     * and remove them from the remaining list if reached
     */
    private List<FindRequest.LatLng> checkAndUpdateReachedIntermediates(double[] currentPoint, List<FindRequest.LatLng> remainingIntermediates) {
        double reachThresholdMiles = 1.0; // Consider intermediate reached if within 1 mile

        List<FindRequest.LatLng> justReached = new ArrayList<>();
        Iterator<FindRequest.LatLng> iterator = remainingIntermediates.iterator();
        while (iterator.hasNext()) {
            FindRequest.LatLng intermediate = iterator.next();
            double distance = haversineMiles(currentPoint[0], currentPoint[1],
                    intermediate.latitude, intermediate.longitude);

            if (distance <= reachThresholdMiles) {
                justReached.add(intermediate);
                iterator.remove(); // Remove this intermediate as we've reached it
                System.out.println("Reached intermediate stop at: " + intermediate.latitude + ", " + intermediate.longitude);
            }
        }

        return justReached;
    }

    private Map<String, Object> callGoogleRoutesApi(FindRequest req) {
        String url = "https://routes.googleapis.com/directions/v2:computeRoutes";

        // Create request payload matching the exact structure from your curl example
        Map<String, Object> payload = new HashMap<>();

        Map<String, Object> origin = Map.of(
                "location", Map.of(
                        "latLng", Map.of(
                                "latitude", req.getOrigin().latitude,
                                "longitude", req.getOrigin().longitude
                        )
                )
        );

        Map<String, Object> destination = Map.of(
                "location", Map.of(
                        "latLng", Map.of(
                                "latitude", req.getDestination().latitude,
                                "longitude", req.getDestination().longitude
                        )
                )
        );

        payload.put("origin", origin);
        payload.put("destination", destination);

        // Add intermediates if provided
        if (req.getIntermediates() != null && !req.getIntermediates().isEmpty()) {
            List<Map<String, Object>> intermediates = new ArrayList<>();
            for (FindRequest.LatLng intermediate : req.getIntermediates()) {
                Map<String, Object> intermediateLocation = Map.of(
                        "location", Map.of(
                                "latLng", Map.of(
                                        "latitude", intermediate.latitude,
                                        "longitude", intermediate.longitude
                                )
                        )
                );
                intermediates.add(intermediateLocation);
            }
            payload.put("intermediates", intermediates);
        }

        payload.put("travelMode", "DRIVE");
        payload.put("routingPreference", "TRAFFIC_AWARE_OPTIMAL");
//        payload.put("polylineQuality", "HIGH_QUALITY");

        // Set headers exactly as specified in your curl example
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Goog-Api-Key", googleApiKey);
        headers.set("X-Goog-FieldMask", "routes.distanceMeters,routes.polyline.encodedPolyline");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                System.err.println("Google Routes API error: " + response.getStatusCode());
                return null;
            }

            // Parse JSON response
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response.getBody());

            com.fasterxml.jackson.databind.JsonNode routeNode = root.path("routes").get(0);
            if (routeNode == null) return null;

            double distanceMeters = routeNode.path("distanceMeters").asDouble(0);
            String encodedPolyline = routeNode.path("polyline").path("encodedPolyline").asText();

            List<double[]> polyline = new ArrayList<>();
            if (encodedPolyline != null && !encodedPolyline.isEmpty()) {
                polyline = Polyline.decode(encodedPolyline);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("distanceMeters", distanceMeters);
            result.put("polyline", polyline);
            System.out.println("polyline " + polyline);
            result.put("encodedPolyline", encodedPolyline); // Add encoded polyline to result

            return result;

        } catch (Exception ex) {
            System.err.println("Error calling Google Routes API: " + ex.getMessage());
            ex.printStackTrace();
            return null;
        }
    }

    private FindResponse.Stop searchForChargingStation(double lat, double lon) {
        double halfKm = 7.0; // half of 5 km (so total box = 10 km height × 10 km width)

        // Approximate conversion factors
        double kmPerDegLat = 110.574; // ~ km per degree latitude
        double kmPerDegLon = 111.320 * Math.cos(Math.toRadians(lat)); // ~ km per degree longitude at this latitude

        // Convert km to degrees
        double dLat = halfKm / kmPerDegLat;
        double dLon = halfKm / kmPerDegLon;

        // Bounding box corners
        double neLat = lat + dLat;
        double neLon = lon + dLon;
        double swLat = lat - dLat;
        double swLon = lon - dLon;

        // Create payload matching your curl example structure
        Map<String, Object> stationList = new HashMap<>();
        stationList.put("screen_width", 417.5);
        stationList.put("screen_height", 548);
        stationList.put("ne_lat", neLat);
        stationList.put("ne_lon", neLon);
        stationList.put("sw_lat", swLat);
        stationList.put("sw_lon", swLon);
        stationList.put("page_size", 10);
        stationList.put("page_offset", "");
        stationList.put("sort_by", "distance");
        stationList.put("reference_lat", lat);
        stationList.put("reference_lon", lon);
        stationList.put("include_map_bound", true);

        // Add filters for available DC fast charging stations
        Map<String, Object> filter = new HashMap<>();
        filter.put("status_available", true);
        filter.put("dc_fast_charging", true);
        stationList.put("filter", filter);
        stationList.put("bound_output", true);

        Map<String, Object> payload = Map.of("station_list", stationList);

        // Set headers exactly as specified in your curl example
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("accept", "*/*");
        headers.set("accept-language", "en-GB");
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(chargepointApiUrl, entity, String.class);
            System.out.println("pk" + response);
            if (!response.getStatusCode().is2xxSuccessful()) {
                System.err.println("ChargePoint API error: " + response.getStatusCode());
                return null;
            }

            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response.getBody());

            com.fasterxml.jackson.databind.JsonNode stations = root.path("station_list").path("stations");
            if (stations.isArray() && stations.size() > 0) {
                // Return the first (closest) station for the charging route logic
                // But we'll add all stations to a separate list for frontend display
                com.fasterxml.jackson.databind.JsonNode firstStation = stations.get(0);

                double stationLat = firstStation.path("lat").asDouble(firstStation.path("latitude").asDouble());
                double stationLon = firstStation.path("lon").asDouble(firstStation.path("longitude").asDouble());
                String stationName = firstStation.path("name").asText(
                        firstStation.path("station_name").asText(
                            firstStation.path("name1").asText("ChargePoint Station")
                        )
                );
                Integer deviceId = firstStation.path("device_id").asInt(0);

                FindResponse.Stop stop = new FindResponse.Stop();
                stop.setLat(stationLat);
                stop.setLon(stationLon);
                stop.setStationName(stationName);
                stop.setDistanceFromRoutePointMiles(haversineMiles(lat, lon, stationLat, stationLon));
                stop.setDeviceId(deviceId);

                // Store the complete raw station data for the primary station
                try {
                    Object rawFirstStation = objectMapper.treeToValue(firstStation, Object.class);
                    stop.setRawStationData(rawFirstStation);
                } catch (Exception e) {
                    System.err.println("Error converting first station to raw data: " + e.getMessage());
                }

                // Store all raw stations for this search location
                List<Object> allRawStations = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode station : stations) {
                    try {
                        Object rawStation = objectMapper.treeToValue(station, Object.class);
                        allRawStations.add(rawStation);
                    } catch (Exception e) {
                        System.err.println("Error converting station to raw data: " + e.getMessage());
                    }
                }

                // Add all raw stations to the main stop for reference
                stop.setAllRawStations(allRawStations);
                return stop;
            }

            return null;

        } catch (Exception ex) {
            System.err.println("Error calling ChargePoint API: " + ex.getMessage());
            ex.printStackTrace();
            return null;
        }
    }

    private double haversineMiles(double lat1, double lon1, double lat2, double lon2) {
        double R = 3958.8; // Earth radius in miles
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
