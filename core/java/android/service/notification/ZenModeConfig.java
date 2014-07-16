/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.notification;

import android.content.ComponentName;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Slog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Objects;

/**
 * Persisted configuration for zen mode.
 *
 * @hide
 */
public class ZenModeConfig implements Parcelable {
    private static String TAG = "ZenModeConfig";

    public static final String SLEEP_MODE_NIGHTS = "nights";
    public static final String SLEEP_MODE_WEEKNIGHTS = "weeknights";
    public static final String SLEEP_MODE_DAYS_PREFIX = "days:";

    public static final int SOURCE_ANYONE = 0;
    public static final int SOURCE_CONTACT = 1;
    public static final int SOURCE_STAR = 2;
    public static final int MAX_SOURCE = SOURCE_STAR;

    public static final int[] ALL_DAYS = { Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
            Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY };
    public static final int[] WEEKNIGHT_DAYS = { Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
            Calendar.WEDNESDAY, Calendar.THURSDAY };

    private static final int XML_VERSION = 1;
    private static final String ZEN_TAG = "zen";
    private static final String ZEN_ATT_VERSION = "version";
    private static final String ALLOW_TAG = "allow";
    private static final String ALLOW_ATT_CALLS = "calls";
    private static final String ALLOW_ATT_MESSAGES = "messages";
    private static final String ALLOW_ATT_FROM = "from";
    private static final String SLEEP_TAG = "sleep";
    private static final String SLEEP_ATT_MODE = "mode";

    private static final String SLEEP_ATT_START_HR = "startHour";
    private static final String SLEEP_ATT_START_MIN = "startMin";
    private static final String SLEEP_ATT_END_HR = "endHour";
    private static final String SLEEP_ATT_END_MIN = "endMin";

    private static final String CONDITION_TAG = "condition";
    private static final String CONDITION_ATT_COMPONENT = "component";
    private static final String CONDITION_ATT_ID = "id";

    private static final String EXIT_CONDITION_TAG = "exitCondition";
    private static final String EXIT_CONDITION_ATT_ID = "id";
    private static final String EXIT_CONDITION_ATT_COMPONENT = "component";

    public boolean allowCalls;
    public boolean allowMessages;
    public int allowFrom = SOURCE_ANYONE;

    public String sleepMode;
    public int sleepStartHour;
    public int sleepStartMinute;
    public int sleepEndHour;
    public int sleepEndMinute;
    public ComponentName[] conditionComponents;
    public Uri[] conditionIds;
    public Uri exitConditionId;
    public ComponentName exitConditionComponent;

    public ZenModeConfig() { }

    public ZenModeConfig(Parcel source) {
        allowCalls = source.readInt() == 1;
        allowMessages = source.readInt() == 1;
        if (source.readInt() == 1) {
            sleepMode = source.readString();
        }
        sleepStartHour = source.readInt();
        sleepStartMinute = source.readInt();
        sleepEndHour = source.readInt();
        sleepEndMinute = source.readInt();
        int len = source.readInt();
        if (len > 0) {
            conditionComponents = new ComponentName[len];
            source.readTypedArray(conditionComponents, ComponentName.CREATOR);
        }
        len = source.readInt();
        if (len > 0) {
            conditionIds = new Uri[len];
            source.readTypedArray(conditionIds, Uri.CREATOR);
        }
        allowFrom = source.readInt();
        exitConditionId = source.readParcelable(null);
        exitConditionComponent = source.readParcelable(null);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(allowCalls ? 1 : 0);
        dest.writeInt(allowMessages ? 1 : 0);
        if (sleepMode != null) {
            dest.writeInt(1);
            dest.writeString(sleepMode);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(sleepStartHour);
        dest.writeInt(sleepStartMinute);
        dest.writeInt(sleepEndHour);
        dest.writeInt(sleepEndMinute);
        if (conditionComponents != null && conditionComponents.length > 0) {
            dest.writeInt(conditionComponents.length);
            dest.writeTypedArray(conditionComponents, 0);
        } else {
            dest.writeInt(0);
        }
        if (conditionIds != null && conditionIds.length > 0) {
            dest.writeInt(conditionIds.length);
            dest.writeTypedArray(conditionIds, 0);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(allowFrom);
        dest.writeParcelable(exitConditionId, 0);
        dest.writeParcelable(exitConditionComponent, 0);
    }

    @Override
    public String toString() {
        return new StringBuilder(ZenModeConfig.class.getSimpleName()).append('[')
            .append("allowCalls=").append(allowCalls)
            .append(",allowMessages=").append(allowMessages)
            .append(",allowFrom=").append(sourceToString(allowFrom))
            .append(",sleepMode=").append(sleepMode)
            .append(",sleepStart=").append(sleepStartHour).append('.').append(sleepStartMinute)
            .append(",sleepEnd=").append(sleepEndHour).append('.').append(sleepEndMinute)
            .append(",conditionComponents=")
            .append(conditionComponents == null ? null : TextUtils.join(",", conditionComponents))
            .append(",conditionIds=")
            .append(conditionIds == null ? null : TextUtils.join(",", conditionIds))
            .append(",exitConditionId=").append(exitConditionId)
            .append(",exitConditionComponent=").append(exitConditionComponent)
            .append(']').toString();
    }

    public static String sourceToString(int source) {
        switch (source) {
            case SOURCE_ANYONE:
                return "anyone";
            case SOURCE_CONTACT:
                return "contacts";
            case SOURCE_STAR:
                return "stars";
            default:
                return "UNKNOWN";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ZenModeConfig)) return false;
        if (o == this) return true;
        final ZenModeConfig other = (ZenModeConfig) o;
        return other.allowCalls == allowCalls
                && other.allowMessages == allowMessages
                && other.allowFrom == allowFrom
                && Objects.equals(other.sleepMode, sleepMode)
                && other.sleepStartHour == sleepStartHour
                && other.sleepStartMinute == sleepStartMinute
                && other.sleepEndHour == sleepEndHour
                && other.sleepEndMinute == sleepEndMinute
                && Objects.deepEquals(other.conditionComponents, conditionComponents)
                && Objects.deepEquals(other.conditionIds, conditionIds)
                && Objects.equals(other.exitConditionId, exitConditionId)
                && Objects.equals(other.exitConditionComponent, exitConditionComponent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowCalls, allowMessages, allowFrom, sleepMode,
                sleepStartHour, sleepStartMinute, sleepEndHour, sleepEndMinute,
                Arrays.hashCode(conditionComponents), Arrays.hashCode(conditionIds),
                exitConditionId, exitConditionComponent);
    }

    public boolean isValid() {
        return isValidHour(sleepStartHour) && isValidMinute(sleepStartMinute)
                && isValidHour(sleepEndHour) && isValidMinute(sleepEndMinute)
                && isValidSleepMode(sleepMode);
    }

    public static boolean isValidSleepMode(String sleepMode) {
        return sleepMode == null || sleepMode.equals(SLEEP_MODE_NIGHTS)
                || sleepMode.equals(SLEEP_MODE_WEEKNIGHTS) || tryParseDays(sleepMode) != null;
    }

    public static int[] tryParseDays(String sleepMode) {
        if (sleepMode == null) return null;
        sleepMode = sleepMode.trim();
        if (SLEEP_MODE_NIGHTS.equals(sleepMode)) return ALL_DAYS;
        if (SLEEP_MODE_WEEKNIGHTS.equals(sleepMode)) return WEEKNIGHT_DAYS;
        if (!sleepMode.startsWith(SLEEP_MODE_DAYS_PREFIX)) return null;
        if (sleepMode.equals(SLEEP_MODE_DAYS_PREFIX)) return null;
        final String[] tokens = sleepMode.substring(SLEEP_MODE_DAYS_PREFIX.length()).split(",");
        if (tokens.length == 0) return null;
        final int[] rt = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            final int day = tryParseInt(tokens[i], -1);
            if (day == -1) return null;
            rt[i] = day;
        }
        return rt;
    }

    private static int tryParseInt(String value, int defValue) {
        if (TextUtils.isEmpty(value)) return defValue;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    public static ZenModeConfig readXml(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type != XmlPullParser.START_TAG) return null;
        String tag = parser.getName();
        if (!ZEN_TAG.equals(tag)) return null;
        final ZenModeConfig rt = new ZenModeConfig();
        final int version = safeInt(parser, ZEN_ATT_VERSION, XML_VERSION);
        final ArrayList<ComponentName> conditionComponents = new ArrayList<ComponentName>();
        final ArrayList<Uri> conditionIds = new ArrayList<Uri>();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            tag = parser.getName();
            if (type == XmlPullParser.END_TAG && ZEN_TAG.equals(tag)) {
                if (!conditionComponents.isEmpty()) {
                    rt.conditionComponents = conditionComponents
                            .toArray(new ComponentName[conditionComponents.size()]);
                    rt.conditionIds = conditionIds.toArray(new Uri[conditionIds.size()]);
                }
                return rt;
            }
            if (type == XmlPullParser.START_TAG) {
                if (ALLOW_TAG.equals(tag)) {
                    rt.allowCalls = safeBoolean(parser, ALLOW_ATT_CALLS, false);
                    rt.allowMessages = safeBoolean(parser, ALLOW_ATT_MESSAGES, false);
                    rt.allowFrom = safeInt(parser, ALLOW_ATT_FROM, SOURCE_ANYONE);
                    if (rt.allowFrom < SOURCE_ANYONE || rt.allowFrom > MAX_SOURCE) {
                        throw new IndexOutOfBoundsException("bad source in config:" + rt.allowFrom);
                    }
                } else if (SLEEP_TAG.equals(tag)) {
                    final String mode = parser.getAttributeValue(null, SLEEP_ATT_MODE);
                    rt.sleepMode = isValidSleepMode(mode)? mode : null;
                    final int startHour = safeInt(parser, SLEEP_ATT_START_HR, 0);
                    final int startMinute = safeInt(parser, SLEEP_ATT_START_MIN, 0);
                    final int endHour = safeInt(parser, SLEEP_ATT_END_HR, 0);
                    final int endMinute = safeInt(parser, SLEEP_ATT_END_MIN, 0);
                    rt.sleepStartHour = isValidHour(startHour) ? startHour : 0;
                    rt.sleepStartMinute = isValidMinute(startMinute) ? startMinute : 0;
                    rt.sleepEndHour = isValidHour(endHour) ? endHour : 0;
                    rt.sleepEndMinute = isValidMinute(endMinute) ? endMinute : 0;
                } else if (CONDITION_TAG.equals(tag)) {
                    final ComponentName component =
                            safeComponentName(parser, CONDITION_ATT_COMPONENT);
                    final Uri conditionId = safeUri(parser, CONDITION_ATT_ID);
                    if (component != null && conditionId != null) {
                        conditionComponents.add(component);
                        conditionIds.add(conditionId);
                    }
                } else if (EXIT_CONDITION_TAG.equals(tag)) {
                    rt.exitConditionId = safeUri(parser, EXIT_CONDITION_ATT_ID);
                    rt.exitConditionComponent =
                            safeComponentName(parser, EXIT_CONDITION_ATT_COMPONENT);
                }
            }
        }
        throw new IllegalStateException("Failed to reach END_DOCUMENT");
    }

    public void writeXml(XmlSerializer out) throws IOException {
        out.startTag(null, ZEN_TAG);
        out.attribute(null, ZEN_ATT_VERSION, Integer.toString(XML_VERSION));

        out.startTag(null, ALLOW_TAG);
        out.attribute(null, ALLOW_ATT_CALLS, Boolean.toString(allowCalls));
        out.attribute(null, ALLOW_ATT_MESSAGES, Boolean.toString(allowMessages));
        out.attribute(null, ALLOW_ATT_FROM, Integer.toString(allowFrom));
        out.endTag(null, ALLOW_TAG);

        out.startTag(null, SLEEP_TAG);
        if (sleepMode != null) {
            out.attribute(null, SLEEP_ATT_MODE, sleepMode);
        }
        out.attribute(null, SLEEP_ATT_START_HR, Integer.toString(sleepStartHour));
        out.attribute(null, SLEEP_ATT_START_MIN, Integer.toString(sleepStartMinute));
        out.attribute(null, SLEEP_ATT_END_HR, Integer.toString(sleepEndHour));
        out.attribute(null, SLEEP_ATT_END_MIN, Integer.toString(sleepEndMinute));
        out.endTag(null, SLEEP_TAG);

        if (conditionComponents != null && conditionIds != null
                && conditionComponents.length == conditionIds.length) {
            for (int i = 0; i < conditionComponents.length; i++) {
                out.startTag(null, CONDITION_TAG);
                out.attribute(null, CONDITION_ATT_COMPONENT,
                        conditionComponents[i].flattenToString());
                out.attribute(null, CONDITION_ATT_ID, conditionIds[i].toString());
                out.endTag(null, CONDITION_TAG);
            }
        }
        if (exitConditionId != null && exitConditionComponent != null) {
            out.startTag(null, EXIT_CONDITION_TAG);
            out.attribute(null, EXIT_CONDITION_ATT_ID, exitConditionId.toString());
            out.attribute(null, EXIT_CONDITION_ATT_COMPONENT,
                    exitConditionComponent.flattenToString());
            out.endTag(null, EXIT_CONDITION_TAG);
        }
        out.endTag(null, ZEN_TAG);
    }

    public static boolean isValidHour(int val) {
        return val >= 0 && val < 24;
    }

    public static boolean isValidMinute(int val) {
        return val >= 0 && val < 60;
    }

    private static boolean safeBoolean(XmlPullParser parser, String att, boolean defValue) {
        final String val = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(val)) return defValue;
        return Boolean.valueOf(val);
    }

    private static int safeInt(XmlPullParser parser, String att, int defValue) {
        final String val = parser.getAttributeValue(null, att);
        return tryParseInt(val, defValue);
    }

    private static ComponentName safeComponentName(XmlPullParser parser, String att) {
        final String val = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(val)) return null;
        return ComponentName.unflattenFromString(val);
    }

    private static Uri safeUri(XmlPullParser parser, String att) {
        final String val = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(val)) return null;
        return Uri.parse(val);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public ZenModeConfig copy() {
        final Parcel parcel = Parcel.obtain();
        try {
            writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return new ZenModeConfig(parcel);
        } finally {
            parcel.recycle();
        }
    }

    public static final Parcelable.Creator<ZenModeConfig> CREATOR
            = new Parcelable.Creator<ZenModeConfig>() {
        @Override
        public ZenModeConfig createFromParcel(Parcel source) {
            return new ZenModeConfig(source);
        }

        @Override
        public ZenModeConfig[] newArray(int size) {
            return new ZenModeConfig[size];
        }
    };

    // Built-in countdown conditions, e.g. condition://android/countdown/1399917958951

    private static final String COUNTDOWN_AUTHORITY = "android";
    private static final String COUNTDOWN_PATH = "countdown";

    public static Uri toCountdownConditionId(long time) {
        return new Uri.Builder().scheme(Condition.SCHEME)
                .authority(COUNTDOWN_AUTHORITY)
                .appendPath(COUNTDOWN_PATH)
                .appendPath(Long.toString(time))
                .build();
    }

    public static long tryParseCountdownConditionId(Uri conditionId) {
        if (!Condition.isValidId(conditionId, COUNTDOWN_AUTHORITY)) return 0;
        if (conditionId.getPathSegments().size() != 2
                || !COUNTDOWN_PATH.equals(conditionId.getPathSegments().get(0))) return 0;
        try {
            return Long.parseLong(conditionId.getPathSegments().get(1));
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error parsing countdown condition: " + conditionId, e);
            return 0;
        }
    }

    public static boolean isValidCountdownConditionId(Uri conditionId) {
        return tryParseCountdownConditionId(conditionId) != 0;
    }
}
