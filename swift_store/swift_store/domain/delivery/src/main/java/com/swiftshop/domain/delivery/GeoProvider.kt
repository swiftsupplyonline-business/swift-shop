package com.swiftshop.domain.delivery

import com.swiftshop.core.model.GeoPoint

/**
 * Interface for geospatial services.
 * Shields domain logic from specific map SDKs (osmdroid, Mapbox, etc.).
 */
interface GeoProvider {
    /**
     * Calculates the great-circle distance between two points in meters.
     */
    fun getDistanceMeters(from: GeoPoint, to: GeoPoint): Double
}
