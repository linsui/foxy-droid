package nya.kitsunyan.foxydroid

import android.annotation.SuppressLint
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import coil.Coil
import coil.ImageLoader
import nya.kitsunyan.foxydroid.content.Cache
import nya.kitsunyan.foxydroid.content.Preferences
import nya.kitsunyan.foxydroid.content.ProductPreferences
import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.entity.InstalledItem
import nya.kitsunyan.foxydroid.index.RepositoryUpdater
import nya.kitsunyan.foxydroid.network.CoilDownloader
import nya.kitsunyan.foxydroid.network.Downloader
import nya.kitsunyan.foxydroid.service.Connection
import nya.kitsunyan.foxydroid.service.SyncService
import nya.kitsunyan.foxydroid.utility.Utils
import nya.kitsunyan.foxydroid.utility.extension.android.*
import java.net.InetSocketAddress
import java.net.Proxy

@Suppress("unused")
class MainApplication: Application() {
  private fun PackageInfo.toInstalledItem(): InstalledItem {
    val signatureString = singleSignature?.let(Utils::calculateHash).orEmpty()
    return InstalledItem(packageName, versionName.orEmpty(), versionCodeCompat, signatureString)
  }

  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(Utils.configureLocale(base))
  }

  override fun onCreate() {
    super.onCreate()

    val databaseUpdated = Database.init(this)
    Preferences.init(this)
    ProductPreferences.init(this)
    RepositoryUpdater.init(this)
    listenApplications()
    listenPreferences()

    Coil.setImageLoader(ImageLoader.Builder(this)
      .callFactory(CoilDownloader.Factory(Cache.getImagesDir(this))).build())

    if (databaseUpdated) {
      forceSyncAll()
    }

    Cache.cleanup(this)
    updateSyncJob()
  }

  private fun listenApplications() {
    registerReceiver(object: BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.let { if (it.scheme == "package") it.schemeSpecificPart else null }
        if (packageName != null) {
          when (intent.action.orEmpty()) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED -> {
              val packageInfo = try {
                packageManager.getPackageInfo(packageName, Android.PackageManager.signaturesFlag)
              } catch (e: Exception) {
                null
              }
              if (packageInfo != null) {
                Database.InstalledAdapter.put(packageInfo.toInstalledItem())
              } else {
                Database.InstalledAdapter.delete(packageName)
              }
            }
          }
        }
      }
    }, IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addDataScheme("package")
    })
    val installedItems = packageManager.getInstalledPackages(Android.PackageManager.signaturesFlag)
      .map { it.toInstalledItem() }
    Database.InstalledAdapter.putAll(installedItems)
  }

  private fun listenPreferences() {
    updateProxy()
    var lastAutoSync = Preferences[Preferences.Key.AutoSync]
    var lastUpdateUnstable = Preferences[Preferences.Key.UpdateUnstable]
    Preferences.observable.subscribe {
      if (it == Preferences.Key.ProxyType || it == Preferences.Key.ProxyHost || it == Preferences.Key.ProxyPort) {
        updateProxy()
      } else if (it == Preferences.Key.AutoSync) {
        val autoSync = Preferences[Preferences.Key.AutoSync]
        if (lastAutoSync != autoSync) {
          lastAutoSync = autoSync
          updateSyncJob()
        }
      } else if (it == Preferences.Key.UpdateUnstable) {
        val updateUnstable = Preferences[Preferences.Key.UpdateUnstable]
        if (lastUpdateUnstable != updateUnstable) {
          lastUpdateUnstable = updateUnstable
          forceSyncAll()
        }
      }
    }
  }

  private fun updateSyncJob() {
    val autoSync = Preferences[Preferences.Key.AutoSync]
    val jobScheduler = getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
    when (autoSync) {
      Preferences.AutoSync.Never -> {
        jobScheduler.cancel(Common.JOB_ID_SYNC)
      }
      Preferences.AutoSync.Wifi, Preferences.AutoSync.Always -> {
        val period = 12 * 60 * 60 * 1000L // 12 hours
        val wifiOnly = autoSync == Preferences.AutoSync.Wifi
        jobScheduler.schedule(JobInfo
          .Builder(Common.JOB_ID_SYNC, ComponentName(this, SyncService.Job::class.java))
          .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
          .apply {
            if (Android.sdk(26)) {
              setRequiresBatteryNotLow(true)
              setRequiresStorageNotLow(true)
            }
            if (Android.sdk(24)) {
              setPeriodic(period, JobInfo.getMinFlexMillis())
            } else {
              setPeriodic(period)
            }
          }
          .build())
        Unit
      }
    }::class.java
  }

  private fun updateProxy() {
    val type = Preferences[Preferences.Key.ProxyType].proxyType
    val host = Preferences[Preferences.Key.ProxyHost]
    val port = Preferences[Preferences.Key.ProxyPort]
    val socketAddress = when (type) {
      Proxy.Type.DIRECT -> {
        null
      }
      Proxy.Type.HTTP, Proxy.Type.SOCKS -> {
        try {
          InetSocketAddress.createUnresolved(host, port)
        } catch (e: Exception) {
          e.printStackTrace()
          null
        }
      }
    }
    val proxy = socketAddress?.let { Proxy(type, socketAddress) }
    Downloader.proxy = proxy
  }

  private fun forceSyncAll() {
    Database.RepositoryAdapter.getAll(null).forEach {
      if (it.lastModified.isNotEmpty() || it.entityTag.isNotEmpty()) {
        Database.RepositoryAdapter.put(it.copy(lastModified = "", entityTag = ""))
      }
    }
    Connection<SyncService.Binder>(SyncService::class.java, onBind = {
      it.binder.sync(SyncService.SyncRequest.FORCE)
      it.connection.unbind(this)
    }).bind(this)
  }

  class BootReceiver: BroadcastReceiver() {
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) = Unit
  }
}
