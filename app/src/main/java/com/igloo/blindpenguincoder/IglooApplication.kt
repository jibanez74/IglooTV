package com.igloo.blindpenguincoder

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.igloo.blindpenguincoder.core.image.createIglooImageLoader

class IglooApplication : Application(), SingletonImageLoader.Factory {
    val container by lazy { IglooAppContainer(this) }

    override fun newImageLoader(context: Context): ImageLoader =
        createIglooImageLoader(context, container.credentials, container.serverUrlProvider)
}
