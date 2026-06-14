package info.dvkr.screenstream

import info.dvkr.screenstream.common.CommonKoinModule
import info.dvkr.screenstream.mjpeg.MjpegKoinModule
import info.dvkr.screenstream.rtsp.RtspKoinModule
import org.koin.core.module.Module

public class ScreenStreamApp : BaseApp() {

    override val streamingModules: Array<Module> = arrayOf(CommonKoinModule, MjpegKoinModule, RtspKoinModule)
}
