package com.markhansen.neverrepeat;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;

import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getBridge().getWebView().addJavascriptInterface(new RRBridge(this), "RRNative");
        ContextCompat.startForegroundService(this, new Intent(this, RRBackgroundService.class));
    }

    public static class RRBridge {
        private final MainActivity activity;
        private final Context context;
        private static MediaPlayer localPlayer;

        RRBridge(MainActivity activity) {
            this.activity = activity;
            this.context = activity;
        }

        @JavascriptInterface
        public void syncState(String json) {
            context.getSharedPreferences("rr", 0).edit().putString("state", json).apply();
            try {
                ContextCompat.startForegroundService(
                    context,
                    new Intent(context, RRBackgroundService.class).setAction("rr.SYNC")
                );
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public String drainPlayed() {
            android.content.SharedPreferences prefs = context.getSharedPreferences("rr", 0);
            String value = prefs.getString("observed", "[]");
            prefs.edit().putString("observed", "[]").apply();
            return value;
        }

        private boolean hasAudioPermission() {
            if (Build.VERSION.SDK_INT >= 33) {
                return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            }
            if (Build.VERSION.SDK_INT >= 23) {
                return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        @JavascriptInterface
        public void requestAudioPermission() {
            if (hasAudioPermission()) return;
            activity.runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    activity.requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, 4101);
                } else if (Build.VERSION.SDK_INT >= 23) {
                    activity.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 4101);
                }
            });
        }

        @JavascriptInterface
        public String scanMusicFiles() {
            JSONObject result = new JSONObject();
            JSONArray files = new JSONArray();
            try {
                if (!hasAudioPermission()) {
                    result.put("permissionNeeded", true);
                    result.put("files", files);
                    return result.toString();
                }

                Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String[] projection;
                if (Build.VERSION.SDK_INT >= 29) {
                    projection = new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.RELATIVE_PATH
                    };
                } else {
                    projection = new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM
                    };
                }

                Cursor cursor = context.getContentResolver().query(
                    collection,
                    projection,
                    MediaStore.Audio.Media.IS_MUSIC + "!=0",
                    null,
                    MediaStore.Audio.Media.DATE_ADDED + " DESC"
                );

                if (cursor != null) {
                    int idIndex = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                    int nameIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
                    int durationIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                    int artistIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                    int albumIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                    int pathIndex = Build.VERSION.SDK_INT >= 29
                        ? cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                        : -1;

                    while (cursor.moveToNext()) {
                        if (Build.VERSION.SDK_INT >= 29) {
                            String relativePath = pathIndex >= 0 ? cursor.getString(pathIndex) : "";
                            String normalized = relativePath == null
                                ? ""
                                : relativePath.replace('\\', '/').toLowerCase(java.util.Locale.US);
                            if (!normalized.contains("music/neverrepeat/")
                                && !normalized.endsWith("music/neverrepeat")) {
                                continue;
                            }
                        }

                        long id = cursor.getLong(idIndex);
                        JSONObject item = new JSONObject();
                        item.put("id", id);
                        item.put("uri", ContentUris.withAppendedId(collection, id).toString());
                        item.put("name", nameIndex >= 0 ? cursor.getString(nameIndex) : "Local Track");
                        item.put("duration_ms", durationIndex >= 0 ? cursor.getLong(durationIndex) : 0);

                        String artist = artistIndex >= 0 ? cursor.getString(artistIndex) : "";
                        String album = albumIndex >= 0 ? cursor.getString(albumIndex) : "";
                        item.put(
                            "artist",
                            artist == null || artist.equals("<unknown>") ? "My Music Files" : artist
                        );
                        item.put("album", album == null ? "" : album);
                        files.put(item);
                    }
                    cursor.close();
                }

                result.put("permissionNeeded", false);
                result.put("files", files);
                return result.toString();
            } catch (Exception e) {
                try {
                    result.put("permissionNeeded", false);
                    result.put("files", files);
                    result.put("error", String.valueOf(e.getMessage()));
                } catch (Exception ignored) {}
                return result.toString();
            }
        }

        @JavascriptInterface
        public String playLocal(String uri) {
            try {
                stopLocal();
                localPlayer = new MediaPlayer();
                localPlayer.setDataSource(context, Uri.parse(uri));
                localPlayer.setOnCompletionListener(mp -> {
                    try {
                        context.getSharedPreferences("rr", 0)
                            .edit()
                            .putString("localEvent", "ended")
                            .apply();
                    } catch (Exception ignored) {}
                });
                localPlayer.prepare();
                localPlayer.start();
                return "{\"ok\":true}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
        }

        @JavascriptInterface
        public void stopLocal() {
            try {
                if (localPlayer != null) {
                    localPlayer.stop();
                    localPlayer.release();
                    localPlayer = null;
                }
            } catch (Exception e) {
                localPlayer = null;
            }
        }

        @JavascriptInterface
        public String localStatus() {
            try {
                JSONObject state = new JSONObject();
                state.put("playing", localPlayer != null && localPlayer.isPlaying());
                state.put("position", localPlayer == null ? 0 : localPlayer.getCurrentPosition());
                state.put("duration", localPlayer == null ? 0 : localPlayer.getDuration());
                String event = context.getSharedPreferences("rr", 0).getString("localEvent", "");
                state.put("event", event);
                if (!event.isEmpty()) {
                    context.getSharedPreferences("rr", 0).edit().putString("localEvent", "").apply();
                }
                return state.toString();
            } catch (Exception e) {
                return "{\"playing\":false}";
            }
        }

        @JavascriptInterface
        public void openSpotify() {
            try {
                PackageManager pm = context.getPackageManager();
                Intent launch = pm.getLaunchIntentForPackage("com.spotify.music");
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launch);
                    return;
                }
                Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com"));
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(web);
            } catch (Exception ignored) {}
        }
    }
}
