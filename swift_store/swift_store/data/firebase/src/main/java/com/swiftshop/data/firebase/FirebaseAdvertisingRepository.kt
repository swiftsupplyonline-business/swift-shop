package com.swiftshop.data.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.swiftshop.core.model.*
import java.util.Date
import com.swiftshop.domain.advertising.AdvertisingRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAdvertisingRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions
) : AdvertisingRepository {

    override suspend fun createCampaign(campaign: AdCampaign, includeAllFeed: Boolean): Result<String> = runCatching {
        val doc = firestore.collection("advertisingCampaigns").document()
        val finalCampaign = campaign.copy(campaignId = doc.id)
        doc.set(finalCampaign.toFirestore(includeAllFeed)).await()
        doc.id
    }

    override suspend fun activateCampaign(campaignId: String): Result<Unit> = runCatching {
        val data = mapOf("campaignId" to campaignId)
        functions.getHttpsCallable("activateCampaign").call(data).await()
    }

    override suspend fun pauseCampaign(campaignId: String): Result<Unit> = runCatching {
        firestore.collection("advertisingCampaigns").document(campaignId)
            .update("status", "PAUSED")
            .await()
    }

    override suspend fun cancelCampaign(campaignId: String): Result<Unit> = runCatching {
        val data = mapOf("campaignId" to campaignId)
        functions.getHttpsCallable("cancelCampaign").call(data).await()
    }

    override fun observeUserCampaigns(userId: String): Flow<List<AdCampaign>> = callbackFlow {
        val subscription = firestore.collection("advertisingCampaigns")
            .whereEqualTo("ownerId", userId)
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.toObjects(FirestoreAdCampaign::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun getCampaignById(campaignId: String): Result<AdCampaign> = runCatching {
        firestore.collection("advertisingCampaigns").document(campaignId).get().await()
            .toObject(FirestoreAdCampaign::class.java)?.toDomain() ?: throw NoSuchElementException("Campaign not found")
    }

    override suspend fun recordImpression(campaignId: String): Result<Unit> = runCatching {
        firestore.collection("advertisingCampaigns").document(campaignId)
            .update("impressions", com.google.firebase.firestore.FieldValue.increment(1))
            .await()
    }

    override suspend fun recordClick(campaignId: String): Result<Unit> = runCatching {
        firestore.collection("advertisingCampaigns").document(campaignId)
            .update("clicks", com.google.firebase.firestore.FieldValue.increment(1))
            .await()
    }

    override suspend fun recordConversion(campaignId: String): Result<Unit> = runCatching {
        firestore.collection("advertisingCampaigns").document(campaignId)
            .update("conversions", com.google.firebase.firestore.FieldValue.increment(1))
            .await()
    }
}

data class FirestoreAdCampaign(
    val campaignId: String = "",
    val ownerId: String = "",
    val contentId: String = "",
    val contentType: String = "LISTING",
    val budgetMinorUnits: Long = 0L,
    val budgetCurrency: String = "LSL",
    val durationWeeks: Int = 1,
    val status: String = "DRAFT",
    val impressions: Long = 0L,
    val clicks: Long = 0L,
    val conversions: Long = 0L,
    val createdAt: Date = Date(0)
) {
    fun toDomain() = AdCampaign(campaignId, ownerId, contentId, runCatching { CampaignContentType.valueOf(contentType) }.getOrDefault(CampaignContentType.LISTING), MoneyAmount(budgetCurrency, budgetMinorUnits), durationWeeks, runCatching { CampaignStatus.valueOf(status) }.getOrDefault(CampaignStatus.DRAFT), AdTargeting(), impressions, clicks, conversions, createdAt.time)
}

fun AdCampaign.toFirestore(includeAllFeed: Boolean) = mapOf(
    "campaignId" to campaignId, "ownerId" to ownerId, "contentId" to contentId,
    "contentType" to contentType.name, "budgetMinorUnits" to budget.minorUnits,
    "budgetCurrency" to budget.currency, "durationWeeks" to durationWeeks,
    "status" to status.name, "impressions" to impressions, "clicks" to clicks,
    "conversions" to conversions, "includeAllFeed" to includeAllFeed, "createdAt" to createdAt
)
