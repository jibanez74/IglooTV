package com.igloo.blindpenguincoder.playback.media3

/**
 * Authoritative transport intent for one engine. ExoPlayer's temporary source-swap state never
 * enters this object, so a manifest suspension can only be resolved with the latest user/host
 * choice.
 */
internal class PlaybackIntent {
    var desiredPlayWhenReady = false
        private set
    var hostActive = true
        private set
    var terminal = false
        private set
    var released = false
        private set

    val shouldPlay: Boolean
        get() = desiredPlayWhenReady && hostActive && !terminal && !released

    val acceptsCommands: Boolean
        get() = !terminal && !released

    fun start(initialPlayWhenReady: Boolean): Boolean {
        if (!acceptsCommands) return false
        desiredPlayWhenReady = initialPlayWhenReady && hostActive
        return true
    }

    fun play(): Boolean {
        if (!acceptsCommands || !hostActive) return false
        desiredPlayWhenReady = true
        return true
    }

    fun pause(): Boolean {
        if (!acceptsCommands) return false
        desiredPlayWhenReady = false
        return true
    }

    fun hostPaused(): Boolean {
        if (!acceptsCommands) return false
        hostActive = false
        desiredPlayWhenReady = false
        return true
    }

    fun hostResumed(): Boolean {
        if (!acceptsCommands) return false
        hostActive = true
        return true
    }

    fun failTerminal(): Boolean {
        if (!acceptsCommands) return false
        terminal = true
        desiredPlayWhenReady = false
        return true
    }

    fun release(): Boolean {
        if (released) return false
        released = true
        terminal = true
        desiredPlayWhenReady = false
        return true
    }
}
