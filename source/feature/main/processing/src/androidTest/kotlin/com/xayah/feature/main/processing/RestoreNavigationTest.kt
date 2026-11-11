package com.xayah.feature.main.processing

import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.ui.route.MainRoutes
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreNavigationTest {
    @Test
    fun restoreKeepsLiteralAccountNamesAndDirectories() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val restore = MainRoutes.PackagesRestoreProcessingGraph
            val nav = NavHostController(instrumentation.targetContext)
            nav.navigatorProvider.addNavigator(ComposeNavigator())
            nav.graph = nav.createGraph(startDestination = "home") {
                composable("home") {}
                composable(restore.route) {}
            }
            val path = "资料/%2F + #1?x&y"
            for (name in listOf("", "Lab+50% / A&B")) {
                nav.navigate(restore.getRoute(name, path))
                val args = checkNotNull(nav.currentBackStackEntry?.arguments)
                assertEquals(name.ifEmpty { null }, args.getString(MainRoutes.ARG_ACCOUNT_NAME))
                assertEquals(path, args.getString(MainRoutes.ARG_ACCOUNT_REMOTE))
            }
        }
    }
}
