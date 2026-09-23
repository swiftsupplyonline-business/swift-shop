package com.swiftshop.core.model

import org.junit.Assert.*
import org.junit.Test

class Phase1ContractTest {

    @Test
    fun `ListingType existence check`() {
        // Verify current taxonomy exists
        assertTrue(ListingType.values().contains(ListingType.BUY))
        assertTrue(ListingType.values().contains(ListingType.DELIVER))
    }

    @Test
    fun `Listing state captures transactional state`() {
        val originalPrice = MoneyAmount("LSL", 1000)
        val listing = Listing(
            id = "l1",
            title = "Original Title",
            price = originalPrice,
            listingType = ListingType.BUY
        )

        // Modify original listing
        val modifiedListing = listing.copy(
            title = "New Title",
            price = MoneyAmount("LSL", 2000)
        )

        assertEquals("Original Title", listing.title)
        assertEquals(originalPrice, listing.price)
        assertNotEquals(modifiedListing.title, listing.title)
    }

    @Test
    fun `Order state independent defaults`() {
        val order = Order(id = "o1")
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(SettlementStatus.PENDING, order.settlementStatus)
        assertEquals(InventoryStatus.PENDING, order.inventoryStatus)
    }
}
