package com.thealgothrim.dwellplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.policy.formatClock
import com.thealgothrim.dwellplay.sources.VideoFrame
import com.thealgothrim.dwellplay.ui.theme.LocalDims
import com.thealgothrim.dwellplay.ui.theme.LocalType
import com.thealgothrim.dwellplay.ui.theme.Palette

/** The crimson mark and the name, as on the brand board. The mark follows the text's font scale. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
  val style = LocalType.current.heading.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em)
  val markHeight = with(LocalDensity.current) { style.fontSize.toDp() } * 0.86f
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Image(rememberVectorPainter(Icons.Monogram), contentDescription = null, modifier = Modifier.height(markHeight).aspectRatio(Icons.MARK_ASPECT))
    Spacer(Modifier.width(markHeight * 0.42f))
    Text("DwellPlay", style = style, maxLines = 1)
  }
}

fun SourceType.icon(): ImageVector =
    when (this) {
      SourceType.PHONE -> Icons.Phone
      SourceType.FOLDER -> Icons.Folder
      SourceType.YOUTUBE -> Icons.Embed
      SourceType.JELLYFIN -> Icons.Server
      SourceType.STREAM -> Icons.Link
    }

fun SourceType.label(): String =
    when (this) {
      SourceType.PHONE -> com.thealgothrim.dwellplay.car.Device.label
      SourceType.FOLDER -> "Folder"
      SourceType.YOUTUBE -> "YouTube"
      SourceType.JELLYFIN -> "Jellyfin"
      SourceType.STREAM -> "Link"
    }

/** What Coil should draw for a video: its artwork URL, or a still from the file itself. */
fun artworkModel(v: Video): Any? = v.artworkUrl ?: (v.ref as? MediaRef.Content)?.let { VideoFrame(it.uri) }

/** 16:9 art with the source icon underneath while it loads (or if there's none). */
@Composable
fun Artwork(video: Video, modifier: Modifier = Modifier, crop: Boolean = true) {
  val dims = LocalDims.current
  Box(modifier.clip(RoundedCornerShape(dims.corner)).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
    Icon(video.type.icon(), contentDescription = null, tint = Palette.Text3, modifier = Modifier.size(dims.iconLarge))
    AsyncImage(
        model = artworkModel(video),
        contentDescription = null,
        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
  }
}

/** A video card: art, progress, title, where it's from. */
@Composable
fun MediaCard(
    video: Video,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = LocalDims.current.cardWidth,
    onLongClick: (() -> Unit)? = null,
) {
  val dims = LocalDims.current
  val type = LocalType.current
  Column(
      modifier
          .then(if (width != null) Modifier.width(width) else Modifier)
          .clip(RoundedCornerShape(dims.corner))
          .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
          .semantics { contentDescription = "${video.title}, ${video.type.label()}" }
  ) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
      Artwork(video, Modifier.fillMaxSize())
      if (video.isFolder) {
        Icon(Icons.Folder, null, tint = Palette.Text, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(dims.icon))
      }
      video.durationMs?.let {
        Text(
            formatClock(it),
            style = type.small.copy(color = Palette.Text),
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Palette.Scrim, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
      }
      if (progress != null) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Palette.Scrim)) {
          Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Palette.Accent))
        }
      }
    }
    Spacer(Modifier.height(10.dp))
    Text(video.title, style = type.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(video.type.icon(), null, tint = Palette.Text3, modifier = Modifier.size(dims.iconSmall).padding(end = 4.dp))
      Text(video.subtitle ?: video.type.label(), style = type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
  val type = LocalType.current
  Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(title, style = type.heading, modifier = Modifier.weight(1f))
    action?.invoke()
  }
}

/** A titled, horizontally scrolling row of cards. */
@Composable
fun CardRow(
    title: String,
    videos: List<Video>,
    progressOf: (Video) -> Float?,
    onOpen: (Video, List<Video>) -> Unit,
    onLongClick: ((Video) -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
  if (videos.isEmpty()) return
  val dims = LocalDims.current
  Column {
    SectionHeader(title, Modifier.gutters(), action)
    Spacer(Modifier.height(dims.gap / 2))
    // Rows start clear of the car rail but scroll underneath its glass.
    LazyRow(contentPadding = PaddingValues(start = gutterStart(), end = dims.gutter), horizontalArrangement = Arrangement.spacedBy(dims.gap)) {
      items(videos, key = { it.id }) { v -> MediaCard(v, progressOf(v), onClick = { onOpen(v, videos) }, onLongClick = onLongClick?.let { { it(v) } }) }
    }
  }
}

/** A button with a label, at least the car's 76dp touch target in a car. */
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
  val dims = LocalDims.current
  val type = LocalType.current
  val bg = if (primary) Palette.Accent else Palette.SurfaceHigh
  val fg = if (primary) Palette.OnAccent else Palette.Text
  Row(
      modifier
          .defaultMinSize(minHeight = dims.touch)
          .clip(RoundedCornerShape(dims.touch / 2))
          .background(if (enabled) bg else Palette.Surface)
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .padding(horizontal = dims.gap),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
  ) {
    if (icon != null) {
      Icon(icon, null, tint = if (enabled) fg else Palette.Text3, modifier = Modifier.size(dims.icon))
      Spacer(Modifier.width(10.dp))
    }
    Text(label, style = type.button.copy(color = if (enabled) fg else Palette.Text3), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 10.dp))
  }
}

/** An icon-only button. Always labelled for TalkBack and rotary focus. */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = LocalDims.current.touch,
    iconSize: Dp = LocalDims.current.icon,
    tint: Color = Palette.Text,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
  Box(
      modifier
          .size(size)
          .clip(CircleShape)
          .background(if (filled) Palette.SurfaceTop else Color.Transparent)
          .then(if (filled) Modifier.border(1.dp, Palette.Hairline, CircleShape) else Modifier)
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .semantics { contentDescription = label },
      contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = null, tint = if (!enabled) Palette.Text3.copy(alpha = 0.45f) else tint, modifier = Modifier.size(iconSize))
  }
}

/** A calm full-width message with optional actions: empty states, errors, the driving notice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessagePanel(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    iconTint: Color = Palette.Text2,
    actions: @Composable FlowRowScope.() -> Unit = {},
) {
  val dims = LocalDims.current
  val type = LocalType.current
  Column(modifier.padding(dims.gutter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Icon(icon, null, tint = iconTint, modifier = Modifier.size(dims.iconLarge * 1.3f))
    Spacer(Modifier.height(dims.gap))
    Text(title, style = type.heading, maxLines = 3)
    if (body != null) {
      Spacer(Modifier.height(8.dp))
      Text(body, style = type.body.copy(color = Palette.Text2), maxLines = 4)
    }
    Spacer(Modifier.height(dims.gap))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(dims.gap / 2), verticalArrangement = Arrangement.spacedBy(dims.gap / 2), content = actions)
  }
}

@Composable
fun Spinner(modifier: Modifier = Modifier) {
  CircularProgressIndicator(modifier.size(LocalDims.current.iconLarge), color = Palette.Accent, strokeWidth = 3.dp)
}

/** A neutral surface block (no coloured strips: accent goes on content, not on boxes). */
fun Modifier.panel(corner: Dp): Modifier =
    this.clip(RoundedCornerShape(corner)).background(Palette.Surface).border(1.dp, Palette.Hairline, RoundedCornerShape(corner))

/** Chrome floats above content. The dark tint stays readable without a live backdrop blur. */
fun Modifier.chrome(corner: Dp = 24.dp): Modifier =
    this.clip(RoundedCornerShape(corner)).background(Palette.Glass).border(1.dp, Palette.Hairline, RoundedCornerShape(corner))
