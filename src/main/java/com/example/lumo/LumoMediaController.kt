package com.example.lumo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val CHANNEL_ID = "lumo_playback"
private const val NOTIFICATION_ID = 3107
private const val NOTIFICATION_PERMISSION_REQUEST = 3108
private const val ACTION_PLAY = "com.example.lumo.PLAY"
private const val ACTION_PAUSE = "com.example.lumo.PAUSE"
private const val ACTION_PREVIOUS = "com.example.lumo.PREVIOUS"
private const val ACTION_NEXT = "com.example.lumo.NEXT"
private const val ACTION_DISMISS = "com.example.lumo.DISMISS"
private const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024

internal data class LumoPlaybackState(
    val title: String = "Lecture LUMO",
    val subtitle: String = "",
    val artworkUrl: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val playing: Boolean = false,
    val videoWidth: Int = 16,
    val videoHeight: Int = 9,
    val mediaPresent: Boolean = false
)

internal class LumoMediaController(private val activity: Activity) {
    private val notificationManager = NotificationManagerCompat.from(activity)
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private val mediaSession = MediaSessionCompat(activity, "LUMO").apply {
        setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = play()
            override fun onPause() = pause()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = previous()
            override fun onSeekTo(pos: Long) = seekTo(pos)
        })
    }
    private var webView: WebView? = null
    private var playbackState = LumoPlaybackState()
    private var artwork: Bitmap? = null
    private var lastArtworkUrl = ""
    private var lastNotificationFingerprint = ""
    private var permissionRequested = false
    private var notificationDismissed = false
    private var nativeAutoStartPending = false
    private val defaultArtwork: Bitmap by lazy { createFallbackArtwork() }

    val bridge = Bridge()
    val isPlaying: Boolean get() = playbackState.playing
    val hasMedia: Boolean get() = playbackState.mediaPresent
    val videoWidth: Int get() = playbackState.videoWidth
    val videoHeight: Int get() = playbackState.videoHeight

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pause()
                ACTION_PLAY -> play()
                ACTION_PAUSE -> pause()
                ACTION_PREVIOUS -> previous()
                ACTION_NEXT -> next()
                ACTION_DISMISS -> {
                    notificationDismissed = true
                    lastNotificationFingerprint = ""
                    pause()
                    notificationManager.cancel(NOTIFICATION_ID)
                }
            }
        }
    }

    init {
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_PREVIOUS)
            addAction(ACTION_NEXT)
            addAction(ACTION_DISMISS)
        }
        ContextCompat.registerReceiver(
            activity,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun attach(view: WebView) {
        webView = view
        view.addJavascriptInterface(bridge, "LumoAndroid")
    }

    fun detach(view: WebView?) {
        if (webView === view) webView = null
    }

    fun injectBridge(view: WebView?) {
        view?.evaluateJavascript(MEDIA_BRIDGE_SCRIPT, null)
        if (nativeAutoStartPending && view?.url?.contains("/web/") == true) {
            nativeAutoStartPending = false
            view.evaluateJavascript(NATIVE_AUTOPLAY_SCRIPT, null)
        }
    }

    fun play() {
        notificationDismissed = false
        playbackState = playbackState.copy(playing = true)
        sendCommand("play")
        publishState()
    }

    fun pause() {
        playbackState = playbackState.copy(playing = false)
        sendCommand("pause")
        publishState()
    }

    fun previous() = sendCommand("previous")

    fun next() = sendCommand("next")

    fun seekTo(positionMs: Long) {
        val bounded = positionMs.coerceIn(0L, playbackState.durationMs.coerceAtLeast(0L))
        playbackState = playbackState.copy(positionMs = bounded)
        sendCommand("seek", bounded)
        publishState()
    }

    fun refreshNotificationAfterPermission() {
        if (canPostNotifications()) showNotification(force = true)
    }

    fun release() {
        pause()
        runCatching { activity.unregisterReceiver(receiver) }
        notificationManager.cancel(NOTIFICATION_ID)
        mediaSession.isActive = false
        mediaSession.release()
        artworkExecutor.shutdownNow()
    }

    private fun sendCommand(command: String, value: Long? = null) {
        val argument = value?.toString() ?: "null"
        webView?.post {
            webView?.evaluateJavascript(
                "window.__lumoCommand && window.__lumoCommand(${JSONObject.quote(command)}, $argument);",
                null
            )
        }
    }

    private fun acceptState(json: String) {
        val data = runCatching { JSONObject(json) }.getOrNull() ?: return
        activity.runOnUiThread {
            val newState = LumoPlaybackState(
                title = data.optString("title").trim().ifBlank { "Lecture LUMO" },
                subtitle = data.optString("subtitle").trim(),
                artworkUrl = data.optString("artwork").trim(),
                durationMs = data.optDouble("duration", 0.0).finiteSecondsToMs(),
                positionMs = data.optDouble("position", 0.0).finiteSecondsToMs(),
                playing = data.optBoolean("playing", false),
                videoWidth = data.optInt("videoWidth", 16).coerceAtLeast(1),
                videoHeight = data.optInt("videoHeight", 9).coerceAtLeast(1),
                mediaPresent = data.optBoolean("mediaPresent", true)
            )
            val artworkChanged = newState.artworkUrl.isNotBlank() && newState.artworkUrl != lastArtworkUrl
            if (newState.playing) notificationDismissed = false
            playbackState = newState
            publishState()
            if (artworkChanged) loadArtwork(newState.artworkUrl)
        }
    }

    private fun Double.finiteSecondsToMs(): Long =
        if (isFinite() && this > 0.0) (this * 1000.0).toLong() else 0L

    private fun publishState() {
        if (!playbackState.mediaPresent) {
            mediaSession.isActive = false
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }

        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SEEK_TO
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playbackState.playing) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    playbackState.positionMs,
                    if (playbackState.playing) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
        updateMetadata()
        mediaSession.isActive = true

        if (playbackState.playing) requestNotificationPermissionIfNeeded()
        if (!notificationDismissed) showNotification()
    }

    private fun updateMetadata() {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, playbackState.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, playbackState.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, playbackState.subtitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, playbackState.durationMs)
        artwork?.let {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }
        mediaSession.setMetadata(builder.build())
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(force: Boolean = false) {
        if (!canPostNotifications()) return
        val fingerprint = listOf(
            playbackState.title,
            playbackState.subtitle,
            playbackState.playing.toString(),
            playbackState.durationMs.toString(),
            lastArtworkUrl,
            (artwork != null).toString()
        ).joinToString("|")
        if (!force && fingerprint == lastNotificationFingerprint) return
        lastNotificationFingerprint = fingerprint
        val launchIntent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
        val contentIntent = PendingIntent.getActivity(
            activity,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = if (playbackState.playing) actionIntent(ACTION_PAUSE, 2)
        else actionIntent(ACTION_PLAY, 2)
        val playPauseIcon = if (playbackState.playing) android.R.drawable.ic_media_pause
        else android.R.drawable.ic_media_play
        val playPauseLabel = if (playbackState.playing) "Pause" else "Lecture"

        val notification = NotificationCompat.Builder(activity, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(playbackState.title)
            .setContentText(playbackState.subtitle.ifBlank {
                if (playbackState.playing) "Lecture en cours" else "En pause"
            })
            .setLargeIcon(artwork ?: defaultArtwork)
            .setContentIntent(contentIntent)
            .setDeleteIntent(actionIntent(ACTION_DISMISS, 5))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(playbackState.playing)
            .addAction(android.R.drawable.ic_media_previous, "Précédent", actionIntent(ACTION_PREVIOUS, 1))
            .addAction(playPauseIcon, playPauseLabel, playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Suivant", actionIntent(ACTION_NEXT, 3))
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            activity,
            requestCode,
            Intent(action).setPackage(activity.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !canPostNotifications() && !permissionRequested
        ) {
            permissionRequested = true
            activity.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lecture LUMO",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Commandes de lecture vidéo LUMO"
                setShowBadge(false)
            }
            activity.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createFallbackArtwork(): Bitmap {
        val original = BitmapFactory.decodeResource(activity.resources, R.drawable.lumo_logo)
        return Bitmap.createScaledBitmap(original, 384, 384, true).also {
            if (it !== original) original.recycle()
        }
    }

    private fun loadArtwork(url: String) {
        lastArtworkUrl = url
        artworkExecutor.execute {
            val loaded = runCatching { downloadArtwork(url) }.getOrNull() ?: return@execute
            activity.runOnUiThread {
                if (lastArtworkUrl == url) {
                    artwork?.takeIf { it !== loaded }?.recycle()
                    artwork = loaded
                    updateMetadata()
                    if (!notificationDismissed) showNotification(force = true)
                } else {
                    loaded.recycle()
                }
            }
        }
    }

    private fun downloadArtwork(address: String): Bitmap? {
        val uri = Uri.parse(address)
        if (uri.scheme != "https" && uri.scheme != "http") return null
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            CookieManager.getInstance().getCookie(address)?.let { setRequestProperty("Cookie", it) }
            setRequestProperty("User-Agent", webView?.settings?.userAgentString ?: "LUMO")
        }
        return try {
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_ARTWORK_BYTES) return null
                    output.write(buffer, 0, count)
                }
            }
            decodeScaledBitmap(output.toByteArray(), 768)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeScaledBitmap(bytes: ByteArray, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    inner class Bridge {
        @JavascriptInterface
        fun onMediaState(json: String) = acceptState(json)

        @JavascriptInterface
        fun openNativePlayer(detailsUrl: String) {
            val target = runCatching { Uri.parse(detailsUrl) }.getOrNull() ?: return
            if (target.scheme != "https" && target.scheme != "http") return
            activity.runOnUiThread {
                nativeAutoStartPending = true
                webView?.loadUrl(target.toString())
            }
        }
    }

    companion object {
        private val MEDIA_BRIDGE_SCRIPT = """
            (() => {
              if (window.__lumoBridgeInstalled) { window.__lumoScanMedia?.(); return; }
              window.__lumoBridgeInstalled = true;
              let currentMedia = null;
              let reportTimer = null;
              let scanTimer = null;

              const accessibleDocuments = () => {
                const documents = [document];
                document.querySelectorAll('iframe').forEach(frame => {
                  try { if (frame.contentDocument) documents.push(frame.contentDocument); } catch (_) {}
                });
                return documents;
              };

              const findMedia = () => {
                for (const doc of accessibleDocuments()) {
                  const media = doc.querySelector('video, audio');
                  if (media) return media;
                }
                return null;
              };

              const firstText = (...values) => {
                for (const value of values) {
                  const text = (value || '').toString().trim();
                  if (text) return text;
                }
                return '';
              };

              const absoluteUrl = (value) => {
                if (!value) return '';
                try { return new URL(value, document.baseURI).href; } catch (_) { return ''; }
              };

              const metadata = () => {
                const mediaDocument = currentMedia?.ownerDocument || document;
                const mediaWindow = mediaDocument.defaultView || window;
                const mediaMetadata = mediaWindow.navigator.mediaSession && mediaWindow.navigator.mediaSession.metadata;
                const artwork = mediaMetadata && mediaMetadata.artwork && mediaMetadata.artwork.length
                  ? mediaMetadata.artwork[mediaMetadata.artwork.length - 1].src : '';
                const title = firstText(
                  mediaMetadata && mediaMetadata.title,
                  mediaDocument.querySelector('.videoOsdTitle')?.textContent,
                  mediaDocument.querySelector('.nowPlayingBarText')?.textContent,
                  mediaDocument.querySelector('.itemName.infoText')?.textContent,
                  mediaDocument.querySelector('[data-title]')?.getAttribute('data-title'),
                  mediaDocument.querySelector('meta[property="og:title"]')?.content,
                  mediaDocument.title,
                  document.title
                );
                const subtitle = firstText(
                  mediaMetadata && mediaMetadata.artist,
                  mediaMetadata && mediaMetadata.album,
                  mediaDocument.querySelector('.videoOsdSecondaryText')?.textContent,
                  mediaDocument.querySelector('.nowPlayingBarSecondaryText')?.textContent
                );
                const image = firstText(
                  artwork,
                  currentMedia && currentMedia.poster,
                  mediaDocument.querySelector('meta[property="og:image"]')?.content,
                  mediaDocument.querySelector('.videoOsdBottom img')?.src,
                  mediaDocument.querySelector('.nowPlayingBar img')?.src
                );
                return { title, subtitle, artwork: absoluteUrl(image) };
              };

              const report = () => {
                if (!currentMedia || !currentMedia.ownerDocument?.contains(currentMedia)) currentMedia = findMedia();
                if (!currentMedia) {
                  LumoAndroid.onMediaState(JSON.stringify({ mediaPresent: false, playing: false }));
                  return;
                }
                let mediaRoute = '';
                try { mediaRoute = currentMedia.ownerDocument.defaultView.location.hash || ''; } catch (_) {}
                const hasPlayableSource = Boolean(currentMedia.currentSrc || currentMedia.src || mediaRoute.startsWith('#/video'));
                if (!hasPlayableSource) {
                  LumoAndroid.onMediaState(JSON.stringify({ mediaPresent: false, playing: false }));
                  return;
                }
                const info = metadata();
                LumoAndroid.onMediaState(JSON.stringify({
                  mediaPresent: true,
                  title: info.title,
                  subtitle: info.subtitle,
                  artwork: info.artwork,
                  duration: Number.isFinite(currentMedia.duration) ? currentMedia.duration : 0,
                  position: Number.isFinite(currentMedia.currentTime) ? currentMedia.currentTime : 0,
                  playing: !currentMedia.paused && !currentMedia.ended,
                  videoWidth: currentMedia.videoWidth || 16,
                  videoHeight: currentMedia.videoHeight || 9
                }));
              };

              const attach = (media) => {
                if (!media || media === currentMedia) return;
                currentMedia = media;
                ['play', 'pause', 'playing', 'ended', 'loadedmetadata', 'durationchange', 'seeked']
                  .forEach(event => media.addEventListener(event, report, { passive: true }));
                if (reportTimer) clearInterval(reportTimer);
                reportTimer = setInterval(report, 1000);
                report();
              };

              window.__lumoScanMedia = () => attach(findMedia());
              window.__lumoCommand = (command, value) => {
                const media = currentMedia || findMedia();
                if (command === 'play') { media?.play()?.catch(() => {}); return; }
                if (command === 'pause') { media?.pause(); return; }
                if (command === 'seek' && media && Number.isFinite(Number(value))) {
                  media.currentTime = Math.max(0, Math.min(Number(value) / 1000, media.duration || Number(value) / 1000));
                  report();
                  return;
                }
                const nextSelectors = [
                  '.btnNextTrack', '.btnNextItem', '[data-action="next"]',
                  'button[aria-label*="Next" i]', 'button[aria-label*="Suivant" i]'
                ];
                const previousSelectors = [
                  '.btnPreviousTrack', '.btnPreviousItem', '[data-action="previous"]',
                  'button[aria-label*="Previous" i]', 'button[aria-label*="Précédent" i]'
                ];
                const selectors = command === 'next' ? nextSelectors : previousSelectors;
                const mediaDocument = media?.ownerDocument || document;
                const button = selectors.map(selector => mediaDocument.querySelector(selector)).find(Boolean);
                if (button) button.click();
              };

              document.addEventListener('click', event => {
                const trigger = event.target.closest?.('[data-action="play"][data-id]');
                if (!trigger) return;
                window.__lumoFallbackOpening = false;
                setTimeout(() => {
                  const activeMedia = findMedia();
                  if (window.__lumoFallbackOpening || (activeMedia && !activeMedia.paused && !activeMedia.ended)) return;
                  const layer = document.querySelector('#nativePlayerOverlay.preparing');
                  const frame = layer?.querySelector('iframe');
                  if (!layer || !frame) return;
                  let frameHash = '';
                  try { frameHash = frame.contentWindow.location.hash || ''; } catch (_) {}
                  if (frameHash.startsWith('#/video')) return;
                  const detailsUrl = frame.src || '';
                  if (!detailsUrl || !detailsUrl.includes('/web/#/details')) return;
                  window.__lumoFallbackOpening = true;
                  try { window.__cinematicClosePlayer?.(false); } catch (_) {}
                  LumoAndroid.openNativePlayer(detailsUrl);
                }, 3500);
              }, true);

              new MutationObserver(window.__lumoScanMedia).observe(document.documentElement, {
                childList: true, subtree: true
              });
              scanTimer = setInterval(window.__lumoScanMedia, 500);
              window.__lumoScanMedia();
            })();
        """.trimIndent()

        private val NATIVE_AUTOPLAY_SCRIPT = """
            (() => {
              if (window.__lumoNativeAutoStart) return;
              window.__lumoNativeAutoStart = true;
              let attempts = 0;
              const timer = setInterval(() => {
                attempts += 1;
                if (location.hash.startsWith('#/video')) {
                  clearInterval(timer);
                  return;
                }
                const button = [...document.querySelectorAll('button:not([disabled])')].find(element => {
                  const name = (element.textContent || element.getAttribute('aria-label') || '')
                    .trim().toLocaleLowerCase('fr');
                  return element.getClientRects().length && (
                    element.matches('.btnPlayOrResume,.btnPlay,.btnReplay,[data-action="resume"],[data-action="play"]') ||
                    name === 'lire' || name === 'reprendre'
                  );
                });
                if (button) {
                  button.focus();
                  button.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
                  button.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
                  button.click();
                }
                if (attempts >= 150) clearInterval(timer);
              }, 100);
            })();
        """.trimIndent()
    }
}
