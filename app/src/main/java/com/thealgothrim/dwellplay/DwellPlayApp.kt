package com.thealgothrim.dwellplay

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.thealgothrim.dwellplay.car.CarScreens
import com.thealgothrim.dwellplay.car.Device
import com.thealgothrim.dwellplay.sources.VideoFrameFetcher
import com.thealgothrim.dwellplay.sources.VideoFrameKeyer

class DwellPlayApp : Application(), SingletonImageLoader.Factory {
  override fun onCreate() {
    super.onCreate()
    AppGraph.init(this)
    Device.init(this)
    CarScreens.watch(this)
    AppGraph.library.refreshAll()
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= TRIM_MEMORY_UI_HIDDEN) AppGraph.store.flush()
  }

  /** Coil with the app's HTTP client (web artwork) plus stills drawn from local videos. */
  override fun newImageLoader(context: PlatformContext): ImageLoader =
      ImageLoader.Builder(context)
          .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { AppGraph.http }))
            add(VideoFrameFetcher.Factory(this@DwellPlayApp))
            add(VideoFrameKeyer())
          }
          .crossfade(true)
          .build()
}
