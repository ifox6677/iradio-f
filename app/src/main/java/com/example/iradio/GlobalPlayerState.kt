package com.zhangjq0908.iradio


//object GlobalPlayerState {
// GlobalPlayerState.kt
object GlobalPlayerState {
    var isPlaying: Boolean = false
        private set
    var playingUrl: String? = null
        private set
    
    private val listeners = mutableListOf<(Boolean, String?) -> Unit>()
    
    fun setPlaying(url: String?) {
        playingUrl = url
        isPlaying = url != null
        notifyListeners()
    }
    
    fun stop() {
        playingUrl = null
        isPlaying = false
        notifyListeners()
    }
    
    fun addListener(listener: (Boolean, String?) -> Unit) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: (Boolean, String?) -> Unit) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners() {
        listeners.forEach { it(isPlaying, playingUrl) }
    }
}