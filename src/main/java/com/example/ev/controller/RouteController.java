package com.example.ev.controller;

import com.example.ev.model.FindRequest;
import com.example.ev.service.RouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;

    @PostMapping("/find-charge-route")
    public ResponseEntity<?> findChargeRoute(@RequestBody FindRequest request) {
        try {
            return ResponseEntity.ok(routeService.findChargingPlan(request));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }
}
