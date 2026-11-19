package app.openradio

import android.app.Application
import androidx.room.Room
import app.openradio.data.CountryLocator
import app.openradio.data.PlaybackErrorLog
import app.openradio.data.PreferencesRepository
import app.openradio.data.StationRepository
import app.openradio.data.local.AppDatabase
import app.openradio.data.remote.RadioBrowserClient
import app.openradio.playback.PlaybackConnection
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Hand-rolled constructor injection: one container, created with the process. */
class AppContainer(app: Application) {
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    val directory = RadioBrowserClient(httpClient)
    private val database = Room.databaseBuilder(app, AppDatabase::class.java, "radio.db")
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
        .fallbackToDestructiveMigration(true)
        .build()
    val stations = StationRepository(database, directory)
    val preferences = PreferencesRepository(app)
    val playback = PlaybackConnection(app)
    val countryLocator = CountryLocator(app)
    val errorLog = PlaybackErrorLog(app)
}

class RadioApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
