# EV Charger Finder - Spring Boot

This project is a minimal Spring Boot application that demonstrates finding charging stops along a driving route.

Features:
- Calls Google Routes API to fetch polyline for a route.
- Iterates points and checks reachability using currentRangeMiles with a buffer (30% by default).
- When the car can't reach next polyline point, searches ChargePoint map API within ~5km box for stations.
- Simple charging logic: restores range to `fullRangeMiles * chargePercent` when a station is used.
- Configurable values in `src/main/resources/application.properties`.

Important:
- You MUST set `google.routes.api.key` in `application.properties` to a valid Google Routes API key.
- ChargePoint map API endpoint is set to `chargepoint.map.api.url`. The example ChargePoint API may require cookies/headers or authentication to return results — adapt as needed.

How to run:
1. Build: `mvn -U -DskipTests clean package`
2. Run: `java -jar target/ev-charger-finder-0.0.1-SNAPSHOT.jar`
3. POST to `http://localhost:8080/api/v1/find-charge-route` with JSON body:
```json
{
  "origin": {"latitude": 23.0154595, "longitude": 72.5531299},
  "destination": {"latitude": 18.5246091, "longitude": 73.8786239},
  "currentRangeMiles": 100.0,
  "soc": 0.45,
  "fullRangeMiles": 220.0
}
```

Notes & next steps:
- This is a starting implementation for demonstration and local testing. In production:
  - Add retry/backoff, proper error handling, logging.
  - Securely store API keys (env vars / secrets manager).
  - Improve selection of charging stations (filters, availability).
  - Implement proper SOC<->range conversions and charging time estimation.
