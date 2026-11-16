package com.gathin.tvhome;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Data model for cards in horizontal content shelves (Stremio Continue Watching & SmartTube Subscriptions).
 */
public class MediaShelfItem {
    public final String id;
    public final String title;
    public final String subtitle;
    public final String posterUrl;
    public final int progressPercent;
    public final String intentUri;
    public final String targetPackage;
    public final boolean isWidescreen;

    public MediaShelfItem(String id, String title, String subtitle, String posterUrl,
                          int progressPercent, String intentUri, String targetPackage, boolean isWidescreen) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.posterUrl = posterUrl;
        this.progressPercent = progressPercent;
        this.intentUri = intentUri;
        this.targetPackage = targetPackage;
        this.isWidescreen = isWidescreen;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("title", title);
            obj.put("subtitle", subtitle);
            obj.put("posterUrl", posterUrl);
            obj.put("progressPercent", progressPercent);
            obj.put("intentUri", intentUri);
            obj.put("targetPackage", targetPackage);
            obj.put("isWidescreen", isWidescreen);
        } catch (JSONException ignored) {}
        return obj;
    }

    public static MediaShelfItem fromJson(JSONObject obj) {
        return new MediaShelfItem(
            obj.optString("id", ""),
            obj.optString("title", ""),
            obj.optString("subtitle", ""),
            obj.optString("posterUrl", ""),
            obj.optInt("progressPercent", 0),
            obj.optString("intentUri", ""),
            obj.optString("targetPackage", ""),
            obj.optBoolean("isWidescreen", false)
        );
    }
}
