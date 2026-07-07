package com.igloo.blindpenguincoder

import android.app.Application

class IglooApplication : Application() {
    val container by lazy { IglooAppContainer(this) }
}
