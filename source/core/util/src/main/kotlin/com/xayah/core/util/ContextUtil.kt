package com.xayah.core.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

fun Context.toBrowser(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
fun Context.getActivity(): Activity = this as Activity
fun Context.sharedFileUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)
