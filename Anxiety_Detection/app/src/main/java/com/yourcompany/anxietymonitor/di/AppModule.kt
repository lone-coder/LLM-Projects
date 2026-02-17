package com.yourcompany.anxietymonitor.di

import android.content.Context
import androidx.room.Room
import com.yourcompany.anxietymonitor.data.database.AnxietyDatabase
import com.yourcompany.anxietymonitor.data.repository.AndroidDataRepository
import com.yourcompany.anxietymonitor.data.repository.DataRepository
import com.yourcompany.anxietymonitor.data.health.AndroidHealthConnectDataSource
import com.yourcompany.anxietymonitor.data.health.AndroidSamsungSensorDataSource
import com.yourcompany.anxietymonitor.data.health.HybridBiometricDataSource
import com.yourcompany.anxietymonitor.data.health.HistoricalDataLoader
import com.yourcompany.anxietymonitor.service.WearableDataSyncService
import com.yourcompany.anxietymonitor.domain.interfaces.BiometricDataSource
import com.yourcompany.anxietymonitor.domain.interfaces.CognitiveAnalyzer
import com.yourcompany.anxietymonitor.domain.engine.AnxietyDetectionEngine
import com.yourcompany.anxietymonitor.domain.engine.BaselineEngine
import com.yourcompany.anxietymonitor.ml.AndroidTensorFlowLiteModel
import com.yourcompany.anxietymonitor.ml.FeatureEngineer
import com.yourcompany.anxietymonitor.ml.ModelValidationUtils
import com.yourcompany.anxietymonitor.ml.PersonalizedThresholdManager
import com.yourcompany.anxietymonitor.ai.AndroidCognitiveAnalyzer
import com.yourcompany.anxietymonitor.service.AnxietyMonitoringService
import com.yourcompany.anxietymonitor.ai.ModelManager
import com.yourcompany.anxietymonitor.utils.FirstRunHandler
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
    fun provideAnxietyDatabase(@ApplicationContext context: Context): AnxietyDatabase {
        return Room.databaseBuilder(
            context,
            AnxietyDatabase::class.java,
            "anxiety_database"
        ).build()
    }

    @Provides
    @Singleton
    fun provideModelManager(@ApplicationContext context: Context): ModelManager =
        ModelManager(context)

    @Provides
    fun provideBiometricDao(database: AnxietyDatabase) = database.biometricDao()

    @Provides
    fun provideAnxietyEventDao(database: AnxietyDatabase) = database.anxietyEventDao()

    @Provides
    fun provideUserFeedbackDao(database: AnxietyDatabase) = database.userFeedbackDao()

    // NOTE: HiltWorkerFactory removed until WorkManager is properly implemented
    // The "never used" warnings below are normal - they'll be used once we implement:
    // - AnxietyMonitoringService (uses dataSource, detectionEngine, baselineEngine)
    // - Data setup UI (uses historicalDataLoader, firstRunHandler)
    // - Samsung sensor integration (uses samsungSensorDataSource)
    // - Watch sync (uses wearableDataSyncService)

    // Samsung Sensor Data Source
    @Provides
    @Singleton
    fun provideSamsungSensorDataSource(@ApplicationContext context: Context): AndroidSamsungSensorDataSource =
        AndroidSamsungSensorDataSource(context)

    // health Connect Data Source
    @Provides
    @Singleton
    fun provideHealthConnectDataSource(@ApplicationContext context: Context): AndroidHealthConnectDataSource =
        AndroidHealthConnectDataSource(context)

    // Hybrid Biometric Data Source as primary
    @Provides
    @Singleton
    fun provideBiometricDataSource(
        @ApplicationContext context: Context,
        samsungSensorSource: AndroidSamsungSensorDataSource,
        healthConnectSource: AndroidHealthConnectDataSource
    ): BiometricDataSource = HybridBiometricDataSource(context, samsungSensorSource, healthConnectSource)

    // Historical Data Loader
    @Provides
    @Singleton
    fun provideHistoricalDataLoader(
        @ApplicationContext context: Context,
        repository: DataRepository,
        baselineEngine: BaselineEngine
    ): HistoricalDataLoader = HistoricalDataLoader(context, repository, baselineEngine)

    // Wearable Data Sync Service
    @Provides
    @Singleton
    fun provideWearableDataSyncService(
        @ApplicationContext context: Context,
        repository: DataRepository
    ): WearableDataSyncService = WearableDataSyncService(context, repository)

    // First Run Handler
    @Provides
    @Singleton
    fun provideFirstRunHandler(@ApplicationContext context: Context): FirstRunHandler =
        FirstRunHandler(context)

    @Provides
    @Singleton
    fun provideDataRepository(
        database: AnxietyDatabase
    ): DataRepository = AndroidDataRepository(database)

    @Provides
    @Singleton
    fun provideCognitiveAnalyzer(
        modelManager: ModelManager
    ): CognitiveAnalyzer = AndroidCognitiveAnalyzer(modelManager)

    // ML Components
    @Provides
    @Singleton
    fun provideFeatureEngineer(): FeatureEngineer = FeatureEngineer()

    @Provides
    @Singleton
    fun provideModelValidationUtils(@ApplicationContext context: Context): ModelValidationUtils =
        ModelValidationUtils(context)

    @Provides
    @Singleton
    fun provideTensorFlowModel(
        @ApplicationContext context: Context,
        featureEngineer: FeatureEngineer
    ): AndroidTensorFlowLiteModel = AndroidTensorFlowLiteModel(context, featureEngineer)

    @Provides
    @Singleton
    fun providePersonalizedThresholdManager(): PersonalizedThresholdManager =
        PersonalizedThresholdManager()

    // Detection Engines
    @Provides
    @Singleton
    fun provideAnxietyDetectionEngine(
        mlModel: AndroidTensorFlowLiteModel,
        validationUtils: ModelValidationUtils,
        thresholdManager: PersonalizedThresholdManager,
        repository: DataRepository
    ): AnxietyDetectionEngine = AnxietyDetectionEngine(
        mlModel,
        validationUtils,
        thresholdManager,
        repository
    )

    @Provides
    @Singleton
    fun provideBaselineEngine(
        repository: DataRepository
    ): BaselineEngine = BaselineEngine(repository)

    // Services
    @Provides
    @Singleton
    fun provideAnxietyMonitoringService(
        dataSource: BiometricDataSource,
        repository: DataRepository,
        detectionEngine: AnxietyDetectionEngine,
        baselineEngine: BaselineEngine,
        wearableSync: WearableDataSyncService
    ): AnxietyMonitoringService = AnxietyMonitoringService(
        dataSource,
        repository,
        detectionEngine,
        baselineEngine,
        wearableSync
    )
}