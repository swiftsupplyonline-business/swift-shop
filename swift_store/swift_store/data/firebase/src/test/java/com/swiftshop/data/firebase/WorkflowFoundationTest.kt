package com.swiftshop.data.firebase

import com.swiftshop.core.model.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowFoundationTest {

    @Test
    fun `FulfillmentType mapping handles known and legacy values`() {
        // Known values
        assertEquals(FulfillmentType.PICKUP, safeEnumValueOf<FulfillmentType>("PICKUP"))
        assertEquals(FulfillmentType.PHYSICAL_DELIVERY, safeEnumValueOf<FulfillmentType>("PHYSICAL_DELIVERY"))

        // Legacy values
        assertEquals(FulfillmentType.AT_PROVIDER, safeEnumValueOf<FulfillmentType>("SERVICE"))

        // Null and blank
        assertNull(safeEnumValueOf<FulfillmentType>(null))
        assertNull(safeEnumValueOf<FulfillmentType>(""))
        assertNull(safeEnumValueOf<FulfillmentType>("  "))

        // Unknown values
        assertNull(safeEnumValueOf<FulfillmentType>("UNKNOWN_MODE"))
    }

    @Test
    fun `Listing mapping handles duration and fulfillment options`() {
        val dto = FirestoreListing(
            id = "l1",
            durationMinutes = 60,
            fulfillmentOptions = listOf("PICKUP", "AT_PROVIDER")
        )

        val domain = dto.toDomain()
        assertEquals(60, domain.durationMinutes)
        assertEquals(2, domain.fulfillmentOptions.size)
        assertTrue(domain.fulfillmentOptions.contains(FulfillmentType.PICKUP))
        assertTrue(domain.fulfillmentOptions.contains(FulfillmentType.AT_PROVIDER))

        // Backward compatibility: missing fields
        val legacyDto = FirestoreListing(id = "l2") // durationMinutes defaults to 0, fulfillmentOptions to emptyList
        val legacyDomain = legacyDto.toDomain()
        assertEquals(0, legacyDomain.durationMinutes)
        assertTrue(legacyDomain.fulfillmentOptions.isEmpty())
    }

    @Test
    fun `Order mapping round-trip for new fields`() {
        val order = Order(
            id = "o1",
            fulfillmentType = FulfillmentType.AT_CUSTOMER,
            notes = "Ring bell",
            customerResponses = listOf(
                CustomerFieldResponse(fieldId = "f1", label = "Gate Code", value = "1234")
            )
        )

        val map = order.toFirestore()
        assertEquals("AT_CUSTOMER", map["fulfillmentType"])
        assertEquals("Ring bell", map["notes"])
        
        val responsesMap = map["customerResponses"] as List<Map<String, Any>>
        assertEquals(1, responsesMap.size)
        assertEquals("1234", responsesMap[0]["value"])

        // Domain reconstruction
        val dto = FirestoreOrder(
            id = "o1",
            fulfillmentType = "AT_CUSTOMER",
            notes = "Ring bell",
            customerResponses = listOf(
                FirestoreCustomerFieldResponse(fieldId = "f1", label = "Gate Code", value = "1234")
            )
        )
        val reconstructed = dto.toDomain()
        assertEquals(FulfillmentType.AT_CUSTOMER, reconstructed.fulfillmentType)
        assertEquals("Ring bell", reconstructed.notes)
        assertEquals(1, reconstructed.customerResponses.size)
        assertEquals("1234", reconstructed.customerResponses[0].value)
    }

    @Test
    fun `Order mapping backward compatibility for missing fields`() {
        val dto = FirestoreOrder(id = "o_legacy")
        val domain = dto.toDomain()

        assertNull(domain.fulfillmentType)
        assertEquals("", domain.notes)
        assertTrue(domain.customerResponses.isEmpty())
    }

    @Test
    fun `ListingSnapshot mapping round-trip preserves types`() {
        val snapshot = ListingSnapshot(
            listingId = "l1",
            durationMinutes = 30,
            fulfillmentOptions = listOf(FulfillmentType.REMOTE)
        )

        val map = snapshot.toFirestore()
        assertEquals(30, map["durationMinutes"])
        val options = map["fulfillmentOptions"] as List<String>
        assertEquals("REMOTE", options[0])

        val dto = FirestoreListingSnapshot(
            listingId = "l1",
            durationMinutes = 30,
            fulfillmentOptions = listOf("REMOTE")
        )
        val reconstructed = dto.toDomain()
        assertEquals(30, reconstructed.durationMinutes)
        assertEquals(FulfillmentType.REMOTE, reconstructed.fulfillmentOptions[0])
    }

    @Test
    fun `Money parsing handles exact decimals and rejects invalid inputs`() {
        // Valid cases
        assertEquals(10000L, MoneyAmount.fromDecimalString("100").getOrThrow().minorUnits)
        assertEquals(10000L, MoneyAmount.fromDecimalString("100.0").getOrThrow().minorUnits)
        assertEquals(10000L, MoneyAmount.fromDecimalString("100.00").getOrThrow().minorUnits)
        assertEquals(10050L, MoneyAmount.fromDecimalString("100.5").getOrThrow().minorUnits)
        assertEquals(10050L, MoneyAmount.fromDecimalString("100.50").getOrThrow().minorUnits)
        assertEquals(10099L, MoneyAmount.fromDecimalString("100.99").getOrThrow().minorUnits)
        assertEquals(99L, MoneyAmount.fromDecimalString("0.99").getOrThrow().minorUnits)

        // Realistic large value
        assertEquals(99999999L, MoneyAmount.fromDecimalString("999999.99").getOrThrow().minorUnits)

        // Invalid cases - should be Failure
        assertTrue(MoneyAmount.fromDecimalString("abc").isFailure)
        assertTrue(MoneyAmount.fromDecimalString("").isFailure)
        assertTrue(MoneyAmount.fromDecimalString("   ").isFailure)
        assertTrue(MoneyAmount.fromDecimalString(null).isFailure)
        
        // Excess precision - should be Failure (Contract: reject instead of truncate)
        assertTrue(MoneyAmount.fromDecimalString("100.123").isFailure)

        // Negative - should be Failure
        assertTrue(MoneyAmount.fromDecimalString("-100.50").isFailure)
        
        // Zero - should be Failure if contract requires positive (Swift Shop usually does for listings)
        assertTrue(MoneyAmount.fromDecimalString("0").isFailure)
        assertTrue(MoneyAmount.fromDecimalString("0.00").isFailure)

        // Malformed
        assertTrue(MoneyAmount.fromDecimalString("1..5").isFailure)
        assertTrue(MoneyAmount.fromDecimalString("1.5.0").isFailure)
    }

    @Test
    fun `Workflow mapping identifies appointment types correctly`() {
        val mapping = com.swiftshop.domain.commerce.WorkflowMapping
        
        // Appointment based
        assertTrue(mapping.isAppointment(ListingType.SERVICE))
        assertTrue(mapping.isAppointment(ListingType.SET_APPOINTMENT))
        assertTrue(mapping.isAppointment(ListingType.BOOKABLE_SERVICE))
        assertTrue(mapping.isAppointment(OrderType.SERVICE_BOOKING))
        
        // Not appointment based (even if they can have fulfillment locations)
        assertFalse(mapping.isAppointment(ListingType.PHYSICAL_ITEM))
        assertFalse(mapping.isAppointment(ListingType.PREPARED_FOOD))
        assertFalse(mapping.isAppointment(ListingType.DELIVERY_SERVICE))
        assertFalse(mapping.isAppointment(OrderType.PRODUCT_PURCHASE))
        assertFalse(mapping.isAppointment(OrderType.DELIVERY_REQUEST))
    }
}
