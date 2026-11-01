package com.example.pickuppleos;

import org.json.JSONObject;

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

    private final String baseUrl;

    PickupApi(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
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

    JSONObject location(String id, String token, double latitude, double longitude,
                        Integer etaSeconds) throws Exception {
        JSONObject body = new JSONObject().put("lat", latitude).put("lon", longitude);
        if (etaSeconds != null) body.put("etaSeconds", etaSeconds);
        return request("POST", "/api/sessions/" + id + "/location", token, body);
    }

    JSONObject finish(String id, String token) throws Exception {
        return request("POST", "/api/sessions/" + id + "/finish", token, new JSONObject());
    }

    private JSONObject request(String method, String path, String token, JSONObject body) throws Exception {
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
