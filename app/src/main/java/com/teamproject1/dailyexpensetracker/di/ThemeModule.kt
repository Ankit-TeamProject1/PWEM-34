package com.teamproject1.dailyexpensetracker.di

import android.content.Context
import com.teamproject1.dailyexpensetracker.core.theme.ThemePreferenceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ThemeModule {

    @Provides
    @Singleton
    fun provideThemePreferenceManager(@ApplicationContext context: Context): ThemePreferenceManager =
        ThemePreferenceManager(context)
}
