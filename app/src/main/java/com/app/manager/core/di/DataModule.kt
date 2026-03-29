package com.app.manager.core.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.app.manager.core.common.StringProvider
import com.app.manager.core.common.StringProviderImpl
import com.app.manager.data.local.database.DownloadStateDao
import com.app.manager.data.local.database.RevancedDatabase
import com.app.manager.data.repository.AppRepositoryImpl
import com.app.manager.domain.repository.AppRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing concrete dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideRevancedDatabase(@ApplicationContext context: Context): RevancedDatabase {
        return Room.databaseBuilder(
            context,
            RevancedDatabase::class.java,
            RevancedDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
    
    @Provides
    fun provideDownloadStateDao(database: RevancedDatabase): DownloadStateDao {
        return database.downloadStateDao()
    }
}

/**
 * Hilt module for binding abstract dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModule {

    @Binds
    @Singleton
    abstract fun bindAppRepository(
        appRepositoryImpl: AppRepositoryImpl
    ): AppRepository

    @Binds
    @Singleton
    abstract fun bindStringProvider(
        stringProviderImpl: StringProviderImpl
    ): StringProvider
} 

