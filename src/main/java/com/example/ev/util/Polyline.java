package com.example.ev.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple decoder for Google's encoded polyline.
 * Source: adapted for brevity.
 */
public class Polyline {

    public static List<double[]> decode(String encoded) {
        List<double[]> path = new ArrayList<double[]>();
        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;

        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += dlat;

            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += dlng;

            double latitude = lat / 1E5;
            double longitude = lng / 1E5;
            path.add(new double[]{latitude, longitude});
        }
        return path;
    }
}
