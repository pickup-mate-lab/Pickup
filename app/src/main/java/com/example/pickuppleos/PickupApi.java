package com.example.pickuppleos;

import org.json.JSONObject;
import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTP client for the local prototype server. Call only from a worker thread. */
final class PickupApi {
    static final class ApiException extends Exception {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }

    static boolean isTerminalSessionError(Exception error, String token) {
        if (!(error instanceof ApiException)) return false;
        int status = ((ApiException) error).status;
        // Demo server reports a missing session or invalid session token as 401.
        // Firebase account 401 can be a recoverable sign-in problem.
        return status == 404 || status == 410
                || (status == 401 && !DriverFirebaseAuth.ACCOUNT_TOKEN.equals(token));
    }

    private final String baseUrl;
    private final Context context;

    PickupApi(Context context, String baseUrl) {
        this.context = context.getApplicationContext();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    JSONObject createAccount(String contactName, String pickupTime, String phone) throws Exception {
        JSONObject body = new JSONObject().put("contactName", contactName).put("passengerPhone", phone);
        if (!pickupTime.trim().isEmpty()) body.put("pickupTime", pickupTime.trim());
        return request("POST", "/api/sessions", DriverFirebaseAuth.ACCOUNT_TOKEN, body);
    }

    JSONObject create(String contactName, String pickupTime) throws Exception {
        JSONObject body = new JSONObject().put("contactName", contactName);
        if (!pickupTime.trim().isEmpty()) body.put("pickupTime", pickupTime.trim());
        return request("POST", "/api/sessions", null, body);
    }

    JSONObject state(String id, String token) throws Exception {
        return request("GET", "/api/sessions/" + id, token, null);
    }

    JSONObject confirm(String id, String token, int revision) throws Exception {
        return request("POST", "/api/sessions/" + id + "/confirm", token,
                new JSONObject().put("revision", revision));
    }

    JSONObject passengerPickup(String id, String token, double lat, double lon, String label) throws Exception {
        return request("POST", "/api/sessions/" + id + "/pickup", token,
                new JSONObject().put("lat", lat).put("lon", lon).put("label", label));
    }

    JSONObject location(String id, String token, double latitude, double longitude,
                        Integer etaSeconds) throws Exception {
        // Always send the key: an explicit null clears a previous ETA on the server.
        JSONObject body = new JSONObject().put("lat", latitude).put("lon", longitude)
                .put("etaSeconds", etaSeconds != null ? etaSeconds : JSONObject.NULL);
        return request("POST", "/api/sessions/" + id + "/location", token, body);
    }

    JSONObject finish(String id, String token) throws Exception {
        return request("POST", "/api/sessions/" + id + "/finish", token, new JSONObject());
    }

    JSONObject message(String id, String token, String text) throws Exception {
        return request("POST", "/api/sessions/" + id + "/message", token,
                new JSONObject().put("text", text));
    }

    private JSONObject request(String method, String path, String token, JSONObject body) throws Exception {
        if (DriverFirebaseAuth.ACCOUNT_TOKEN.equals(token)) {
            URL endpoint = new URL(baseUrl);
            boolean localDemo = "http".equals(endpoint.getProtocol()) && "10.0.2.2".equals(endpoint.getHost())
                    && (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (!"https".equals(endpoint.getProtocol()) && !localDemo)
                throw new SecurityException("계정 연결에는 HTTPS가 필요합니다.");
            path = path.replaceFirst("^/api/sessions", "/api/v1/pickups");
            token = DriverFirebaseAuth.idToken(context);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int code = connection.getResponseCode();
        InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        StringBuilder response = new StringBuilder();
        if (input != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
            }
        }
        connection.disconnect();
        JSONObject json = response.length() == 0 ? new JSONObject() : new JSONObject(response.toString());
        if (code >= 400) throw new ApiException(code, json.optString("error", "HTTP " + code));
        return json;
    }
}
