package io.github.respawnedguy.e2eshare;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Shares one file of {@link TestFileProvider} with the FamilyApp, exactly like a gallery or mail
 * app: ACTION_SEND, EXTRA_STREAM, ClipData and a read grant. Extras: "kind" (ok, big, html, fake),
 * "text" (EXTRA_TEXT), optional "target" (package of the FamilyApp build under test).
 */
public class ShareSourceActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        String kind = valueOr(in.getStringExtra("kind"), "ok");
        String target = valueOr(in.getStringExtra("target"), "io.github.respawnedguy.familyapp");
        Uri uri = Uri.parse("content://" + TestFileProvider.AUTHORITY + "/" + kind);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setComponent(new ComponentName(target, "io.github.respawnedguy.familyapp.MainActivity"));
        // The type the sender claims; the FamilyApp must ask the provider and look at the bytes.
        send.setType("image/jpeg");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        if (in.hasExtra("text")) send.putExtra(Intent.EXTRA_TEXT, in.getStringExtra("text"));
        send.setClipData(ClipData.newRawUri("", uri));
        // Own task for the FamilyApp: otherwise it stays on top of this app's task, and the next
        // "am start" of this activity only brings that old task back without sharing again.
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(send);
        finish();
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
