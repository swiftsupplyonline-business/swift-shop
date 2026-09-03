package com.swiftshop.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data object Empty : SearchState
    data class Results(
        val users: List<User> = emptyList(),
        val listings: List<Listing> = emptyList(),
        val shops: List<Shop> = emptyList(),
        val posts: List<FeedPost> = emptyList()
    ) : SearchState
    data class Error(val message: String) : SearchState
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchListings: com.swiftshop.domain.commerce.SearchListingsUseCase
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<SearchState>(SearchState.Idle)
    val results: StateFlow<SearchState> = _results.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Debounce search input by 350ms
        viewModelScope.launch {
            _query
                .debounce(350)
                .distinctUntilChanged()
                .collect { q ->
                    if (q.isBlank()) {
                        _results.value = SearchState.Idle
                    } else {
                        performSearch(q)
                    }
                }
        }
    }

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun retry() {
        val q = _query.value
        if (q.isNotBlank()) performSearch(q)
    }

    fun clearRecentSearches() {
        _recentSearches.value = emptyList()
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _results.value = SearchState.Loading
            searchListings(query).fold(
                onSuccess = { listings ->
                    if (listings.isEmpty()) {
                        _results.value = SearchState.Empty
                    } else {
                        _results.value = SearchState.Results(listings = listings)
                    }
                },
                onFailure = { 
                    _results.value = SearchState.Error(it.message ?: "Search failed")
                }
            )
            
            // Add to recent searches
            _recentSearches.value = (_recentSearches.value + query)
                .distinct()
                .takeLast(10)
        }
    }
}
