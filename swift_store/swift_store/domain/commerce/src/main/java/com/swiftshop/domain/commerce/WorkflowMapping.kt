package com.swiftshop.domain.commerce

import com.swiftshop.core.model.ListingType
import com.swiftshop.core.model.OrderType

/**
 * Centralized mapping source for Swift Shop Android workflow paths.
 * Reconciles ListingType to canonical Workflow/Order semantics.
 */
object WorkflowMapping {

    fun getOrderType(listingType: ListingType): OrderType = when (listingType) {
        ListingType.PRODUCT, 
        ListingType.BUY, 
        ListingType.PHYSICAL_ITEM -> OrderType.PRODUCT_PURCHASE

        ListingType.SERVICE, 
        ListingType.BOOKABLE_SERVICE, 
        ListingType.SET_APPOINTMENT -> OrderType.SERVICE_BOOKING

        ListingType.PREPARED_FOOD -> OrderType.FOOD_ORDER
        
        ListingType.BULK_SUPPLY -> OrderType.BULK_PURCHASE
        
        ListingType.DELIVER, 
        ListingType.DELIVERY_SERVICE -> OrderType.DELIVERY_REQUEST
        
        else -> OrderType.LEGACY
    }

    /**
     * Determines if a listing type supports seller-defined custom fields.
     */
    fun supportsCustomFields(type: ListingType): Boolean = type in listOf(
        ListingType.PLACE_ORDER,
        ListingType.REGISTER,
        ListingType.SET_APPOINTMENT,
        ListingType.SERVICE,
        ListingType.BOOKABLE_SERVICE,
        ListingType.PREPARED_FOOD
    )

    /**
     * Determines if a listing type supports/requires delivery estimates.
     */
    fun supportsDeliveryEstimate(type: ListingType): Boolean = type in listOf(
        ListingType.PRODUCT,
        ListingType.BUY,
        ListingType.PLACE_ORDER,
        ListingType.DELIVER,
        ListingType.PHYSICAL_ITEM,
        ListingType.DELIVERY_SERVICE
    )

    /**
     * Determines if a listing type supports/requires duration metadata (minutes).
     */
    fun supportsDuration(type: ListingType): Boolean = type in listOf(
        ListingType.SERVICE,
        ListingType.BOOKABLE_SERVICE,
        ListingType.SET_APPOINTMENT
    )

    /**
     * Determines if a fulfillment type represents an appointment-based service.
     */
    fun isAppointment(fulfillmentType: com.swiftshop.core.model.FulfillmentType?): Boolean = fulfillmentType in listOf(
        com.swiftshop.core.model.FulfillmentType.AT_PROVIDER,
        com.swiftshop.core.model.FulfillmentType.AT_CUSTOMER,
        com.swiftshop.core.model.FulfillmentType.REMOTE
    )

    fun getDisplayName(type: ListingType): String = when (type) {
        ListingType.PRODUCT         -> "Physical Product"
        ListingType.SERVICE         -> "Service"
        ListingType.BUY             -> "Buy / Purchase"
        ListingType.MAKE_PAYMENT    -> "Make a Payment"
        ListingType.SET_APPOINTMENT -> "Book Appointment"
        ListingType.PLACE_ORDER     -> "Place an Order (Custom Form)"
        ListingType.REGISTER        -> "Register / Sign Up"
        ListingType.DELIVER         -> "Request Delivery"
        ListingType.TAKE_ME_THERE   -> "Navigation / Directions"
        ListingType.PHYSICAL_ITEM    -> "Physical Item"
        ListingType.PREPARED_FOOD    -> "Prepared Food"
        ListingType.BOOKABLE_SERVICE -> "Bookable Service"
        ListingType.BULK_SUPPLY      -> "Bulk Supply"
        ListingType.DELIVERY_SERVICE -> "Delivery Service"
    }

    fun getHelpText(type: ListingType): String = when (type) {
        ListingType.PRODUCT         -> "Standard physical goods with inventory tracking."
        ListingType.SERVICE         -> "A service offering with a booking/contact form."
        ListingType.BUY             -> "Standard product purchase with quantity and cart."
        ListingType.MAKE_PAYMENT    -> "Buyer enters an amount and pays via Mopay."
        ListingType.SET_APPOINTMENT -> "Buyer selects a date/time slot to book."
        ListingType.PLACE_ORDER     -> "Buyer fills a custom form you define before ordering."
        ListingType.REGISTER        -> "Buyer submits a registration form (events, courses, etc.)."
        ListingType.DELIVER         -> "Buyer requests a delivery pickup/dropoff."
        ListingType.TAKE_ME_THERE   -> "Shows directions to your physical location."
        ListingType.PHYSICAL_ITEM    -> "Standard physical goods."
        ListingType.PREPARED_FOOD    -> "Food items for order."
        ListingType.BOOKABLE_SERVICE -> "Services that require booking."
        ListingType.BULK_SUPPLY      -> "Wholesale or bulk supplies."
        ListingType.DELIVERY_SERVICE -> "Courier or transport services."
    }

    /**
     * Determines which listing types should be exposed for NEW creation by a seller.
     * Filters out legacy/internal types.
     */
    fun getCreatableTypes(): List<ListingType> = listOf(
        ListingType.PHYSICAL_ITEM,
        ListingType.BOOKABLE_SERVICE,
        ListingType.PREPARED_FOOD,
        ListingType.BULK_SUPPLY,
        ListingType.DELIVERY_SERVICE
    )

    enum class WorkflowCategory { RETAIL, SERVICE, DELIVERY, FORM, PAYMENT, NAVIGATION }

    fun getWorkflowCategory(type: ListingType): WorkflowCategory = when (type) {
        ListingType.PRODUCT, 
        ListingType.BUY, 
        ListingType.PHYSICAL_ITEM, 
        ListingType.PREPARED_FOOD, 
        ListingType.BULK_SUPPLY -> WorkflowCategory.RETAIL
        
        ListingType.SERVICE, 
        ListingType.BOOKABLE_SERVICE, 
        ListingType.SET_APPOINTMENT -> WorkflowCategory.SERVICE
        
        ListingType.DELIVER, 
        ListingType.DELIVERY_SERVICE -> WorkflowCategory.DELIVERY
        
        ListingType.PLACE_ORDER, 
        ListingType.REGISTER -> WorkflowCategory.FORM
        
        ListingType.MAKE_PAYMENT -> WorkflowCategory.PAYMENT
        
        ListingType.TAKE_ME_THERE -> WorkflowCategory.NAVIGATION
    }

    /**
     * Determines if an order type requires physical delivery information.
     */
    fun needsDeliveryInfo(orderType: OrderType): Boolean = orderType in listOf(
        OrderType.PRODUCT_PURCHASE,
        OrderType.FOOD_ORDER,
        OrderType.DELIVERY_REQUEST
    )
}
