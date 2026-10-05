package com.markhansen.neverrepeat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class RRBackgroundService extends MediaBrowserServiceCompat {
    private static final String CHANNEL_ID = "rr_background";
    private static final String ROOT_ID = "rr_root";
    private static final int NOTIFICATION_ID = 35;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private MediaSessionCompat mediaSession;

    private String lastId = "";
    private JSONObject lastTrack = null;
    private long lastPosition = 0;
    private long lastGoodPosition = 0;
    private boolean lastPlaying = false;
    private boolean advanceLock = false;

    private final Runnable pulse = new Runnable() {
        @Override
        public void run() {
            new Thread(() -> pollSpotify()).start();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(
            new NotificationChannel(
                CHANNEL_ID,
                "NeveRRepeat playback",
                NotificationManager.IMPORTANCE_LOW
            )
        );

        mediaSession = new MediaSessionCompat(this, "NeveRRepeat");
        setSessionToken(mediaSession.getSessionToken());

        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        mediaSession.setSessionActivity(contentIntent);
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { runCommand("rr.PLAY"); }
            @Override public void onPause() { runCommand("rr.PAUSE"); }
            @Override public void onSkipToNext() { runCommand("rr.NEXT"); }
            @Override public void onSkipToPrevious() { runCommand("rr.PREV"); }

            @Override
            public void onPlayFromMediaId(String mediaId, Bundle extras) {
                new Thread(() -> playFromQueue(mediaId)).start();
            }
        });
        mediaSession.setActive(true);

        startForeground(
            NOTIFICATION_ID,
            buildNotification("NeveRRepeat", "Remembered Rotation", false)
        );

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NeveRRepeat:RR"
        );
        wakeLock.acquire();

        handler.post(pulse);
    }

    private void runCommand(String action) {
        startService(new Intent(this, RRBackgroundService.class).setAction(action));
    }

    private PendingIntent actionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, RRBackgroundService.class).setAction(action);
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    private Notification buildNotification(String title, String artist, boolean playing) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(getApplicationInfo().icon)
            .setContentTitle(title == null || title.isEmpty() ? "NeveRRepeat" : title)
            .setContentText(artist == null || artist.isEmpty() ? "Remembered Rotation" : artist)
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                actionIntent("rr.PREV", 1)
            )
            .addAction(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Pause" : "Play",
                actionIntent(playing ? "rr.PAUSE" : "rr.PLAY", 2)
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                actionIntent("rr.NEXT", 3)
            )
            .setStyle(
                new MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build();
    }

    private JSONObject state(android.content.SharedPreferences prefs) {
        try {
            return new JSONObject(prefs.getString("state", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String refreshAccess(android.content.SharedPreferences prefs, JSONObject state) {
        try {
            String refresh = state.optString("refresh", "");
            String clientId = state.optString("clientId", "");
            if (refresh.isEmpty() || clientId.isEmpty()) return "";

            String body =
                "client_id=" + URLEncoder.encode(clientId, "UTF-8")
                    + "&grant_type=refresh_token&refresh_token="
                    + URLEncoder.encode(refresh, "UTF-8");

            HttpURLConnection request =
                (HttpURLConnection) new URL("https://accounts.spotify.com/api/token")
                    .openConnection();
            request.setRequestMethod("POST");
            request.setDoOutput(true);
            request.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded"
            );

            try (OutputStream output = request.getOutputStream()) {
                output.write(body.getBytes("UTF-8"));
            }

            if (request.getResponseCode() != 200) return "";

            BufferedReader reader =
                new BufferedReader(new InputStreamReader(request.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);

            JSONObject json = new JSONObject(response.toString());
            String token = json.optString("access_token", "");
            if (!token.isEmpty()) {
                state.put("token", token);
                prefs.edit().putString("state", state.toString()).apply();
            }
            return token;
        } catch (Exception e) {
            return "";
        }
    }

    private boolean spotifyCommand(String method, String path, String body) {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("rr", 0);
            JSONObject state = state(prefs);
            String token = state.optString("token", "");
            if (token.isEmpty()) return false;

            for (int attempt = 0; attempt < 2; attempt++) {
                HttpURLConnection request =
                    (HttpURLConnection) new URL(
                        "https://api.spotify.com/v1" + path
                    ).openConnection();
                request.setRequestMethod(method);
                request.setRequestProperty("Authorization", "Bearer " + token);
                request.setConnectTimeout(5000);
                request.setReadTimeout(5000);

                if (body != null) {
                    request.setDoOutput(true);
                    request.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream output = request.getOutputStream()) {
                        output.write(body.getBytes("UTF-8"));
                    }
                }

                int code = request.getResponseCode();
                if (code == 401 && attempt == 0) {
                    token = refreshAccess(prefs, state);
                    if (token.isEmpty()) return false;
                    continue;
                }
                return code >= 200 && code < 300;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean spotifyPlayUri(String uri) {
        try {
            JSONObject body = new JSONObject();
            body.put("uris", new JSONArray().put(uri));
            return spotifyCommand("PUT", "/me/player/play", body.toString());
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject trackFrom(JSONObject item, long position, boolean playing) {
        try {
            JSONObject track = new JSONObject();
            track.put("id", item.optString("id", ""));
            track.put("name", item.optString("name", ""));

            JSONArray artists = item.optJSONArray("artists");
            StringBuilder artistNames = new StringBuilder();
            if (artists != null) {
                for (int i = 0; i < artists.length(); i++) {
                    JSONObject artist = artists.optJSONObject(i);
                    if (artist == null) continue;
                    if (artistNames.length() > 0) artistNames.append(", ");
                    artistNames.append(artist.optString("name", ""));
                }
            }
            track.put("artist", artistNames.toString());

            JSONObject album = item.optJSONObject("album");
            track.put("album", album == null ? "" : album.optString("name", ""));
            if (album != null) {
                JSONArray images = album.optJSONArray("images");
                if (images != null && images.length() > 0) {
                    JSONObject image = images.optJSONObject(0);
                    if (image != null) track.put("image", image.optString("url", ""));
                }
            }

            track.put("duration_ms", item.optLong("duration_ms", 0));
            JSONObject externalIds = item.optJSONObject("external_ids");
            track.put(
                "isrc",
                externalIds == null ? "" : externalIds.optString("isrc", "")
            );
            track.put("position_ms", position);
            track.put("isPlaying", playing);
            return track;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void updateSession(JSONObject item, long position, boolean playing) {
        try {
            JSONArray artists = item.optJSONArray("artists");
            StringBuilder artistNames = new StringBuilder();
            if (artists != null) {
                for (int i = 0; i < artists.length(); i++) {
                    JSONObject artist = artists.optJSONObject(i);
                    if (artist == null) continue;
                    if (artistNames.length() > 0) artistNames.append(", ");
                    artistNames.append(artist.optString("name", ""));
                }
            }

            JSONObject album = item.optJSONObject("album");
            String albumName = album == null ? "" : album.optString("name", "");

            MediaMetadataCompat.Builder metadata =
                new MediaMetadataCompat.Builder()
                    .putString(
                        MediaMetadataCompat.METADATA_KEY_MEDIA_ID,
                        item.optString("id", "")
                    )
                    .putString(
                        MediaMetadataCompat.METADATA_KEY_TITLE,
                        item.optString("name", "NeveRRepeat")
                    )
                    .putString(
                        MediaMetadataCompat.METADATA_KEY_ARTIST,
                        artistNames.toString()
                    )
                    .putString(
                        MediaMetadataCompat.METADATA_KEY_ALBUM,
                        albumName
                    )
                    .putLong(
                        MediaMetadataCompat.METADATA_KEY_DURATION,
                        item.optLong("duration_ms", 0)
                    );

            if (album != null) {
                JSONArray images = album.optJSONArray("images");
                if (images != null && images.length() > 0) {
                    JSONObject image = images.optJSONObject(0);
                    if (image != null) {
                        metadata.putString(
                            MediaMetadataCompat.METADATA_KEY_ART_URI,
                            image.optString("url", "")
                        );
                    }
                }
            }

            mediaSession.setMetadata(metadata.build());

            long actions =
                PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID;

            mediaSession.setPlaybackState(
                new PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(
                        playing
                            ? PlaybackStateCompat.STATE_PLAYING
                            : PlaybackStateCompat.STATE_PAUSED,
                        position,
                        playing ? 1f : 0f,
                        SystemClock.elapsedRealtime()
                    )
                    .build()
            );

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    item.optString("name", "NeveRRepeat"),
                    artistNames.toString(),
                    playing
                )
            );
        } catch (Exception ignored) {}
    }

    private void refreshQueue(JSONObject state) {
        try {
            JSONArray queue = state.optJSONArray("queue");
            if (queue == null) return;

            List<MediaSessionCompat.QueueItem> items = new ArrayList<>();
            long queueId = 1;
            for (int i = 0; i < Math.min(100, queue.length()); i++) {
                JSONObject track = queue.optJSONObject(i);
                if (track == null) continue;

                MediaDescriptionCompat description =
                    new MediaDescriptionCompat.Builder()
                        .setMediaId(track.optString("id", ""))
                        .setTitle(track.optString("name", ""))
                        .setSubtitle(track.optString("artist", ""))
                        .build();

                items.add(
                    new MediaSessionCompat.QueueItem(description, queueId++)
                );
            }

            mediaSession.setQueue(items);
            mediaSession.setQueueTitle("Remembered Rotation");
        } catch (Exception ignored) {}
    }

    @Override
    public BrowserRoot onGetRoot(
        String clientPackageName,
        int clientUid,
        Bundle rootHints
    ) {
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(
        String parentId,
        Result<List<MediaBrowserCompat.MediaItem>> result
    ) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();

        if (ROOT_ID.equals(parentId)) {
            try {
                JSONObject state = state(getSharedPreferences("rr", 0));
                JSONArray queue = state.optJSONArray("queue");
                if (queue != null) {
                    for (int i = 0; i < Math.min(100, queue.length()); i++) {
                        JSONObject track = queue.optJSONObject(i);
                        if (track == null) continue;

                        MediaDescriptionCompat description =
                            new MediaDescriptionCompat.Builder()
                                .setMediaId(track.optString("id", ""))
                                .setTitle(track.optString("name", ""))
                                .setSubtitle(track.optString("artist", ""))
                                .build();

                        items.add(
                            new MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                            )
                        );
                    }
                }
            } catch (Exception ignored) {}
        }

        result.sendResult(items);
    }

    private void playFromQueue(String mediaId) {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("rr", 0);
            JSONObject state = state(prefs);
            JSONArray queue = state.optJSONArray("queue");
            if (queue == null) return;

            for (int i = 0; i < queue.length(); i++) {
                JSONObject track = queue.optJSONObject(i);
                if (track == null || !mediaId.equals(track.optString("id", ""))) {
                    continue;
                }

                String uri = track.optString("uri", "");
                if (uri.isEmpty()) uri = "spotify:track:" + mediaId;

                if (spotifyPlayUri(uri)) {
                    state.put("currentId", mediaId);
                    prefs.edit().putString("state", state.toString()).apply();
                }
                return;
            }
        } catch (Exception ignored) {}
    }

    private void pollSpotify() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("rr", 0);
            JSONObject state = state(prefs);
            String token = state.optString("token", "");
            if (token.isEmpty()) return;

            HttpURLConnection request =
                (HttpURLConnection) new URL(
                    "https://api.spotify.com/v1/me/player"
                ).openConnection();
            request.setRequestProperty("Authorization", "Bearer " + token);
            request.setConnectTimeout(5000);
            request.setReadTimeout(5000);

            int code = request.getResponseCode();
            if (code == 401) {
                token = refreshAccess(prefs, state);
                if (!token.isEmpty()) {
                    lastId = "";
                    lastTrack = null;
                }
                return;
            }
            if (code == 204) {
                recoverNoPlayerState(
                    prefs,
                    state,
                    "native_no_player_state_204"
                );
                return;
            }
            if (code != 200) {
                try {
                    JSONObject event = new JSONObject();
                    event.put("status", code);
                    event.put("last_id", lastId);
                    event.put("last_position_ms", lastGoodPosition);
                    appendEvent(prefs, "BACKGROUND_PLAYER_HTTP_STATE", event);
                } catch (Exception ignored) {}
                return;
            }

            BufferedReader reader =
                new BufferedReader(new InputStreamReader(request.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);

            JSONObject player = new JSONObject(response.toString());
            JSONObject item = player.optJSONObject("item");
            if (item == null) {
                recoverNoPlayerState(
                    prefs,
                    state,
                    "native_no_player_item_200"
                );
                return;
            }

            String id = item.optString("id", "");
            long position = player.optLong("progress_ms", 0);
            boolean playing = player.optBoolean("is_playing", false);
            if (id.isEmpty()) return;

            updateSession(item, position, playing);
            refreshQueue(state);

            JSONObject now = trackFrom(item, position, playing);

            if (lastId.isEmpty()) {
                lastId = id;
                lastTrack = now;
                lastPosition = position;
                lastGoodPosition = position;
                lastPlaying = playing;
                appendEvent(prefs, "BACKGROUND_ECU_ATTACH", now);
                return;
            }

            if (!id.equals(lastId)) {
                JSONObject ended =
                    lastTrack == null
                        ? new JSONObject()
                        : new JSONObject(lastTrack.toString());

                ended.put(
                    "endedAt",
                    new java.text.SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        java.util.Locale.US
                    ) {{
                        setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                    }}.format(new java.util.Date())
                );
                ended.put("last_position_ms", lastPosition);
                ended.put("next_id", id);
                ended.put("next_name", now.optString("name", ""));
                ended.put("reason", "spotify_track_transition");

                appendObserved(prefs, ended);
                appendEvent(prefs, "BACKGROUND_TRACK_TRANSITION", ended);

                lastId = id;
                lastTrack = now;
                lastPosition = position;
                lastGoodPosition = position;
                lastPlaying = playing;
                return;
            }

            lastTrack = now;
            if (position > lastGoodPosition) lastGoodPosition = position;

            long duration = now.optLong("duration_ms", 0);
            boolean nearEnd =
                duration > 0
                    && lastGoodPosition >= Math.max(20000, duration - 9000);

            if (!playing && lastPlaying && nearEnd) {
                JSONObject ended = new JSONObject(now.toString());
                ended.put("last_position_ms", lastGoodPosition);
                ended.put("reason", "native_end_watchdog");

                appendObserved(prefs, ended);
                appendEvent(prefs, "BACKGROUND_TRACK_COMPLETED", ended);
                nativeAdvance(prefs, state, "native_end_watchdog");
            }

            lastPosition = position;
            lastPlaying = playing;
        } catch (Exception ignored) {}
    }

    private void recoverNoPlayerState(
        android.content.SharedPreferences prefs,
        JSONObject state,
        String reason
    ) {
        try {
            long duration =
                lastTrack == null ? 0 : lastTrack.optLong("duration_ms", 0);
            boolean nearEnd =
                duration > 0
                    && lastGoodPosition >= Math.max(20000, duration - 9000);

            JSONObject event = new JSONObject();
            event.put("reason", reason);
            event.put("last_id", lastId);
            event.put(
                "last_name",
                lastTrack == null ? "" : lastTrack.optString("name", "")
            );
            event.put("last_position_ms", lastGoodPosition);
            event.put("duration_ms", duration);
            event.put("last_playing", lastPlaying);
            event.put("near_end", nearEnd);

            if (lastPlaying && nearEnd) {
                JSONObject ended =
                    lastTrack == null
                        ? new JSONObject()
                        : new JSONObject(lastTrack.toString());
                ended.put("last_position_ms", lastGoodPosition);
                ended.put("reason", reason);

                appendObserved(prefs, ended);
                appendEvent(
                    prefs,
                    "BACKGROUND_NO_STATE_RECOVERY_ADVANCE",
                    event
                );
                nativeAdvance(prefs, state, reason);
            } else {
                appendEvent(
                    prefs,
                    "BACKGROUND_NO_STATE_IGNORED",
                    event
                );
            }
        } catch (Exception ignored) {}
    }

    private void nativeAdvance(
        android.content.SharedPreferences prefs,
        JSONObject state,
        String reason
    ) {
        if (advanceLock) return;

        try {
            JSONArray queue = state.optJSONArray("queue");
            if (queue == null || queue.length() == 0) return;

            advanceLock = true;
            String stateCurrentId = state.optString("currentId", "");
            int nextIndex = 0;
            JSONObject next = null;
            while (nextIndex < queue.length()) {
                JSONObject candidate = queue.optJSONObject(nextIndex);
                String candidateId =
                    candidate == null ? "" : candidate.optString("id", "");
                boolean sameAsCurrent =
                    !candidateId.isEmpty()
                        && (
                            candidateId.equals(lastId)
                                || candidateId.equals(stateCurrentId)
                        );
                if (candidate != null && !candidateId.isEmpty() && !sameAsCurrent) {
                    next = candidate;
                    break;
                }

                JSONObject skipped = new JSONObject();
                skipped.put("reason", reason);
                skipped.put("candidate_id", candidateId);
                skipped.put("spotify_current_id", lastId);
                skipped.put("state_current_id", stateCurrentId);
                appendEvent(prefs, "BACKGROUND_QUEUE_SELF_SKIP", skipped);
                nextIndex++;
            }
            if (next == null) return;

            String uri = next.optString("uri", "");
            if (uri.isEmpty()) {
                uri = "spotify:track:" + next.optString("id", "");
            }

            JSONObject event = new JSONObject();
            event.put("reason", reason);
            event.put("previous_id", lastId);
            event.put(
                "previous_name",
                lastTrack == null ? "" : lastTrack.optString("name", "")
            );
            event.put("next_id", next.optString("id", ""));
            event.put("next_name", next.optString("name", ""));
            event.put("queueCount", queue.length());

            appendEvent(prefs, "BACKGROUND_AUTO_ADVANCE_START", event);

            if (spotifyPlayUri(uri)) {
                JSONArray newQueue = new JSONArray();
                for (int i = nextIndex + 1; i < queue.length(); i++) {
                    newQueue.put(queue.get(i));
                }

                state.put("queue", newQueue);
                state.put("currentId", next.optString("id", ""));
                prefs.edit().putString("state", state.toString()).apply();

                appendEvent(prefs, "BACKGROUND_SPOTIFY_PLAY_SUCCESS", event);
                lastId = next.optString("id", "");
                lastTrack = next;
                lastPosition = 0;
                lastGoodPosition = 0;
                lastPlaying = true;
                refreshQueue(state);
            } else {
                appendEvent(prefs, "BACKGROUND_SPOTIFY_PLAY_FAILED", event);
            }
        } catch (Exception ignored) {
        } finally {
            handler.postDelayed(() -> advanceLock = false, 2500);
        }
    }

    private void appendObserved(
        android.content.SharedPreferences prefs,
        JSONObject row
    ) {
        try {
            JSONArray rows = new JSONArray(prefs.getString("observed", "[]"));
            rows.put(row);
            while (rows.length() > 500) rows.remove(0);
            prefs.edit().putString("observed", rows.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void appendEvent(
        android.content.SharedPreferences prefs,
        String type,
        JSONObject data
    ) {
        try {
            JSONArray rows = new JSONArray(prefs.getString("nativeLog", "[]"));
            JSONObject row = new JSONObject();
            row.put("at", System.currentTimeMillis());
            row.put("event", type);
            row.put("data", data);
            rows.put(row);
            while (rows.length() > 1000) rows.remove(0);
            prefs.edit().putString("nativeLog", rows.toString()).apply();
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (action != null) {
            new Thread(() -> {
                if ("rr.PLAY".equals(action)) {
                    spotifyCommand("PUT", "/me/player/play", null);
                } else if ("rr.PAUSE".equals(action)) {
                    spotifyCommand("PUT", "/me/player/pause", null);
                } else if ("rr.PREV".equals(action)) {
                    spotifyCommand("POST", "/me/player/previous", null);
                } else if ("rr.NEXT".equals(action)) {
                    android.content.SharedPreferences prefs =
                        getSharedPreferences("rr", 0);
                    JSONObject state = state(prefs);
                    try {
                        JSONObject mediaEvent = new JSONObject();
                        mediaEvent.put("last_id", lastId);
                        mediaEvent.put("state_current_id", state.optString("currentId", ""));
                        appendEvent(prefs, "MEDIA_BUTTON_NEXT_RECEIVED", mediaEvent);
                    } catch (Exception ignored) {}
                    nativeAdvance(prefs, state, "android_auto_next");
                } else if ("rr.SYNC".equals(action)) {
                    refreshQueue(state(getSharedPreferences("rr", 0)));
                }
            }).start();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(pulse);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
