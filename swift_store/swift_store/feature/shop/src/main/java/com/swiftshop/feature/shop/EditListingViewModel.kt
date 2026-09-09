package com.swiftshop.feature.shop

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.CommerceRepository
import com.swiftshop.domain.commerce.GetListingUseCase
import com.swiftshop.domain.commerce.UpdateListingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

sealed interface EditListingUiState {
    data object Loading : EditListingUiState
    data object Success : EditListingUiState
    data class Error(val message: String) : EditListingUiState
    data class Content(val listing: Listing) : EditListingUiState
}

@HiltViewModel
class EditListingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val getListing: GetListingUseCase,
    private val updateListing: UpdateListingUseCase,
    private val repository: CommerceRepository
) : ViewModel() {

    private val listingId: String = checkNotNull(savedStateHandle["listingId"])

    private val _uiState = MutableStateFlow<EditListingUiState>(EditListingUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _title = MutableStateFlow("")
    val title = _title.asStateFlow()

    private val _description = MutableStateFlow("")
    val description = _description.asStateFlow()

    private val _category = MutableStateFlow("")
    val category = _category.asStateFlow()

    private val _priceMajor = MutableStateFlow("")
    val priceMajor = _priceMajor.asStateFlow()

    private val _totalQuantity = MutableStateFlow("1")
    val totalQuantity = _totalQuantity.asStateFlow()

    private val _existingImageUrls = MutableStateFlow<List<String>>(emptyList())
    val existingImageUrls = _existingImageUrls.asStateFlow()

    private val _newImageUris = MutableStateFlow<List<Uri>>(emptyList())
    val newImageUris = _newImageUris.asStateFlow()

    private val _listingType = MutableStateFlow(ListingType.BUY)
    val listingType = _listingType.asStateFlow()

    private val _customFields = MutableStateFlow<List<CustomField>>(emptyList())
    val customFields = _customFields.asStateFlow()

    private val _deliveryEstimateDays = MutableStateFlow("0")
    val deliveryEstimateDays = _deliveryEstimateDays.asStateFlow()

    private val _isAvailable = MutableStateFlow(true)
    val isAvailable = _isAvailable.asStateFlow()

    private var originalListing: Listing? = null

    init {
        loadListing()
    }

    private fun loadListing() {
        viewModelScope.launch {
            _uiState.value = EditListingUiState.Loading
            getListing(listingId).fold(
                onSuccess = { listing ->
                    originalListing = listing
                    _title.value = listing.title
                    _description.value = listing.description
                    _category.value = listing.category
                    _priceMajor.value = (listing.price.minorUnits / 100.0).toString()
                    _totalQuantity.value = listing.totalQuantity.toString()
                    _existingImageUrls.value = listing.imageUrls
                    _listingType.value = listing.listingType
                    _customFields.value = listing.customFields
                    _deliveryEstimateDays.value = listing.deliveryEstimateDays.toString()
                    _isAvailable.value = listing.isAvailable
                    _uiState.value = EditListingUiState.Content(listing)
                },
                onFailure = {
                    _uiState.value = EditListingUiState.Error(it.message ?: "Failed to load listing")
                }
            )
        }
    }

    fun onTitleChange(v: String) { _title.value = v }
    fun onDescriptionChange(v: String) { _description.value = v }
    fun onCategoryChange(v: String) { _category.value = v }
    fun onPriceChange(v: String) { _priceMajor.value = v }
    fun onStockChange(v: String) { _totalQuantity.value = v }
    fun onDeliveryEstimateChange(v: String) { _deliveryEstimateDays.value = v }
    fun onAvailableChange(v: Boolean) { _isAvailable.value = v }

    fun addImages(uris: List<Uri>) {
        _newImageUris.value = (_newImageUris.value + uris).distinct()
    }

    fun removeExistingImage(url: String) {
        _existingImageUrls.value = _existingImageUrls.value - url
    }

    fun removeNewImage(uri: Uri) {
        _newImageUris.value = _newImageUris.value - uri
    }

    fun submit() {
        if (_uiState.value is EditListingUiState.Loading) return // Duplicate submit protection

        viewModelScope.launch {

            val user = observeCurrentUser().first()
            if (user == null) {
                _uiState.value = EditListingUiState.Error("User not signed in")
                return@launch
            }

            _uiState.value = EditListingUiState.Loading
            val price = MoneyAmount.fromMajorUnits(_priceMajor.value.toDoubleOrNull() ?: 0.0)
            val total = _totalQuantity.value.toIntOrNull() ?: 0
            val delivery = _deliveryEstimateDays.value.toIntOrNull() ?: 0

            updateListing(
                listingId = listingId,
                title = _title.value,
                description = _description.value,
                category = _category.value,
                price = price,
                totalQuantity = total,
                imageUris = _newImageUris.value,
                existingImageUrls = _existingImageUrls.value,
                sellerId = user.uid,
                listingType = _listingType.value,
                customFields = _customFields.value,
                deliveryEstimateDays = delivery,
                isAvailable = _isAvailable.value
            ).fold(
                onSuccess = {
                    // VISIBILITY GATING: Wait until the update is observable in the user's listings
                    viewModelScope.launch {
                        repository.observeUserListings(user.uid)
                            .filter { listings -> listings.any { it.id == listingId } }
                            .first()
                        
                        _uiState.value = EditListingUiState.Success
                    }
                },
                onFailure = { _uiState.value = EditListingUiState.Error(it.message ?: "Update failed") }
            )


        }
    }
}
