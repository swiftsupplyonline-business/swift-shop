package com.swiftshop

import com.swiftshop.core.model.GeoPoint
import org.junit.Assert.*
import org.junit.Test

class W25B_UIContractTest {

    @Test
    fun `geopoint preserves precision in UI context`() {
        // High precision Maseru coordinate
        val lat = -29.31666712345
        val lng = 27.48333312345
        val gp = GeoPoint(lat, lng)
        
        // Ensure no lossy conversion in data holder
        assertEquals(lat, gp.lat, 0.00000000001)
        assertEquals(lng, gp.lng, 0.00000000001)
    }

    @Test
    fun `coordinate validation rejects non-finite values`() {
        assertFalse(GeoPoint(Double.NaN, 27.0).isValid())
        assertFalse(GeoPoint(-29.0, Double.POSITIVE_INFINITY).isValid())
        assertTrue(GeoPoint(-29.3167, 27.4833).isValid())
    }
}
