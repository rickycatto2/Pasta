package co.pixelwood.pasta;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Tiny JSON-over-HTTPS client for the Pasta API. */
public final class ApiClient {
    public static class ApiException extends Exception {
        public final int status;
        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class Unauthorized extends ApiException {
        Unauthorized(String message) {
            super(401, message);
        }
    }

    private String baseUrl;
    private String token;

    public ApiClient(String baseUrl, String token) {
        this.baseUrl = normalizeBase(baseUrl);
        this.token = token;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public static String normalizeBase(String input) {
        if (input == null) throw new IllegalArgumentException("Enter the Pasta server URL.");
        String value = input.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) throw new IllegalArgumentException("Enter the Pasta server URL.");
        if (!value.startsWith("https://")) {
            throw new IllegalArgumentException("Use the https:// address of your Pasta tunnel.");
        }
        try {
            URL url = new URL(value);
            if (url.getHost() == null || url.getHost().isEmpty()) throw new IllegalArgumentException("That URL has no host.");
        } catch (Exception error) {
            throw new IllegalArgumentException("That server URL does not look right.");
        }
        return value;
    }

    public JSONObject login(String password, String label) throws IOException, JSONException, ApiException {
        JSONObject body = new JSONObject();
        body.put("password", password);
        body.put("label", label);
        HttpResult result = request("POST", "/api/auth/login", body.toString().getBytes(StandardCharsets.UTF_8), "application/json", false);
        JSONObject json = new JSONObject(result.body);
        token = json.optString("token", null);
        if (token == null || token.isEmpty()) throw new ApiException(500, "The server did not return a session.");
        return json;
    }

    public JSONArray clips() throws IOException, JSONException, ApiException {
        HttpResult result = request("GET", "/api/clips?limit=60", null, null, true);
        JSONObject json = new JSONObject(result.body);
        return json.optJSONArray("clips");
    }

    public JSONObject sendText(String text, String device) throws IOException, JSONException, ApiException {
        JSONObject body = new JSONObject();
        body.put("text", text);
        body.put("device", device);
        HttpResult result = request("POST", "/api/clips", body.toString().getBytes(StandardCharsets.UTF_8), "application/json", true);
        return new JSONObject(result.body).optJSONObject("clip");
    }

    public JSONObject sendImage(File file, String mime, String name, int width, int height, String device)
            throws IOException, JSONException, ApiException {
        HttpURLConnection connection = open("/api/clips/image", "POST", true);
        connection.setRequestProperty("Content-Type", mime);
        connection.setRequestProperty("X-Pasta-Filename", uriEncode(name));
        connection.setRequestProperty("X-Pasta-Width", String.valueOf(width));
        connection.setRequestProperty("X-Pasta-Height", String.valueOf(height));
        connection.setRequestProperty("X-Pasta-Device", asciiHeader(device));
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(file.length());
        try (OutputStream output = connection.getOutputStream(); FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
        HttpResult result = read(connection);
        return new JSONObject(result.body).optJSONObject("clip");
    }

    public JSONObject setPinned(String id, boolean pinned) throws IOException, JSONException, ApiException {
        JSONObject body = new JSONObject();
        body.put("pinned", pinned);
        HttpResult result = request("PATCH", "/api/clips/" + uriEncode(id), body.toString().getBytes(StandardCharsets.UTF_8), "application/json", true);
        return new JSONObject(result.body).optJSONObject("clip");
    }

    public void delete(String id) throws IOException, JSONException, ApiException {
        request("DELETE", "/api/clips/" + uriEncode(id), null, null, true);
    }

    public JSONObject me() throws IOException, JSONException, ApiException {
        HttpResult result = request("GET", "/api/auth/me", null, null, true);
        return new JSONObject(result.body);
    }

    public byte[] image(String id) throws IOException, ApiException {
        HttpURLConnection connection = open("/api/clips/" + uriEncode(id) + "/content", "GET", true);
        int code = connection.getResponseCode();
        if (code == 401) throw new Unauthorized("Your session has expired.");
        if (code != 200) {
            String message = readError(connection, code);
            throw new ApiException(code, message);
        }
        try (InputStream input = connection.getInputStream()) {
            return readAll(input);
        } finally {
            connection.disconnect();
        }
    }

    private HttpResult request(String method, String path, byte[] body, String contentType, boolean authenticated)
            throws IOException, JSONException, ApiException {
        HttpURLConnection connection = open(path, method, authenticated);
        if (contentType != null) connection.setRequestProperty("Content-Type", contentType);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }
        return read(connection);
    }

    private HttpURLConnection open(String path, String method, boolean authenticated) throws IOException, Unauthorized {
        if (baseUrl == null) throw new IOException("No server configured.");
        URL url = new URL(baseUrl + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "PastaAndroid/1.0");
        connection.setRequestProperty("X-Pasta-Client", "android");
        if (authenticated) {
            if (token == null) throw new Unauthorized("Please sign in.");
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (!"GET".equals(method)) connection.setRequestProperty("X-Pasta", "1");
        return connection;
    }

    private HttpResult read(HttpURLConnection connection) throws IOException, JSONException, ApiException {
        int code;
        try {
            code = connection.getResponseCode();
        } catch (IOException error) {
            connection.disconnect();
            throw error;
        }
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = "";
        if (stream != null) {
            try (InputStream input = stream) {
                text = new String(readAll(input), StandardCharsets.UTF_8);
            }
        }
        connection.disconnect();
        if (code == 401) throw new Unauthorized(errorMessage(text, "Your session has expired."));
        if (code >= 400) throw new ApiException(code, errorMessage(text, "Request failed (" + code + ")."));
        return new HttpResult(code, text);
    }

    private String readError(HttpURLConnection connection, int code) {
        try {
            InputStream stream = connection.getErrorStream();
            if (stream != null) {
                try (InputStream input = stream) {
                    return errorMessage(new String(readAll(input), StandardCharsets.UTF_8), "Request failed (" + code + ").");
                }
            }
        } catch (Exception ignored) {
        } finally {
            connection.disconnect();
        }
        return "Request failed (" + code + ").";
    }

    private static String errorMessage(String body, String fallback) {
        if (body == null || body.isEmpty()) return fallback;
        try {
            JSONObject json = new JSONObject(body);
            String error = json.optString("error", "");
            return error.isEmpty() ? fallback : error;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    static String uriEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception ignored) {
            return value;
        }
    }

    static String asciiHeader(String value) {
        if (value == null) return "Android";
        String cleaned = value.replaceAll("[^\\x20-\\x7E]", "").trim();
        return cleaned.isEmpty() ? "Android" : cleaned;
    }

    private static final class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
