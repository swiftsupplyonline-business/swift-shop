package com.swiftshop.core.model

import org.junit.Assert.*
import org.junit.Test

class Phase1ContractTest {

    @Test
    fun `ListingType taxonomy mapping`() {
        assertEquals(ListingType.PHYSICAL_ITEM, ListingType.PRODUCT.toCanonical())
        assertEquals(ListingType.BOOKABLE_SERVICE, ListingType.SERVICE.toCanonical())
        assertEquals(ListingType.PHYSICAL_ITEM, ListingType.BUY.toCanonical())
        assertEquals(ListingType.PREPARED_FOOD, ListingType.PREPARED_FOOD.toCanonical())
    }

    @Test
    fun `ListingSnapshot captures transactional state`() {
        val originalPrice = MoneyAmount("LSL", 1000)
        val listing = Listing(
            id = "l1",
            title = "Original Title",
            price = originalPrice,
            listingType = ListingType.PHYSICAL_ITEM
        )

        val snapshot = listing.toSnapshot()

        // Modify original listing
        val modifiedListing = listing.copy(
            title = "New Title",
            price = MoneyAmount("LSL", 2000)
        )

        assertEquals("Original Title", snapshot.title)
        assertEquals(originalPrice, snapshot.price)
        assertNotEquals(modifiedListing.title, snapshot.title)
    }

    @Test
    fun `Order state independent defaults`() {
        val order = Order(id = "o1")
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(PaymentStatus.PENDING, order.paymentStatus)
        assertEquals(FulfillmentStatus.PENDING, order.fulfillmentStatus)
        assertEquals(SettlementStatus.PENDING, order.settlementStatus)
        assertEquals(InventoryStatus.PENDING, order.inventoryStatus)
    }

    @Test
    fun `OrderRole presence check`() {
        val roles = OrderRole.values()
        assertTrue(roles.any { it == OrderRole.REQUESTER })
        assertTrue(roles.any { it == OrderRole.SELLER })
        assertTrue(roles.any { it == OrderRole.ADMIN })
    }
}
