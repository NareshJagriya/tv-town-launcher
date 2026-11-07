package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The TV's HDMI and AV sources, then Live TV, each with the intent that switches to it. */
final class Inputs {

    static final class Source {
        final String label;
        final Intent intent;

        Source(String label, Intent intent) {
            this.label = label;
            this.intent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
    }

    /** The TV's own Live TV app (MediaTek TV centre on Philips TVs), listed after the inputs. */
    private static final String LIVE_TV = "com.mediatek.wwtv.tvcenter";

    private Inputs() {
    }

    static List<Source> list(Context ctx) {
        List<Source> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(ctx)).compareToIgnoreCase(String.valueOf(b.loadLabel(ctx)));
            });
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(ctx);
                String label = l == null ? info.getId() : l.toString();
                if (info.getType() == TvInputInfo.TYPE_HDMI && !label.toUpperCase(java.util.Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                out.add(new Source(label, new Intent(Intent.ACTION_VIEW).setDataAndType(
                        TvContract.buildChannelUriForPassthroughInput(info.getId()), "vnd.android.cursor.item/channel")));
            }
        }
        String pkg = LIVE_TV;
        if (ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null) {
            Intent live = tunerId != null
                    ? new Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId),
                    "vnd.android.cursor.item/channel").setPackage(pkg)
                    : ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            out.add(new Source(ctx.getString(R.string.live_tv), live));
        }
        return out;
    }
}
