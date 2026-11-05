package hr.gd.androidfilewifi

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import hr.gd.androidfilewifi.data.PcStore
import hr.gd.androidfilewifi.net.PcDiscovery
import hr.gd.androidfilewifi.transfer.Notifications
import hr.gd.androidfilewifi.transfer.TransferRepository

/** Process-wide singletons (small app, so a plain holder is enough). */
object Services {
    lateinit var pcStore: PcStore
    lateinit var transfers: TransferRepository
    lateinit var discovery: PcDiscovery
}

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Services.pcStore = PcStore(this)
        Services.discovery = PcDiscovery(this)
        Services.transfers = TransferRepository(this)
        Notifications.createChannels(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
