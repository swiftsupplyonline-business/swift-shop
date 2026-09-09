package com.swiftshop.feature.shop

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.CommerceRepository
import com.swiftshop.domain.commerce.CreateListingUseCase
import com.swiftshop.domain.commerce.WorkflowContext
import dagger.hilt.android.lifecycle.HiltViewModel

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

sealed interface CreateListingUiState {
    data object Idle : CreateListingUiState
    data object Loading : CreateListingUiState
    data object Success : CreateListingUiState
    data class Error(val message: String) : CreateListingUiState
}

sealed interface UserShopsState {
    data object Loading : UserShopsState
    data class Success(val shops: List<Shop>) : UserShopsState
    data object Empty : UserShopsState
    data class Error(val message: String) : UserShopsState
}

@HiltViewModel
class CreateListingViewModel @Inject constructor(
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val observeProfile: com.swiftshop.domain.profile.ObserveProfileUseCase,
    private val createListing: CreateListingUseCase,
    private val contextEngine: com.swiftshop.domain.commerce.ContextEngine,
    private val repository: CommerceRepository
) : ViewModel() {

    private val initialType: ListingType = savedStateHandle.get<String>("listingType")?.let {
        runCatching { ListingType.valueOf(it) }.getOrNull()
    } ?: ListingType.PHYSICAL_ITEM

    private val _uiState = MutableStateFlow<CreateListingUiState>(CreateListingUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _canPublish = MutableStateFlow(true)
    val canPublish = _canPublish.asStateFlow()

    private val _listingUsageText = MutableStateFlow("")
    val listingUsageText = _listingUsageText.asStateFlow()

    private val _userShopsState = MutableStateFlow<UserShopsState>(UserShopsState.Loading)
    val userShopsState = _userShopsState.asStateFlow()

    private val _selectedShop = MutableStateFlow<Shop?>(null)
    val selectedShop = _selectedShop.asStateFlow()

    private val requestedShopId: String? = savedStateHandle["shopId"]

    private val _title = MutableStateFlow("")
    val title = _title.asStateFlow()

    private val _description = MutableStateFlow("")
    val description = _description.asStateFlow()

    private val _category = MutableStateFlow("General")
    val category = _category.asStateFlow()

    private val _priceMajor = MutableStateFlow("")
    val priceMajor = _priceMajor.asStateFlow()

    private val _stockQuantity = MutableStateFlow("1")
    val stockQuantity = _stockQuantity.asStateFlow()

    private val _imageUris = MutableStateFlow<List<Uri>>(emptyList())
    val imageUris = _imageUris.asStateFlow()

    private val _isSeeding = MutableStateFlow(false)
    val isSeeding = _isSeeding.asStateFlow()

    private val _listingType = MutableStateFlow(initialType)
    val listingType = _listingType.asStateFlow()

    private val _customFields = MutableStateFlow<List<CustomField>>(emptyList())
    val customFields = _customFields.asStateFlow()

    private val _deliveryEstimateDays = MutableStateFlow("0")
    val deliveryEstimateDays = _deliveryEstimateDays.asStateFlow()

    val showCustomFieldBuilder: StateFlow<Boolean> = _listingType.map { type ->
        type in listOf(ListingType.PLACE_ORDER, ListingType.REGISTER, ListingType.SET_APPOINTMENT, ListingType.SERVICE, ListingType.BOOKABLE_SERVICE, ListingType.PREPARED_FOOD)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val showDeliveryEstimate: StateFlow<Boolean> = _listingType.map { type ->
        type in listOf(ListingType.PRODUCT, ListingType.BUY, ListingType.PLACE_ORDER, ListingType.DELIVER, ListingType.PHYSICAL_ITEM, ListingType.DELIVERY_SERVICE)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _selectedUnit = MutableStateFlow<String?>(null)
    val selectedUnit = _selectedUnit.asStateFlow()

    init {
        viewModelScope.launch {
            contextEngine.getActiveMarketContext().collect { market ->
                if (_selectedUnit.value == null) {
                    _selectedUnit.value = market.defaultUnits.firstOrNull()
                }
            }
        }

        viewModelScope.launch {
            observeCurrentUser()
                .filter { it != null && it.uid.isNotBlank() }
                .map { it!! }
                .flatMapLatest { user ->
                    observeProfile(user.uid)
                        .map { profile -> user to profile }
                        .catch { e -> Timber.e(e, "Error observing profile in CreateListing") }
                }
                .collect { (user, profile) ->
                    _listingUsageText.value = "Unlimited listings"
                    _canPublish.value = true
                }
        }

        viewModelScope.launch {
            observeCurrentUser()
                .filter { it != null && it.uid.isNotBlank() }
                .map { it!! }
                .flatMapLatest { user ->
                    repository.getUserShops(user.uid)
                        .map { shops ->
                            if (shops.isEmpty()) UserShopsState.Empty
                            else UserShopsState.Success(shops)
                        }
                        .catch { e -> 
                            Timber.e(e, "Error observing shops in CreateListing")
                            emit(UserShopsState.Error(e.message ?: "Failed to load shops"))
                        }
                }
                .collect { state ->
                    _userShopsState.value = state
                    if (state is UserShopsState.Success) {
                        val shops = state.shops
                        // Safely handle pre-selection from shopId arg
                        val preselected = if (!requestedShopId.isNullOrBlank()) {
                            shops.find { it.id == requestedShopId }
                        } else null

                        if (_selectedShop.value == null) {
                            if (preselected != null) {
                                onShopSelected(preselected)
                            } else if (shops.isNotEmpty()) {
                                onShopSelected(shops.first())
                            }
                        }
                    }
                }
        }
    }

    fun onShopSelected(shop: Shop) {
        _selectedShop.value = shop
        // Pre-populate category, but don't force it (User can change it)
        if (_category.value == "General" || _category.value.isBlank()) {
            _category.value = shop.category
        }
    }

    fun onTitleChange(value: String) { _title.value = value }
    fun onDescriptionChange(value: String) { _description.value = value }
    fun onCategoryChange(value: String) { 
        _category.value = value 
        applyCategoryDefaults(value)
    }

    private fun applyCategoryDefaults(categoryId: String) {
        viewModelScope.launch {
            val market = contextEngine.getActiveMarketContext().first()
            val schema = market.categories.find { it.id == categoryId || it.label == categoryId } ?: return@launch
            
            // Tier 2: Apply suggested fields as custom fields
            val suggestions = schema.suggestedFields.map { field ->
                CustomField(
                    id = field.id,
                    label = field.label,
                    type = field.type,
                    options = field.options,
                    isRequired = field.isRequired
                )
            }
            if (suggestions.isNotEmpty()) {
                _customFields.value = suggestions
            }
            
            // Suggest units
            if (schema.suggestedUnits.isNotEmpty()) {
                _selectedUnit.value = schema.suggestedUnits.first()
            }
        }
    }

    fun onPriceChange(value: String) { _priceMajor.value = value }
    fun onStockChange(value: String) { _stockQuantity.value = value }
    fun onDeliveryEstimateChange(value: String) { _deliveryEstimateDays.value = value }

    fun onListingTypeChange(type: ListingType) {
        _listingType.value = type
        if (type !in listOf(ListingType.PLACE_ORDER, ListingType.REGISTER, ListingType.SET_APPOINTMENT)) {
            _customFields.value = emptyList()
        }
    }

    fun addImages(uris: List<Uri>) {
        _imageUris.value = (_imageUris.value + uris).distinct()
    }

    fun removeImage(uri: Uri) {
        _imageUris.value = _imageUris.value - uri
    }

    fun addCustomField(type: String = "text") {
        _customFields.value = _customFields.value + CustomField(
            id = UUID.randomUUID().toString(),
            label = "",
            type = type,
            options = emptyList(),
            isRequired = false
        )
    }

    fun updateCustomFieldLabel(id: String, label: String) {
        _customFields.value = _customFields.value.map {
            if (it.id == id) it.copy(label = label) else it
        }
    }

    fun updateCustomFieldType(id: String, type: String) {
        _customFields.value = _customFields.value.map {
            if (it.id == id) it.copy(type = type, options = if (type == "select") listOf("") else emptyList()) else it
        }
    }

    fun updateCustomFieldRequired(id: String, required: Boolean) {
        _customFields.value = _customFields.value.map {
            if (it.id == id) it.copy(isRequired = required) else it
        }
    }

    fun addSelectOption(fieldId: String) {
        _customFields.value = _customFields.value.map { field ->
            if (field.id == fieldId) field.copy(options = field.options + "") else field
        }
    }

    fun updateSelectOption(fieldId: String, index: Int, value: String) {
        _customFields.value = _customFields.value.map { field ->
            if (field.id == fieldId) {
                val newOptions = field.options.toMutableList().also { it[index] = value }
                field.copy(options = newOptions)
            } else field
        }
    }

    fun removeCustomField(id: String) {
        _customFields.value = _customFields.value.filter { it.id != id }
    }

    fun submit() {
        if (_uiState.value is CreateListingUiState.Loading) return // Prevent duplicate

        viewModelScope.launch {
            _uiState.value = CreateListingUiState.Loading
            val user = observeCurrentUser().first()
            val uid = user?.uid
            if (uid == null) {
                _uiState.value = CreateListingUiState.Error("User not signed in")
                return@launch
            }
            val shopId = _selectedShop.value?.id
            if (shopId == null) {
                _uiState.value = CreateListingUiState.Error("Please select a shop")
                return@launch
            }
            val price = MoneyAmount.fromMajorUnits(_priceMajor.value.toDoubleOrNull() ?: 0.0)
            val stock = _stockQuantity.value.toIntOrNull() ?: 0
            val deliveryDays = _deliveryEstimateDays.value.toIntOrNull() ?: 0
            
            repository.createListing(
                Listing(
                    shopId = shopId,
                    sellerId = uid,
                    title = _title.value,
                    description = _description.value,
                    category = _category.value,
                    price = price,
                    totalQuantity = stock,
                    availableQuantity = stock,
                    stockQuantity = stock,
                    listingType = _listingType.value,
                    customFields = _customFields.value,
                    deliveryEstimateDays = deliveryDays,
                    createdAt = System.currentTimeMillis()
                )
            ).onSuccess { newListingId ->
                // VISIBILITY GATING: Wait until the listing is actually observable 
                // in the shop's stream before claiming success.
                repository.getShopListings(shopId, 0, 50)
                    .map { list -> list.any { l -> l.id == newListingId } }
                    .filter { it }
                    .first()

                _uiState.value = CreateListingUiState.Success



                
                // Learn from this successful creation
                viewModelScope.launch {
                    contextEngine.recordUserChoice("category", _category.value, WorkflowContext(categoryId = _category.value))
                    _selectedUnit.value?.let { contextEngine.recordUserChoice("unit", it, WorkflowContext(categoryId = _category.value)) }
                }
            }.onFailure {
                _uiState.value = CreateListingUiState.Error(it.message ?: "Failed to create listing")
            }
        }
    }


    fun seedTestData(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isSeeding.value = true
            val user = observeCurrentUser().first()
            val uid = user?.uid
            if (uid == null) {
                Timber.e("Seed failed: User not signed in")
                _isSeeding.value = false
                return@launch
            }
            val testListing = Listing(
                id = "listing_test_${System.currentTimeMillis()}",
                shopId = "test_shop_1",
                sellerId = uid,
                title = "Verified Test Product ${System.currentTimeMillis()}",
                description = "Seeded for Phase 5 verification.",
                price = MoneyAmount("LSL", 15000),
                listingType = ListingType.BUY,
                isAvailable = true,
                stockQuantity = 5,
                createdAt = System.currentTimeMillis()
            )
            repository.createListing(testListing).onSuccess {
                Timber.d("Seed success: Listing created with ID $it")
                onSuccess()
            }.onFailure {
                Timber.e(it, "Seed failed: Could not create listing")
            }
            _isSeeding.value = false
        }
    }
}
