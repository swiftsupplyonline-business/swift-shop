package com.swiftshop.core.media.di

import com.swiftshop.core.media.FirebaseMediaUploader
import com.swiftshop.core.media.MediaUploader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {

    @Binds
    @Singleton
    abstract fun bindMediaUploader(impl: FirebaseMediaUploader): MediaUploader
}
