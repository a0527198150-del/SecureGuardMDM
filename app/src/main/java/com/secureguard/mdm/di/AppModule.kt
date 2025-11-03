package com.secureguard.mdm.di

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.secureguard.mdm.boot.impl.NetfreeWatchdogBootTask
import com.secureguard.mdm.boot.impl.ShowToastOnBootTask
import com.secureguard.mdm.data.db.AppDatabase
import com.secureguard.mdm.data.db.BlockedAppCacheDao
import com.secureguard.mdm.data.repository.SettingsRepository
import com.secureguard.mdm.data.repository.SettingsRepositoryImpl
import com.secureguard.mdm.kiosk.manager.KioskManager
import com.secureguard.mdm.kiosk.model.KioskApp
import com.secureguard.mdm.kiosk.model.KioskFolder
import com.secureguard.mdm.kiosk.model.KioskItem
import com.secureguard.mdm.utils.RuntimeTypeAdapterFactory
import com.secureguard.mdm.utils.SecureUpdateHelper
import com.secureguard.mdm.utils.update.UpdateManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "secure_guard_database"
        ).build()
    }

    @Provides
    @Singleton
    fun provideBlockedAppCacheDao(appDatabase: AppDatabase): BlockedAppCacheDao {
        return appDatabase.blockedAppCacheDao()
    }

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("secure_guard_prefs", Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideDevicePolicyManager(@ApplicationContext context: Context): DevicePolicyManager {
        return context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository {
        return impl
    }

    @Provides
    @Singleton
    fun provideSecureUpdateHelper(@ApplicationContext context: Context): SecureUpdateHelper {
        return SecureUpdateHelper(context)
    }

    @Provides
    @Singleton
    fun provideUpdateManager(@ApplicationContext context: Context, secureUpdateHelper: SecureUpdateHelper): UpdateManager {
        return UpdateManager(context, secureUpdateHelper)
    }

    @Provides
    @Singleton
    fun provideShowToastOnBootTask(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository
    ): ShowToastOnBootTask {
        return ShowToastOnBootTask(context, settingsRepository)
    }

    @Provides
    @Singleton
    fun provideNetfreeWatchdogBootTask(
        @ApplicationContext context: Context
    ): NetfreeWatchdogBootTask {
        return NetfreeWatchdogBootTask(context)
    }

    @Provides
    @Singleton
    fun provideKioskManager(
        @ApplicationContext context: Context,
        dpm: DevicePolicyManager
    ): KioskManager {
        return KioskManager(context, dpm)
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        val typeFactory = RuntimeTypeAdapterFactory
            .of(KioskItem::class.java, "type")
            .registerSubtype(KioskApp::class.java, "app")
            .registerSubtype(KioskFolder::class.java, "folder")

        return GsonBuilder()
            .registerTypeAdapterFactory(typeFactory)
            .create()
    }
}