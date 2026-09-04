package com.swiftshop.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.persistentCacheSettings
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.swiftshop.BuildConfig
import com.swiftshop.core.network.PaymentGateway
import com.swiftshop.data.firebase.*
import com.swiftshop.data.remote.di.BackendGateway
import com.swiftshop.data.remote.di.MockGateway
import com.swiftshop.data.repositories.OfflineFirstCommerceRepository
import com.swiftshop.domain.auth.*
import com.swiftshop.domain.commerce.*
import com.swiftshop.domain.profile.*
import com.swiftshop.domain.wallet.*
import com.swiftshop.domain.feed.*
import com.swiftshop.domain.delivery.*
import com.swiftshop.domain.advertising.*
import com.swiftshop.domain.messaging.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore {
        val db = FirebaseFirestore.getInstance()
        db.firestoreSettings = firestoreSettings {
            // Enable offline persistence with 100MB cache
            setLocalCacheSettings(persistentCacheSettings {
                setSizeBytes(100 * 1024 * 1024L)
            })
        }
        return db
    }

    @Provides
    @Singleton
    fun provideFirebaseStorage(): FirebaseStorage = FirebaseStorage.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseFunctions(): FirebaseFunctions = FirebaseFunctions.getInstance()
}

@Module
@InstallIn(SingletonComponent::class)
object PaymentModule {

    /**
     * The one place BuildConfig.FLAVOR is trustworthy for this decision â€”
     * this is :app's own BuildConfig, matching whichever product flavor is
     * actually being assembled. dev builds get the in-memory mock so
     * wallet/checkout flows can be exercised without a live backend;
     * staging/production route real HTTP calls through SwiftBackendApi.
     */
    @Provides
    @Singleton
    fun providePaymentGateway(
        @MockGateway mock: PaymentGateway,
        @BackendGateway backend: PaymentGateway
    ): PaymentGateway = if (BuildConfig.FLAVOR == "dev") mock else backend
}

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun provideAuthRepository(impl: FirebaseAuthRepository): AuthRepository = impl

    @Provides
    fun provideSignInUseCase(repo: AuthRepository) = SignInWithEmailUseCase(repo)

    @Provides
    fun provideSignUpUseCase(repo: AuthRepository) = SignUpUseCase(repo)

    @Provides
    fun provideSignOutUseCase(repo: AuthRepository) = SignOutUseCase(repo)

    @Provides
    fun provideObserveCurrentUserUseCase(repo: AuthRepository) = ObserveCurrentUserUseCase(repo)
}

@Module
@InstallIn(SingletonComponent::class)
object ProfileModule {

    @Provides
    @Singleton
    fun provideProfileRepository(impl: FirebaseProfileRepository): ProfileRepository = impl

    @Provides
    fun provideObserveProfileUseCase(repo: ProfileRepository) = ObserveProfileUseCase(repo)

    @Provides
    fun provideUpdateProfileUseCase(repo: ProfileRepository) = UpdateProfileUseCase(repo)

    @Provides
    fun provideFollowUserUseCase(repo: ProfileRepository) = FollowUserUseCase(repo)

    @Provides
    fun provideUnfollowUserUseCase(repo: ProfileRepository) = UnfollowUserUseCase(repo)

    @Provides @Singleton
    fun provideIsFollowingUseCase(repo: ProfileRepository) = IsFollowingUseCase(repo)
}

@Module
@InstallIn(SingletonComponent::class)
object WalletModule {

    @Provides
    @Singleton
    fun provideWalletRepository(impl: FirebaseWalletRepository): WalletRepository = impl

    @Provides
    @Singleton
    fun provideLedgerRepository(impl: FirebaseLedgerRepository): LedgerRepository = impl

    @Provides
    fun provideObserveWalletUseCase(repo: WalletRepository) = ObserveWalletUseCase(repo)

    @Provides
    fun provideObserveTransactionsUseCase(repo: WalletRepository) = ObserveTransactionsUseCase(repo)

    @Provides
    fun provideInitiateWithdrawalUseCase(repo: WalletRepository) = InitiateWithdrawalUseCase(repo)

    @Provides
    fun provideInitiateP2PTransferUseCase(repo: WalletRepository) = InitiateP2PTransferUseCase(repo)
}

@Module
@InstallIn(SingletonComponent::class)
object CommerceModule {

    @Provides
    @Singleton
    fun provideCommerceRepository(impl: OfflineFirstCommerceRepository): CommerceRepository = impl

    @Provides
    fun provideCanCreateShopUseCase() = CanCreateShopUseCase()

    @Provides
    fun provideGetShopUseCase(repo: CommerceRepository) = GetShopUseCase(repo)

    @Provides
    fun provideCreateShopUseCase(repo: CommerceRepository) = CreateShopUseCase(repo)

    @Provides
    fun provideUpdateShopUseCase(repo: CommerceRepository) = UpdateShopUseCase(repo)

    @Provides
    fun provideGetShopListingsUseCase(repo: CommerceRepository) = GetShopListingsUseCase(repo)

    @Provides
    fun provideGetListingUseCase(repo: CommerceRepository) = GetListingUseCase(repo)

    @Provides
    fun provideGetUserListingsUseCase(repo: CommerceRepository) = GetUserListingsUseCase(repo)

    @Provides
    fun provideSearchListingsUseCase(repo: CommerceRepository) = SearchListingsUseCase(repo)

    @Provides
    fun provideObserveCartUseCase(repo: CommerceRepository) = ObserveCartUseCase(repo)

    @Provides
    fun provideAddToCartUseCase(repo: CommerceRepository) = AddToCartUseCase(repo)

    @Provides
    fun provideRemoveFromCartUseCase(repo: CommerceRepository) = RemoveFromCartUseCase(repo)

    @Provides
    fun provideClearCartUseCase(repo: CommerceRepository) = ClearCartUseCase(repo)

    @Provides
    fun provideCalculateOrderFeesUseCase(repo: CommerceRepository) = CalculateOrderFeesUseCase(repo)

    @Provides
    fun providePlaceOrderUseCase(repo: CommerceRepository) = PlaceOrderUseCase(repo)

    @Provides
    fun provideVerifyMopayPaymentUseCase(repo: CommerceRepository) = VerifyMopayPaymentUseCase(repo)

    @Provides
    fun provideGetOrderUseCase(repo: CommerceRepository) = GetOrderUseCase(repo)

    @Provides
    fun provideInitiateSubscriptionUseCase(repo: CommerceRepository) = InitiateSubscriptionUseCase(repo)

    @Provides
    fun provideCreateListingUseCase(repo: CommerceRepository, media: com.swiftshop.core.media.MediaUploader) = 
        CreateListingUseCase(repo, media)

    @Provides
    @Singleton
    fun provideGetDeliveryListingsUseCase(repo: CommerceRepository) = GetDeliveryListingsUseCase(repo)

    @Provides
    fun provideObserveAvailableSlotsUseCase(repo: AvailabilityRepository) = ObserveAvailableSlotsUseCase(repo)

    @Provides
    fun provideInitiateBookingUseCase(repo: CommerceRepository, observeUser: ObserveCurrentUserUseCase) =
        InitiateBookingUseCase(repo, observeUser)

    @Provides
    @Singleton
    fun provideAvailabilityRepository(impl: FirebaseAvailabilityRepository): AvailabilityRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object SocialModule {

    @Provides
    @Singleton
    fun provideFeedRepository(impl: FirebaseFeedRepository): FeedRepository = impl

    @Provides
    fun provideGetShopFeedUseCase(repo: FeedRepository) = GetShopFeedUseCase(repo)

    @Provides
    fun provideGetPostFeedUseCase(repo: FeedRepository) = GetPostFeedUseCase(repo)

    @Provides
    fun provideGetPostUseCase(repo: FeedRepository) = GetPostUseCase(repo)

    @Provides
    fun provideGetReelFeedUseCase(repo: FeedRepository) = GetReelFeedUseCase(repo)

    @Provides
    fun provideGetUserPostsUseCase(repo: FeedRepository) = GetUserPostsUseCase(repo)

    @Provides
    fun provideGetUserReelsUseCase(repo: FeedRepository) = GetUserReelsUseCase(repo)

    @Provides
    fun provideCreatePostUseCase(
        repo: FeedRepository, 
        media: com.swiftshop.core.media.MediaUploader,
        reelUploadManager: com.swiftshop.core.media.ReelUploadManager
    ) = CreatePostUseCase(repo, media, reelUploadManager)

    @Provides
    fun provideLikePostUseCase(repo: FeedRepository) = LikePostUseCase(repo)

    @Provides
    fun provideToggleBookmarkUseCase(repo: FeedRepository) = ToggleBookmarkUseCase(repo)

    @Provides
    fun provideObserveBookmarkedIdsUseCase(repo: FeedRepository) = ObserveBookmarkedIdsUseCase(repo)

    @Provides
    fun provideGetBookmarksUseCase(repo: FeedRepository) = GetBookmarksUseCase(repo)

    @Provides
    @Singleton
    fun provideCommentRepository(firestore: com.google.firebase.firestore.FirebaseFirestore): CommentRepository =
        com.swiftshop.data.firebase.FirebaseCommentRepository(firestore)

    @Provides
    fun provideGetCommentsUseCase(repo: CommentRepository) = GetCommentsUseCase(repo)

    @Provides
    fun provideGetRepliesUseCase(repo: CommentRepository) = GetRepliesUseCase(repo)

    @Provides
    fun provideAddCommentUseCase(repo: CommentRepository) = AddCommentUseCase(repo)

    @Provides
    fun provideDeleteCommentUseCase(repo: CommentRepository) = DeleteCommentUseCase(repo)
}

@Module
@InstallIn(SingletonComponent::class)
object LogisticsModule {
    @Provides
    @Singleton
    fun provideDeliveryRepository(impl: FirebaseDeliveryRepository): DeliveryRepository = impl

    @Provides
    fun provideObserveDeliveryRouteUseCase(repo: DeliveryRepository) = ObserveDeliveryRouteUseCase(repo)

    @Provides
    fun provideRequestDeliveryUseCase(repo: DeliveryRepository) = RequestDeliveryUseCase(repo)
}

@Module
@InstallIn(SingletonComponent::class)
object AdvertisingModule {
    @Provides
    @Singleton
    fun provideAdvertisingRepository(impl: FirebaseAdvertisingRepository): AdvertisingRepository = impl

    @Provides
    fun provideObserveUserCampaignsUseCase(repo: AdvertisingRepository) = ObserveUserCampaignsUseCase(repo)

    @Provides
    fun provideCreateCampaignUseCase(repo: AdvertisingRepository) = CreateCampaignUseCase(repo)
}

@Module
@InstallIn(SingletonComponent::class)
object MessagingModule {
    @Provides
    @Singleton
    fun provideMessagingRepository(impl: FirebaseMessagingRepository): MessagingRepository = impl

    @Provides
    fun provideObserveConversationsUseCase(repo: MessagingRepository) = ObserveConversationsUseCase(repo)

    @Provides
    fun provideObserveMessagesUseCase(repo: MessagingRepository) = ObserveMessagesUseCase(repo)

    @Provides
    fun provideSendMessageUseCase(repo: MessagingRepository) = SendMessageUseCase(repo)
}

