package com.swiftshop.data.firebase

import com.swiftshop.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ArchitectureMapperTest {

    @Test
    fun `Order toFirestore preserves architectural groups`() {
        val snapshot = ListingSnapshot(
            listingId = "l1",
            title = "Product",
            price = MoneyAmount("LSL", 1000)
        )
        val order = Order(
            id = "o1",
            type = OrderType.PRODUCT_PURCHASE,
            listingSnapshot = snapshot,
            participants = mapOf(OrderRole.REQUESTER.name to "u1", OrderRole.SELLER.name to "u2"),
            payload = OrderPayload.ProductPurchase(quantity = 2)
        )

        val map = order.toFirestore()

        assertEquals("PRODUCT_PURCHASE", map["type"])
        assertNotNull(map["listingSnapshot"])
        val partMap = map["participants"] as Map<*, *>
        assertEquals("u1", partMap["REQUESTER"])
        
        // Payload serialization check
        val payloadMap = map["payload"] as Map<*, *>
        assertEquals(2, payloadMap["quantity"])
    }

    @Test
    fun `Listing toSnapshot captures transactional fields`() {
        val listing = Listing(
            id = "l1",
            title = "Phone",
            description = "Good phone",
            price = MoneyAmount("LSL", 5000),
            listingType = ListingType.PHYSICAL_ITEM,
            category = "Electronics"
        )

        val snapshot = listing.toSnapshot()

        assertEquals(listing.id, snapshot.listingId)
        assertEquals(listing.title, snapshot.title)
        assertEquals(listing.price, snapshot.price)
        assertEquals(listing.listingType.name, snapshot.listingType)
    }

    @Test
    fun `Legacy Order mapping defaults`() {
        val legacyDto = FirestoreOrder(
            id = "o_legacy",
            buyerId = "buyer_1",
            sellerId = "seller_1",
            status = "CONFIRMED",
            subtotalMinorUnits = 1000
        )

        val domain = legacyDto.toDomain()

        assertEquals(OrderType.LEGACY, domain.type)
        assertEquals("buyer_1", domain.participants[OrderRole.REQUESTER.name])
        assertEquals("seller_1", domain.participants[OrderRole.SELLER.name])
        assertEquals(PaymentStatus.PAID, domain.paymentStatus)
        assertEquals(InventoryStatus.COMMITTED, domain.inventoryStatus)
    }

    @Test
    fun `Scenario D - Snapshot immutability check`() {
        // T1: Listing created
        val listingT1 = Listing(
            id = "samsung_a15",
            title = "Samsung A15",
            description = "Original description",
            price = MoneyAmount("LSL", 2499),
            listingType = ListingType.PHYSICAL_ITEM,
            category = "Phones"
        )

        // Order created with snapshot T1
        val order = Order(
            id = "order_1",
            sourceListingId = listingT1.id,
            listingSnapshot = listingT1.toSnapshot()
        )

        // T2: Listing edited by seller
        val listingT2 = listingT1.copy(
            title = "Samsung A15 (Clearance)",
            description = "New description",
            price = MoneyAmount("LSL", 2299),
            category = "Clearance"
        )

        // Verify Order snapshot still contains T1 data
        val snapshot = order.listingSnapshot!!
        assertEquals("Samsung A15", snapshot.title)
        assertEquals("Original description", snapshot.description)
        assertEquals(MoneyAmount("LSL", 2499), snapshot.price)
        assertEquals("Phones", snapshot.category)
        
        // Ensure it doesn't match T2
        assertNotEquals(listingT2.title, snapshot.title)
        assertNotEquals(listingT2.price, snapshot.price)
    }

    @Test
    fun `Polymorphic payload round-trip verification`() {
        val payloads = listOf(
            OrderPayload.ProductPurchase(variantId = "v1", quantity = 3),
            OrderPayload.FoodOrder(
                items = listOf(FoodOrderItem(id = "f1", title = "Burger", quantity = 1, addOns = listOf("Cheese")))
            ),
            OrderPayload.ServiceBooking(serviceId = "s1", requestedDate = "2024-09-08"),
            OrderPayload.BulkPurchase(quantity = 100.0, unitOfMeasure = "kg"),
            OrderPayload.DeliveryRequest(packageDescription = "Small box", recipientName = "John")
        )

        payloads.forEach { original ->
            val order = Order(id = "test", type = when(original) {
                is OrderPayload.ProductPurchase -> OrderType.PRODUCT_PURCHASE
                is OrderPayload.FoodOrder -> OrderType.FOOD_ORDER
                is OrderPayload.ServiceBooking -> OrderType.SERVICE_BOOKING
                is OrderPayload.BulkPurchase -> OrderType.BULK_PURCHASE
                is OrderPayload.DeliveryRequest -> OrderType.DELIVERY_REQUEST
            }, payload = original)

            val firestoreMap = order.toFirestore()
            val payloadMap = firestoreMap["payload"] as Map<String, Any>
            
            // Reconstruct DTO
            val dto = FirestoreOrder(
                type = order.type.name,
                payload = payloadMap
            )
            
            val domain = dto.toDomain()
            assertEquals("Round-trip failed for ${original.javaClass.simpleName}", original, domain.payload)
        }
    }
}

