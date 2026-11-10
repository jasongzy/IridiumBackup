package com.xayah.databackup

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xayah.core.ui.component.AnimatedNavHost
import com.xayah.core.datastore.readAppVersionName
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.theme.DataBackupTheme
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.ActivityUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.cloud.PageCloud
import com.xayah.feature.main.cloud.add.PageCloudAddAccount
import com.xayah.feature.main.cloud.add.PageFTPSetup
import com.xayah.feature.main.cloud.add.PageSFTPSetup
import com.xayah.feature.main.cloud.add.PageSMBSetup
import com.xayah.feature.main.cloud.add.PageWebDAVSetup
import com.xayah.feature.main.configurations.PageConfigurations
import com.xayah.feature.main.dashboard.AppRevisionsRoute
import com.xayah.feature.main.dashboard.PageDashboard
import com.xayah.feature.main.details.DetailsRoute
import com.xayah.feature.main.details.PermissionEditorRoute
import com.xayah.feature.main.directory.PageDirectory
import com.xayah.feature.main.list.ListRoute
import com.xayah.feature.main.processing.medium.backup.MediumBackupProcessingGraph
import com.xayah.feature.main.processing.medium.restore.MediumRestoreProcessingGraph
import com.xayah.feature.main.processing.packages.backup.PackagesBackupProcessingGraph
import com.xayah.feature.main.processing.packages.restore.PackagesRestoreProcessingGraph
import com.xayah.feature.main.restore.PageRestore
import com.xayah.feature.main.restore.reload.PageReload
import com.xayah.feature.main.settings.PageSettings
import com.xayah.feature.main.settings.about.PageAboutSettings
import com.xayah.feature.main.settings.about.PageTranslatorsSettings
import com.xayah.feature.main.settings.backup.PageBackupSettings
import com.xayah.feature.main.settings.blacklist.PageBlackList
import com.xayah.feature.main.settings.language.PageLanguageSelector
import com.xayah.feature.main.settings.retention.PageBackupRetention
import com.xayah.feature.main.settings.restore.PageRestoreSettings
import com.xayah.feature.main.settings.titanium.PageTitaniumImport
import com.xayah.feature.main.settings.verification.PageBackupVerification
import com.xayah.feature.setup.MainActivity as SetupActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
@OptIn(
    ExperimentalCoroutinesApi::class,
    ExperimentalAnimationApi::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
)
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        var homeReady = false
        splash.setKeepOnScreenCondition { !homeReady }
        lifecycleScope.launch {
            if (readAppVersionName().first().isNotEmpty()) {
                val ready = try {
                    BaseUtil.initializeEnvironment(applicationContext)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("MainActivity", "Unable to initialize backup tools", e)
                    false
                }
                if (ready) {
                    showHome(savedInstanceState) { homeReady = true }
                    return@launch
                }
                Toast.makeText(this@MainActivity, R.string.base_binaries_release_failed, Toast.LENGTH_LONG).show()
            }
            startActivity(Intent(this@MainActivity, SetupActivity::class.java))
            finish()
        }
    }

    private fun showHome(savedInstanceState: Bundle?, onReady: () -> Unit) {
        setContent {
            DataBackupTheme {
                SideEffect(onReady)
                val navController = rememberNavController()
                val requestedDestination = if (intent.component?.className == MainActivity::class.java.name)
                    intent.getStringExtra(ActivityUtil.EXTRA_START_DESTINATION)?.takeIf { it == MainRoutes.Reload.route }
                else null
                LaunchedEffect(requestedDestination) {
                    if (savedInstanceState == null) requestedDestination?.let { navController.navigateSingle(it) }
                }
                CompositionLocalProvider(
                    LocalNavController provides navController,
                    androidx.lifecycle.compose.LocalLifecycleOwner provides androidx.compose.ui.platform.LocalLifecycleOwner.current,
                ) {
                    AnimatedNavHost(
                        navController = navController,
                        startDestination = MainRoutes.Dashboard.route,
                    ) {
                        composable(MainRoutes.Dashboard.route) {
                            PageDashboard()
                        }
                        composable(MainRoutes.Cloud.route) {
                            PageCloud()
                        }
                        composable(MainRoutes.CloudAddAccount.route) {
                            PageCloudAddAccount()
                        }
                        composable(MainRoutes.FTPSetup.route) {
                            PageFTPSetup()
                        }
                        composable(MainRoutes.WebDAVSetup.route) {
                            PageWebDAVSetup()
                        }
                        composable(MainRoutes.SMBSetup.route) {
                            PageSMBSetup()
                        }
                        composable(MainRoutes.SFTPSetup.route) {
                            PageSFTPSetup()
                        }
                        composable(MainRoutes.List.route) {
                            ListRoute()
                        }
                        composable(MainRoutes.Details.route) {
                            DetailsRoute()
                        }
                        composable(MainRoutes.AppRevisions.route) {
                            AppRevisionsRoute()
                        }
                        composable(MainRoutes.PermissionEditor.route) {
                            PermissionEditorRoute()
                        }
                        composable(MainRoutes.PackagesBackupProcessingGraph.route) {
                            PackagesBackupProcessingGraph()
                        }
                        composable(MainRoutes.PackagesRestoreProcessingGraph.route) {
                            PackagesRestoreProcessingGraph()
                        }
                        composable(MainRoutes.MediumBackupProcessingGraph.route) {
                            MediumBackupProcessingGraph()
                        }
                        composable(MainRoutes.MediumRestoreProcessingGraph.route) {
                            MediumRestoreProcessingGraph()
                        }
                        composable(MainRoutes.Settings.route) {
                            PageSettings()
                        }
                        composable(MainRoutes.BackupVerification.route) {
                            PageBackupVerification()
                        }
                        composable(MainRoutes.BackupRetention.route) {
                            PageBackupRetention()
                        }
                        composable(MainRoutes.TitaniumImport.route) {
                            PageTitaniumImport()
                        }
                        composable(MainRoutes.Restore.route) {
                            PageRestore()
                        }
                        composable(MainRoutes.Reload.route) {
                            PageReload()
                        }
                        composable(MainRoutes.BackupSettings.route) {
                            PageBackupSettings()
                        }
                        composable(MainRoutes.RestoreSettings.route) {
                            PageRestoreSettings()
                        }
                        composable(MainRoutes.LanguageSettings.route) {
                            PageLanguageSelector()
                        }
                        composable(MainRoutes.BlackList.route) {
                            PageBlackList()
                        }
                        composable(MainRoutes.Configurations.route) {
                            PageConfigurations()
                        }
                        composable(MainRoutes.About.route) {
                            PageAboutSettings()
                        }
                        composable(MainRoutes.Translators.route) {
                            PageTranslatorsSettings()
                        }
                        composable(route = MainRoutes.Directory.route) {
                            PageDirectory()
                        }
                    }
                }
            }
        }
    }
}
