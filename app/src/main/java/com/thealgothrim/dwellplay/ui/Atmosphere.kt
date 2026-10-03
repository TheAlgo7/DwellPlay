package com.thealgothrim.dwellplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.ui.theme.LocalScreen
import com.thealgothrim.dwellplay.ui.theme.Palette
import com.thealgothrim.dwellplay.ui.theme.isCar

/**
 * A small cached still colors the controls; this never samples the playing video or blurs text,
 * controls or YouTube. On Android 10/11 low-resolution artwork still supplies the muted tint.
 */
@Composable
fun MediaAtmosphere(video: Video, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
  val context = LocalContext.current
  val car = LocalScreen.current.isCar
  val model = artworkModel(video)
  val request = remember(model, context) {
    ImageRequest.Builder(context).data(model).size(96, 54).build()
  }
  Box(modifier.background(Palette.CanvasRaised)) {
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.matchParentSize().blur(if (car) 12.dp else 24.dp).alpha(0.18f),
    )
    Box(
        Modifier.matchParentSize().background(
            Brush.verticalGradient(listOf(Palette.CanvasRaised.copy(alpha = 0.2f), Palette.Canvas.copy(alpha = 0.88f)))
        )
    )
    content()
  }
}
