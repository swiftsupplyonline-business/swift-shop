package com.swiftshop

import com.swiftshop.core.model.GeoPoint
import com.swiftshop.core.model.LocationSnapshot
import org.junit.Assert.*
import org.junit.Test

class GeoPointTest {

    @Test
    fun `valid coordinates are valid`() {
        assertTrue(GeoPoint(0.0, 0.0).isValid())
        assertTrue(GeoPoint(-90.0, -180.0).isValid())
        assertTrue(GeoPoint(90.0, 180.0).isValid())
        assertTrue(GeoPoint(-29.3167, 27.4833).isValid()) // Maseru
    }

    @Test
    fun `invalid latitude is rejected`() {
        assertFalse(GeoPoint(-90.1, 0.0).isValid())
        assertFalse(GeoPoint(90.1, 0.0).isValid())
    }

    @Test
    fun `invalid longitude is rejected`() {
        assertFalse(GeoPoint(0.0, -180.1).isValid())
        assertFalse(GeoPoint(0.0, 180.1).isValid())
    }

    @Test
    fun `non-finite coordinates are rejected`() {
        assertFalse(GeoPoint(Double.NaN, 0.0).isValid())
        assertFalse(GeoPoint(0.0, Double.NaN).isValid())
        assertFalse(GeoPoint(Double.POSITIVE_INFINITY, 0.0).isValid())
        assertFalse(GeoPoint(0.0, Double.NEGATIVE_INFINITY).isValid())
    }
}

class LocationSnapshotTest {

    @Test
    fun `snapshot preserves fields`() {
        val snapshot = LocationSnapshot(
            lat = -29.3,
            lng = 27.4,
            addressSnapshot = "123 Kingsway",
            instructions = "Near the cathedral"
        )
        assertEquals(-29.3, snapshot.lat, 0.00001)
        assertEquals(27.4, snapshot.lng, 0.00001)
        assertEquals("123 Kingsway", snapshot.addressSnapshot)
        assertEquals("Near the cathedral", snapshot.instructions)
    }

    @Test
    fun `snapshot converts to GeoPoint`() {
        val snapshot = LocationSnapshot(-29.3, 27.4)
        val geoPoint = snapshot.toGeoPoint()
        assertEquals(snapshot.lat, geoPoint.lat, 0.00001)
        assertEquals(snapshot.lng, geoPoint.lng, 0.00001)
    }

    @Test
    fun `order preserves snapshot immutability`() {
        // 1. Create a shop location
        var shopLocation = GeoPoint(-29.3, 27.4)
        
        // 2. Create an order snapshot from that location
        val order = com.swiftshop.core.model.Order(
            id = "order_1",
            originLocationSnapshot = LocationSnapshot(shopLocation.lat, shopLocation.lng, "Original Shop Address")
        )
        
        // 3. Change the shop's current location (simulate shop moving)
        shopLocation = GeoPoint(-29.5, 27.6)
        
        // 4. Verify the order snapshot remains unchanged
        assertNotNull(order.originLocationSnapshot)
        assertEquals(-29.3, order.originLocationSnapshot!!.lat, 0.00001)
        assertEquals("Original Shop Address", order.originLocationSnapshot!!.addressSnapshot)
    }

    @Test
    fun `firestore order handles missing snapshots for legacy compatibility`() {
        // Simulating a DTO with null snapshots (what Firestore returns for legacy docs)
        val dto = com.swiftshop.data.firebase.FirestoreOrder(
            id = "legacy_1",
            originLocationSnapshot = null,
            destinationLocationSnapshot = null
        )
        
        val domain = dto.toDomain()
        
        assertEquals("legacy_1", domain.id)
        assertNull(domain.originLocationSnapshot)
        assertNull(domain.destinationLocationSnapshot)
    }
}
