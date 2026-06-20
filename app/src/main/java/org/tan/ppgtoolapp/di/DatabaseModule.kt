package org.tan.ppgtoolapp.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.tan.ppgtoolapp.data.local.AppDatabase
import org.tan.ppgtoolapp.data.local.FileMetadataDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "ppg_monitor.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideFileMetadataDao(database: AppDatabase): FileMetadataDao {
        return database.fileMetadataDao()
    }
}
