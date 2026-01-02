package info.dvkr.screenstream.mjpeg.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.settings.image.CropImage
import info.dvkr.screenstream.mjpeg.ui.settings.image.Flip
import info.dvkr.screenstream.mjpeg.ui.settings.image.Grayscale
import info.dvkr.screenstream.mjpeg.ui.settings.image.JpegQuality
import info.dvkr.screenstream.mjpeg.ui.settings.image.MaxFPS
import info.dvkr.screenstream.mjpeg.ui.settings.image.ResizeImage
import info.dvkr.screenstream.mjpeg.ui.settings.image.Rotation
import info.dvkr.screenstream.mjpeg.ui.settings.image.VrMode

public object ImageGroup : ModuleSettings.Group {
    override val id: String = "IMAGE"
    override val position: Int = 1
    override val items: List<ModuleSettings.Item> =
        listOf(VrMode, CropImage, Grayscale, ResizeImage, Rotation, Flip, MaxFPS, JpegQuality)
            .filter { it.available }.sortedBy { it.position }

    @Composable
    override fun TitleUI(modifier: Modifier) {
        Text(
            text = stringResource(id = R.string.mjpeg_pref_settings_image),
            modifier = modifier,
            style = MaterialTheme.typography.titleMedium
        )
    }
}