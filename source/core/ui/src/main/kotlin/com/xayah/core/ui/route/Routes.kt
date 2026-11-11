package com.xayah.core.ui.route

import android.net.Uri
import com.xayah.core.model.OpType
import com.xayah.core.model.Target

sealed class MainRoutes(val route: String) {
    companion object {
        const val ARG_PACKAGE_NAME = "pkgName"
        const val ARG_MEDIA_NAME = "mediaName"
        const val ARG_USER_ID = "userId"
        const val ARG_PRESERVE_ID = "preserveId"
        const val ARG_ACCOUNT_NAME = "accountName"
        const val ARG_ACCOUNT_REMOTE = "accountRemote"
        const val ARG_TARGET = "target"
        const val ARG_OP_TYPE = "opType"
        const val ARG_ID = "id"

        private const val ACCOUNT_QUERY = "?$ARG_ACCOUNT_NAME={$ARG_ACCOUNT_NAME}"
        private const val LOCATION_QUERY = "$ACCOUNT_QUERY&$ARG_ACCOUNT_REMOTE={$ARG_ACCOUNT_REMOTE}"

        private fun cloudRoute(path: String, name: String, directory: String = "") = Uri.parse(path).buildUpon().apply {
            if (name.isNotEmpty()) appendQueryParameter(ARG_ACCOUNT_NAME, name)
            if (directory.isNotEmpty()) appendQueryParameter(ARG_ACCOUNT_REMOTE, directory)
        }.build().toString()
    }

    data object Dashboard : MainRoutes(route = "main_dashboard")
    data object Cloud : MainRoutes(route = "main_cloud")
    data object CloudAddAccount : MainRoutes(route = "main_cloud_add_account")
    data object FTPSetup : MainRoutes(route = "main_ftp_setup$ACCOUNT_QUERY") {
        fun getRoute(name: String = "") = cloudRoute("main_ftp_setup", name)
    }

    data object SFTPSetup : MainRoutes(route = "main_sftp_setup$ACCOUNT_QUERY") {
        fun getRoute(name: String = "") = cloudRoute("main_sftp_setup", name)
    }

    data object WebDAVSetup : MainRoutes(route = "main_webdav_setup$ACCOUNT_QUERY") {
        fun getRoute(name: String = "") = cloudRoute("main_webdav_setup", name)
    }

    data object SMBSetup : MainRoutes(route = "main_smb_setup$ACCOUNT_QUERY") {
        fun getRoute(name: String = "") = cloudRoute("main_smb_setup", name)
    }
    data object Settings : MainRoutes(route = "main_settings")
    data object Restore : MainRoutes(route = "main_restore")
    data object Reload : MainRoutes(route = "main_reload")
    data object BackupVerification : MainRoutes(route = "main_backup_verification")
    data object BackupRetention : MainRoutes(route = "main_backup_retention")
    data object TitaniumImport : MainRoutes(route = "main_titanium_import")
    data object BackupSettings : MainRoutes(route = "main_backup_settings")
    data object RestoreSettings : MainRoutes(route = "main_restore_settings")
    data object LanguageSettings : MainRoutes(route = "main_language_settings")
    data object BlackList : MainRoutes(route = "main_blacklist")
    data object Configurations : MainRoutes(route = "main_configurations")
    data object About : MainRoutes(route = "main_about")
    data object Translators : MainRoutes(route = "main_translators")

    data object List : MainRoutes(route = "main_list/{$ARG_TARGET}/{$ARG_OP_TYPE}$LOCATION_QUERY") {
        fun getRoute(target: Target, opType: OpType, cloudName: String = "", backupDir: String = "") =
            cloudRoute("main_list/$target/$opType", cloudName, backupDir)
    }

    data object Details : MainRoutes(route = "main_details/{$ARG_TARGET}/{$ARG_OP_TYPE}/{$ARG_ID}") {
        fun getRoute(target: Target, opType: OpType, id: Long) = "main_details/${target}/${opType}/${id}"
    }

    data object History : MainRoutes(route = "main_history")
    data object AppRevisions : MainRoutes(route = "main_app_revisions/{$ARG_PACKAGE_NAME}/{$ARG_USER_ID}") {
        fun getRoute(packageName: String, userId: Int) = "main_app_revisions/$packageName/$userId"
    }
    data object PermissionEditor : MainRoutes(route = "main_permission_editor/{$ARG_PACKAGE_NAME}/{$ARG_USER_ID}") {
        fun getRoute(packageName: String, userId: Int) = "main_permission_editor/$packageName/$userId"
    }

    data object TaskDetails : MainRoutes(route = "main_task_details/{$ARG_ID}") {
        fun getRoute(id: Long) = "main_task_details/${id}"
    }

    data object Directory : MainRoutes(route = "main_directory")

    data object PackagesBackupProcessing : MainRoutes(route = "main_packages_backup_processing")
    data object PackagesBackupProcessingSetup : MainRoutes(route = "main_packages_backup_processing_setup")
    data object PackagesBackupProcessingGraph : MainRoutes(route = "main_packages_backup_processing_graph")

    data object PackagesRestoreProcessing : MainRoutes(route = "main_packages_restore_processing")
    data object PackagesRestoreProcessingSetup : MainRoutes(route = "main_packages_restore_processing_setup")
    data object PackagesRestoreProcessingGraph : MainRoutes(route = "main_packages_restore_processing_graph$LOCATION_QUERY") {
        fun getRoute(cloudName: String = "", backupDir: String = "") = cloudRoute("main_packages_restore_processing_graph", cloudName, backupDir)
    }

    data object MediumBackupProcessing : MainRoutes(route = "main_medium_backup_processing")
    data object MediumBackupProcessingSetup : MainRoutes(route = "main_medium_backup_processing_setup")
    data object MediumBackupProcessingGraph : MainRoutes(route = "main_medium_backup_processing_graph")

    data object MediumRestoreProcessing : MainRoutes(route = "main_medium_restore_processing")
    data object MediumRestoreProcessingSetup : MainRoutes(route = "main_medium_restore_processing_setup")
    data object MediumRestoreProcessingGraph : MainRoutes(route = "main_medium_restore_processing_graph$LOCATION_QUERY") {
        fun getRoute(cloudName: String = "", backupDir: String = "") = cloudRoute("main_medium_restore_processing_graph", cloudName, backupDir)
    }
}
