package com.xayah.core.ui.component

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xayah.core.ui.R
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.theme.withState
import com.xayah.core.ui.token.SizeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

@ExperimentalFoundationApi
@Composable
fun PackageIconImage(
    icon: ImageVector? = null,
    packageName: String,
    userId: Int? = null,
    shape: Shape? = null,
    inCircleShape: Boolean = false,
    size: Dp = SizeTokens.Level32,
    refreshKey: Any? = null,
    viewModel: PackageIconViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var iconForeground by remember(packageName, userId, icon) { mutableStateOf<Drawable?>(null) }
    var iconBackground by remember(packageName, userId, icon) { mutableStateOf<Drawable?>(null) }
    val sizeForeground by remember(size, inCircleShape) { mutableStateOf(if (inCircleShape) size.div(sqrt(2.2F)) else size) }
    LaunchedEffect(packageName, userId, icon, inCircleShape, refreshKey) {
        if (icon == null) {
            val (foreground, background) = withContext(Dispatchers.IO) {
                val iconDrawable = viewModel.load(packageName, userId, refreshKey != null)
                if (iconDrawable != null) {
                    if (inCircleShape) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && iconDrawable is AdaptiveIconDrawable) {
                            null to LayerDrawable(arrayOf(iconDrawable.background, iconDrawable.foreground))
                        } else {
                            iconDrawable to null
                        }
                    } else {
                        iconDrawable to null
                    }
                } else null to null
            }
            iconBackground = background
            iconForeground = foreground ?: if (background == null) {
                AppCompatResources.getDrawable(context, android.R.drawable.sym_def_app_icon)
            } else {
                null
            }
        }
    }

    Box(modifier = if (shape != null) Modifier.clip(shape) else Modifier, contentAlignment = Alignment.Center) {
        with(LocalDensity.current) {
            AsyncImage(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .scale(1.4f)
                    .background(ThemedColorSchemeKeyTokens.PrimaryContainer.value),
                model = ImageRequest.Builder(context)
                    .data(iconBackground?.toBitmap(sizeForeground.roundToPx(), sizeForeground.roundToPx(), Bitmap.Config.ARGB_8888))
                    .crossfade(true)
                    .build(),
                contentDescription = null
            )
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    modifier = Modifier
                        .size(sizeForeground),
                    contentDescription = null,
                    tint = ThemedColorSchemeKeyTokens.Primary.value
                )
            } else {
                AsyncImage(
                    modifier = Modifier
                        .size(sizeForeground),
                    model = ImageRequest.Builder(context)
                        .data(iconForeground?.toBitmap(sizeForeground.roundToPx(), sizeForeground.roundToPx(), Bitmap.Config.ARGB_8888))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                )
            }
        }
    }
}

@ExperimentalFoundationApi
@Composable
fun MediaIconImage(enabled: Boolean = true, name: String, textStyle: TextStyle = MaterialTheme.typography.labelMedium, size: Dp = SizeTokens.Level32) {
    Surface(modifier = Modifier.size(size), indication = null, shape = CircleShape, color = ThemedColorSchemeKeyTokens.PrimaryContainer.value.withState(), enabled = enabled) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name,
                style = textStyle,
                color = ThemedColorSchemeKeyTokens.OnPrimaryContainer.value.withState(enabled),
            )
        }
    }
}

@Composable
fun AppIcon(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(SizeTokens.Level128)
            .clip(CircleShape)
            .background(colorResource(id = R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            modifier = Modifier.size(SizeTokens.Level100),
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_launcher_foreground_tonal),
            contentDescription = null
        )
    }
}
