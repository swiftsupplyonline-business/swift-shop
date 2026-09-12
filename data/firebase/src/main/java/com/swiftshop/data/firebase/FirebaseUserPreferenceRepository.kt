package com.swiftshop.data.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.swiftshop.domain.profile.UserPreferenceRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseUserPreferenceRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) : UserPreferenceRepository {

    override fun getPreference(uid: String, key: String): Flow<Any?> {
        return getAllPreferences(uid).map { it[key] }
    }

    override suspend fun setPreference(uid: String, key: String, value: Any) {
        firestore.collection("preferences").document(uid)
            .set(mapOf(key to value), com.google.firebase.firestore.SetOptions.merge())
            .await()
    }

    override fun getAllPreferences(uid: String): Flow<Map<String, Any>> = callbackFlow {
        val subscription = firestore.collection("preferences").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyMap())
                    return@addSnapshotListener
                }
                trySend(snapshot?.data ?: emptyMap())
            }
        awaitClose { subscription.remove() }
    }
}
