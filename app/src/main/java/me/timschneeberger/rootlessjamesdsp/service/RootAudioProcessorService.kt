package me.timschneeberger.rootlessjamesdsp.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import androidx.core.content.getSystemService
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistDatabase
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistRepository
import me.timschneeberger.rootlessjamesdsp.model.room.BlockedApp
import me.timschneeberger.rootlessjamesdsp.session.root.OnRootSessionChangeListener
import me.timschneeberger.rootlessjamesdsp.session.root.RootSessionDumpManager
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.notifications.Notifications
import me.timschneeberger.rootlessjamesdsp.utils.notifications.ServiceNotificationHelper
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.sdkAbove
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import timber.log.Timber

class RootAudioProcessorService : BaseAudioProcessorService(), KoinComponent,
    SharedPreferences.OnSharedPreferenceChangeListener, OnRootSessionChangeListener {

    // System services
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    // Termination flags
    private var isServiceDisposing = false

    // Enhanced processing
    private var sessionDumpManager: RootSessionDumpManager? = null

    // Preferences
    private val preferences: Preferences.App by inject()

    // Room databases
    private val applicationScope = CoroutineScope(SupervisorJob())
    private val blockedAppDatabase by lazy { AppBlocklistDatabase.getDatabase(this, applicationScope) }
    private val blockedAppRepository by lazy { AppBlocklistRepository(blockedAppDatabase.appBlocklistDao()) }
    private val blockedApps by lazy { blockedAppRepository.blocklist.asLiveData() }
    private val blockedAppObserver = Observer<List<BlockedApp>?> {
        // Re-calculate routing whenever the list of apps changes
        updateRoutingRules()
    }
    private val toggleListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == getString(R.string.key_routing_mode_whitelist)) {
            Timber.d("Toggle listener fired! Updating routing rules.")
            updateRoutingRules()
        }
    }

    // App object
    private val app
        get() = application as MainApplication

    override fun onCreate() {
        super.onCreate()

        // Register shared preferences listener
        preferences.registerOnSharedPreferenceChangeListener(this)
        app.rootSessionDatabase.registerOnSessionChangeListener(this)

        // Register the direct listener
        val sharedPrefs = getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(toggleListener)

        // Get reference to system services
        audioManager = getSystemService<AudioManager>()!!
        notificationManager = getSystemService<NotificationManager>()!!

        // Setup database observer
        blockedApps.observeForever(blockedAppObserver)

        // Launch foreground service
        val notification = if (app.isLegacyMode)
            ServiceNotificationHelper.createServiceNotificationLegacy(this)
        else
            ServiceNotificationHelper.createServiceNotification(this, arrayOf())

        sdkAbove(Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ID_SERVICE_STATUS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        }.below {
            startForeground(
                Notifications.ID_SERVICE_STATUS,
                notification
            )
        }

        // Initialize shared preferences manually
        arrayOf(R.string.key_powered_on, R.string.key_audioformat_enhanced_processing).forEach {
            onSharedPreferenceChanged(preferences.preferences, getString(it))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand")
        if (intent == null)
            return START_STICKY

        if(JamesDspRemoteEngine.isPluginInstalled() == JamesDspRemoteEngine.PluginState.Unavailable) {
            Timber.e("onStartCommand: Ignoring command because plugin is not installed")
            stopSelf()
            return START_NOT_STICKY
        }

        // Handle intent action
        when (intent.action) {
            null -> {
                Timber.wtf("onStartCommand: intent.action is null")
            }
            ACTION_START_ENHANCED_PROCESSING -> {
                if(!app.isEnhancedProcessing) {
                    Timber.e("onStartCommand: ACTION_START_ENHANCED_PROCESSING received, but isEnhancedProcessing is false")
                }
                sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                if(app.rootSessionDatabase.sessionList.isEmpty())
                    sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))

                if(!isServiceDisposing && !app.isEnhancedProcessing)
                    app.rootSessionDatabase.addSessionByIntent(intent)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                if(!app.isEnhancedProcessing) {
                    val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                    MainScope().launch {
                        if (sessionId != 0)
                            delay(800)
                        app.rootSessionDatabase.removeSessionByIntent(intent)
                        if (app.rootSessionDatabase.sessionList.isEmpty()) {
                            stopSelf()
                        }
                    }
                }
            }
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceDisposing = true

        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Unregister database observer
        blockedApps.removeObserver(blockedAppObserver)

        // Notify app about service termination and unregister
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))

        app.rootSessionDatabase.clearSessions()

        preferences.unregisterOnSharedPreferenceChangeListener(this)
        app.rootSessionDatabase.unregisterOnSessionChangeListener(this)

        // Unregister the direct listener
        val sharedPrefs = getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(toggleListener)

        notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            getString(R.string.key_audioformat_processing) -> { updateServiceNotification() }
            getString(R.string.key_powered_on) -> {
                app.rootSessionDatabase.enabled = sharedPreferences?.getBoolean(key, true) ?: true
                updateServiceNotification()
            }
            getString(R.string.key_audioformat_enhanced_processing) -> {
                // If switched to enhanced processing while the service was already active...
                if(app.isEnhancedProcessing) {
                    setupEnhancedProcessing()
                }
                else {
                    sessionDumpManager?.destroy()
                    sessionDumpManager = null
                    app.rootSessionDatabase.setExcludedUids(arrayOf())
                    app.rootSessionDatabase.clearSessions()
                }
            }
            getString(R.string.key_routing_mode_whitelist) -> {
                Timber.d("Routing mode changed. Updating rules.")
                updateRoutingRules()
            }
        }
    }

    private fun setupEnhancedProcessing() {
        app.rootSessionDatabase.clearSessions()

        sessionDumpManager = RootSessionDumpManager(this)
        sessionDumpManager?.setOnSessionDump(app.rootSessionDatabase::update)
        sessionDumpManager?.setOnDumpMethodChanged(app.rootSessionDatabase::clearSessions)
        sessionDumpManager?.pollOnce(false)
    }

    private fun updateRoutingRules() {
        if(!app.isEnhancedProcessing)
            return

        // Safely get the current list of selected UIDs
        val selectedUids = blockedApps.value?.map { it.uid } ?: emptyList()

        val prefKey = getString(R.string.key_routing_mode_whitelist)
        val sharedPrefs = getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)

        // Safely read the value, whether it was saved as a Boolean (Switch) or a String (List)
        val rawValue = sharedPrefs.all[prefKey]
        val isWhitelistMode = when (rawValue) {
            is Boolean -> rawValue
            is String -> rawValue.toBooleanStrictOrNull() ?: false
            else -> false
        }

        val uidsToExclude = if (isWhitelistMode) {
            // Whitelist mode: exclude all apps EXCEPT the selected ones and the DSP itself
            val allApps = packageManager.getInstalledApplications(0)
            allApps
                .map { it.uid }
                .filter { it !in selectedUids && it != android.os.Process.myUid() }
                .distinct()
        } else {
            // Blacklist mode: exclude ONLY the explicitly selected apps
            selectedUids
        }

        Timber.d("updateRoutingRules: Whitelist Mode: $isWhitelistMode. Excluded UIDs count: ${uidsToExclude.size}")

        app.rootSessionDatabase.setExcludedUids(uidsToExclude.toTypedArray())
        sessionDumpManager?.pollOnce(false)
    }

    override fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>) {
        updateServiceNotification()
    }

    private fun updateServiceNotification() {
        if(preferences.get<Boolean>(R.string.key_audioformat_processing))
            ServiceNotificationHelper.pushServiceNotificationLegacy(this)
        else
            ServiceNotificationHelper.pushServiceNotification(this, app.rootSessionDatabase.sessionList.values.toTypedArray())
    }

    companion object {
        const val ACTION_START_ENHANCED_PROCESSING = BuildConfig.APPLICATION_ID + ".root.service.START_ENHANCED"
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".root.service.STOP"

        fun startService(context: Context, intent: Intent) {
            Timber.d("startService: intent=$intent")

            // Prevent launch if plugin missing
            if(JamesDspRemoteEngine.isPluginInstalled() == JamesDspRemoteEngine.PluginState.Unavailable) {
                Timber.e("Service launch cancelled. Plugin not installed.")
                return
            }

            try {
                sdkAbove(Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                }.below {
                    context.startService(intent)
                }
            }
            catch (ex: Exception) {
                Timber.e("Failed to start service. Try to disable battery optimizations")
                Timber.e(ex)
            }
        }

        fun startServiceEnhanced(context: Context) {
            try {
                Intent(context, RootAudioProcessorService::class.java)
                    .apply { this.action = ACTION_START_ENHANCED_PROCESSING }
                    .run { startService(context, this) }
            }
            catch (ex: Exception) {
                Timber.e("Failed to start service. Try to disable battery optimizations")
                Timber.e(ex)
            }
        }

        fun stopService(context: Context) {
            try {
                Intent(context, RootAudioProcessorService::class.java)
                    .apply { this.action = ACTION_STOP }
                    .run { startService(context, this) }
            }
            catch(ex: Exception) {
                Timber.e(ex)
            }
        }

        fun updateLegacyMode(context: Context, enable: Boolean) {
            Timber.d("updateLegacyMode: enable=$enable")

            val action =
                if (enable) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
            Intent(context, RootAudioProcessorService::class.java)
                .apply { this.action = action }
                .apply { putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) }
                .run { startService(context, this) }
        }
    }
}
