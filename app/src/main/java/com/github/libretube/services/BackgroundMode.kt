package com.github.libretube.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.libretube.R
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.SegmentData
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.BACKGROUND_CHANNEL_ID
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PLAYER_NOTIFICATION_ID
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.awaitQuery
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toStreamItem
import com.github.libretube.util.NowPlayingNotification
import com.github.libretube.util.PlayerHelper
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.PreferenceHelper
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Loads the selected videos audio in background mode with a notification area.
 */
class BackgroundMode : Service() {
    /**
     * VideoId of the video
     */
    private lateinit var videoId: String

    /**
     *PlaylistId for autoplay
     */
    private var playlistId: String? = null

    /**
     * The response that gets when called the Api.
     */
    private var streams: Streams? = null

    /**
     * The [ExoPlayer] player. Followed tutorial [here](https://developer.android.com/codelabs/exoplayer-intro)
     */
    private var player: ExoPlayer? = null
    private var playWhenReadyPlayer = true

    /**
     * The [AudioAttributes] handle the audio focus of the [player]
     */
    private lateinit var audioAttributes: AudioAttributes

    /**
     * SponsorBlock Segment data
     */
    private var segmentData: SegmentData? = null

    /**
     * [Notification] for the player
     */
    private lateinit var nowPlayingNotification: NowPlayingNotification

    /**
     * Autoplay Preference
     */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Setting the required [Notification] for running as a foreground service
     */
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BACKGROUND_CHANNEL_ID,
                "Background Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            // see https://developer.android.com/reference/android/app/Service#startForeground(int,%20android.app.Notification)
            val notification: Notification = Notification.Builder(this, BACKGROUND_CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.playingOnBackground))
                .build()

            startForeground(PLAYER_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Initializes the [player] with the [MediaItem].
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // clear the playing queue
            PlayingQueue.clear()

            // get the intent arguments
            videoId = intent?.getStringExtra(IntentData.videoId)!!
            playlistId = intent.getStringExtra(IntentData.playlistId)
            val position = intent.getLongExtra(IntentData.position, 0L)

            // play the audio in the background
            loadAudio(videoId, position)

            PlayingQueue.setOnQueueTapListener { streamItem ->
                streamItem.url?.toID()?.let { playNextVideo(it) }
            }

            updateWatchPosition()
        } catch (e: Exception) {
            onDestroy()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun updateWatchPosition() {
        player?.currentPosition?.let {
            DatabaseHelper.saveWatchPosition(videoId, it)
        }
        handler.postDelayed(this::updateWatchPosition, 500)
    }

    /**
     * Gets the video data and prepares the [player].
     */
    private fun loadAudio(
        videoId: String,
        seekToPosition: Long = 0
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                streams = RetrofitInstance.api.getStreams(videoId)
            } catch (e: Exception) {
                return@launch
            }

            // add the playlist video to the queue
            if (playlistId != null && PlayingQueue.isEmpty()) {
                streams?.toStreamItem(videoId)
                    ?.let { PlayingQueue.insertPlaylist(playlistId!!, it) }
            } else {
                streams?.toStreamItem(videoId)?.let { PlayingQueue.updateCurrent(it) }
                streams?.relatedStreams?.toTypedArray()?.let {
                    PlayingQueue.add(*it)
                }
            }

            handler.post {
                playAudio(seekToPosition)
            }
        }
    }

    private fun playAudio(
        seekToPosition: Long
    ) {
        initializePlayer()
        setMediaItem()

        // create the notification
        if (!this@BackgroundMode::nowPlayingNotification.isInitialized) {
            nowPlayingNotification = NowPlayingNotification(this@BackgroundMode, player!!, true)
        }
        nowPlayingNotification.updatePlayerNotification(videoId, streams!!)

        player?.apply {
            playWhenReady = playWhenReadyPlayer
            prepare()
        }

        // seek to the previous position if available
        if (seekToPosition != 0L) {
            player?.seekTo(seekToPosition)
        } else if (PlayerHelper.watchPositionsEnabled) {
            try {
                val watchPosition = awaitQuery {
                    DatabaseHolder.Database.watchPositionDao().findById(videoId)
                }
                Log.e("position", watchPosition.toString())
                streams?.duration?.let {
                    if (watchPosition != null && watchPosition.position < it * 1000 * 0.9) {
                        player?.seekTo(watchPosition.position)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // set the playback speed
        val playbackSpeed = PreferenceHelper.getString(
            PreferenceKeys.BACKGROUND_PLAYBACK_SPEED,
            "1"
        ).toFloat()
        player?.setPlaybackSpeed(playbackSpeed)

        fetchSponsorBlockSegments()
    }

    /**
     * create the player
     */
    private fun initializePlayer() {
        if (player != null) return

        audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(audioAttributes, true)
            .build()

        /**
         * Listens for changed playbackStates (e.g. pause, end)
         * Plays the next video when the current one ended
         */
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_ENDED -> {
                        if (PlayerHelper.autoPlayEnabled) playNextVideo()
                    }
                    Player.STATE_IDLE -> {
                        onDestroy()
                    }
                    Player.STATE_BUFFERING -> {}
                    Player.STATE_READY -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // show a toast on errors
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        this@BackgroundMode.applicationContext,
                        error.localizedMessage,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    /**
     * Plays the first related video to the current (used when the playback of the current video ended)
     */
    private fun playNextVideo(nextId: String? = null) {
        val nextVideo = nextId ?: PlayingQueue.getNext()

        // play new video on background
        if (nextVideo != null) {
            this.videoId = nextVideo
        }
        this.segmentData = null
        loadAudio(videoId)
    }

    /**
     * Sets the [MediaItem] with the [streams] into the [player]
     */
    private fun setMediaItem() {
        streams?.let {
            val uri = if (streams!!.hls != null) {
                streams!!.hls
            } else if (streams!!.audioStreams!!.isNotEmpty()) {
                PlayerHelper.getAudioSource(
                    this,
                    streams!!.audioStreams!!
                )
            } else {
                return
            }
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .build()
            player?.setMediaItem(mediaItem)
        }
    }

    /**
     * fetch the segments for SponsorBlock
     */
    private fun fetchSponsorBlockSegments() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val categories = PlayerHelper.getSponsorBlockCategories()
                if (categories.isEmpty()) return@runCatching
                segmentData =
                    RetrofitInstance.api.getSegments(
                        videoId,
                        ObjectMapper().writeValueAsString(categories)
                    )
                checkForSegments()
            }
        }
    }

    /**
     * check for SponsorBlock segments
     */
    private fun checkForSegments() {
        Handler(Looper.getMainLooper()).postDelayed(this::checkForSegments, 100)

        if (segmentData == null || segmentData!!.segments.isEmpty()) return

        segmentData!!.segments.forEach { segment: Segment ->
            val segmentStart = (segment.segment[0] * 1000f).toLong()
            val segmentEnd = (segment.segment[1] * 1000f).toLong()
            val currentPosition = player?.currentPosition
            if (currentPosition in segmentStart until segmentEnd) {
                if (PlayerHelper.sponsorBlockNotifications) {
                    try {
                        Toast.makeText(this, R.string.segment_skipped, Toast.LENGTH_SHORT)
                            .show()
                    } catch (e: Exception) {
                        // Do nothing.
                    }
                }
                player?.seekTo(segmentEnd)
            }
        }
    }

    /**
     * destroy the [BackgroundMode] foreground service
     */
    override fun onDestroy() {
        // clear the playing queue
        PlayingQueue.clear()

        if (this::nowPlayingNotification.isInitialized) nowPlayingNotification.destroySelfAndPlayer()

        // called when the user pressed stop in the notification
        // stop the service from being in the foreground and remove the notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // destroy the service
        stopSelf()

        super.onDestroy()
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
}
