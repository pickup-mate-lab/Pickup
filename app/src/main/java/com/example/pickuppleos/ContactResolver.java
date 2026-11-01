package com.example.pickuppleos;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.List;

final class ContactResolver {
    static final class Choice {
        final String name;
        final String phone;
        Choice(String name, String phone) { this.name = name; this.phone = phone; }
        String label() {
            String digits = phone.replaceAll("\\D", "");
            return name + " · 끝자리 " + (digits.length() >= 4
                    ? digits.substring(digits.length() - 4) : "번호 없음");
        }
    }

    static List<Choice> find(Context context, String name) {
        ArrayList<Choice> choices = new ArrayList<>();
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return choices;
        try (Cursor cursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + name + "%"}, null)) {
            if (cursor == null) return choices;
            while (cursor.moveToNext() && choices.size() < 8) {
                String display = cursor.getString(0), phone = cursor.getString(1);
                if (display != null && phone != null && display.contains(name))
                    choices.add(new Choice(display, phone));
            }
        } catch (SecurityException ignored) { }
        return choices;
    }
}
