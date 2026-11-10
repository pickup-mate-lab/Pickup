package com.example.pickuppleos;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

final class GleoResponder {
    static JSONObject parameters(Intent intent) throws Exception {
        String raw = intent.getStringExtra("ACTION_PARAM");
        if (raw == null) {
            JSONObject result = new JSONObject();
            Bundle extras = intent.getExtras();
            if (extras != null) for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value instanceof String || value instanceof Number || value instanceof Boolean)
                    result.put(key, value);
            }
            return result;
        }
        Object parsed = new JSONTokener(raw).nextValue();
        return parsed instanceof JSONObject ? (JSONObject) parsed : new JSONObject(String.valueOf(parsed));
    }

    static void sendSilent(Context context, String function, String status, String message) {
        try {
            JSONObject response = new JSONObject().put("functionName", function)
                    .put("status", status).put("message", message).put("mode", "silent");
            Intent callback = new Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE");
            callback.putExtra("packageName", context.getPackageName());
            callback.putExtra("response", response.toString());
            context.sendBroadcast(callback);
        } catch (Exception ignored) { }
    }

    static void send(Context context, String function, String status, String message, JSONArray data) {
        try {
            JSONObject response = new JSONObject().put("functionName", function)
                    .put("status", status).put("message", message);
            if (data != null) response.put("data", data.toString());
            Intent callback = new Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE");
            callback.putExtra("packageName", context.getPackageName());
            callback.putExtra("response", response.toString());
            context.sendBroadcast(callback);
        } catch (Exception ignored) { }
    }
}
