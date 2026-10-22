package com.zionhuang.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zionhuang.music.LocalPlayerConnection
import com.zionhuang.music.constants.GlassBackgroundKey
import com.zionhuang.music.constants.PlayerHorizontalPadding
import com.zionhuang.music.constants.ShowLyricsKey
import com.zionhuang.music.constants.ThumbnailCornerRadius
import com.zionhuang.music.ui.component.Lyrics
import com.zionhuang.music.utils.rememberPreference
import android.os.Build

/** Upscale YouTube/Googleusercontent thumbnail URL to maximum resolution. */
fun String.hqThumbnail(): String =
    "=w\\d+-h\\d+".toRegex().let { r ->
        if (r.containsMatchIn(this)) r.replace(this, "=w1200-h1200") else this
    }

private val AURORA_BASE = Color(0xFF0D0618)

@Composable
fun Thumbnail(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val currentView = LocalView.current

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val error by playerConnection.error.collectAsState()

    val showLyrics by rememberPreference(ShowLyricsKey, false)
    val glassBackground by rememberPreference(GlassBackgroundKey, true)

    DisposableEffect(showLyrics) {
        currentView.keepScreenOn = showLyrics
        onDispose {
            currentView.keepScreenOn = false
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "aurora")
    val auroraPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(32000, easing = LinearEasing)
        ),
        label = "auroraPhase"
    )

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = !showLyrics && error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = PlayerHorizontalPadding)
            ) {
                val thumbnailUrl = mediaMetadata?.thumbnailUrl
                if (glassBackground && thumbnailUrl != null) {
                    Box(modifier = Modifier.matchParentSize()) {
                        AsyncImage(
                            model = thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alpha = 0.55f,
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(1.45f)
                                .then(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(40.dp)
                                    else Modifier
                                )
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(AURORA_BASE.copy(alpha = 0.88f))
                        )
                        val primary = MaterialTheme.colorScheme.primary
                        val tertiary = MaterialTheme.colorScheme.tertiary
                        val secondary = MaterialTheme.colorScheme.secondary
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            primary.copy(alpha = 0.50f),
                                            Color.Transparent,
                                            tertiary.copy(alpha = 0.45f),
                                            Color.Transparent,
                                            secondary.copy(alpha = 0.40f)
                                        ),
                                        start = Offset(0f, auroraPhase * 3600f),
                                        end = Offset(auroraPhase * 2600f, (auroraPhase + 0.45f) * 3600f)
                                    )
                                )
                        )
                    }
                }

                AsyncImage(
                    model = thumbnailUrl?.hqThumbnail() ?: thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ThumbnailCornerRadius * 2))
                        .let {
                            if (glassBackground && thumbnailUrl != null) {
                                it.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(ThumbnailCornerRadius * 2)
                                )
                            } else it
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { offset ->
                                    if (offset.x < size.width / 2) {
                                        playerConnection.player.seekBack()
                                    } else {
                                        playerConnection.player.seekForward()
                                    }
                                }
                            )
                        }
                )
            }
        }

        AnimatedVisibility(
            visible = showLyrics && error == null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Lyrics(sliderPositionProvider = sliderPositionProvider)
        }

        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .padding(32.dp)
                .align(Alignment.Center)
        ) {
            error?.let { error ->
                PlaybackError(
                    error = error,
                    retry = playerConnection.player::prepare
                )
            }
        }
    }
}
