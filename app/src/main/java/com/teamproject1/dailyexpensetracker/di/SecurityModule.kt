package com.teamproject1.dailyexpensetracker.di

import android.content.Context
import com.teamproject1.dailyexpensetracker.core.security.PinManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun providePinManager(@ApplicationContext context: Context): PinManager =
        PinManager(context)
}
