package co.pixelwood.pasta;

import org.json.JSONObject;

/** A text or image paste. */
public final class Clip {
    public final String id;
    public final String kind;
    public final String text;
    public final String device;
    public final long createdAt;
    public final boolean pinned;

    public final String imageUrl;
    public final String imageName;
    public final String imageMime;
    public final long imageSize;
    public final int imageWidth;
    public final int imageHeight;

    private Clip(String id, String kind, String text, String device, long createdAt, boolean pinned,
                 String imageUrl, String imageName, String imageMime, long imageSize, int imageWidth, int imageHeight) {
        this.id = id;
        this.kind = kind;
        this.text = text;
        this.device = device;
        this.createdAt = createdAt;
        this.pinned = pinned;
        this.imageUrl = imageUrl;
        this.imageName = imageName;
        this.imageMime = imageMime;
        this.imageSize = imageSize;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    public static Clip fromJson(JSONObject json) {
        if (json == null) return null;
        String kind = json.optString("kind", "text");
        JSONObject image = json.optJSONObject("image");
        return new Clip(
                json.optString("id", ""),
                kind,
                json.optString("text", null),
                json.optString("device", "Unknown device"),
                json.optLong("createdAt", System.currentTimeMillis()),
                json.optBoolean("pinned", false),
                image == null ? null : image.optString("url", null),
                image == null ? null : image.optString("name", "image"),
                image == null ? null : image.optString("mime", "image/*"),
                image == null ? 0 : image.optLong("size", 0),
                image == null ? 0 : image.optInt("width", 0),
                image == null ? 0 : image.optInt("height", 0)
        );
    }

    public boolean isImage() {
        return "image".equals(kind);
    }
}
