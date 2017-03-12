/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.keyguard;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.content.ContentResolver;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff.Mode;
import android.graphics.Typeface;
import android.os.UserHandle;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;

import java.util.Date;
import java.text.NumberFormat;
import java.util.Locale;

public class KeyguardStatusView extends GridLayout {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";

    private final LockPatternUtils mLockPatternUtils;
    private final AlarmManager mAlarmManager;

    private TextView mAlarmStatusView;
    private TextClock mDateView;
    private TextClock mClockView;
	private TextView mAmbientDisplayBatteryView;
    private TextView mOwnerInfo;

	private final int mWarningColor = 0xfff4511e; // deep orange 600
    private int mIconColor;
    private int mPrimaryTextColor;
    private int mLockClockFontSize;
    private int mLockDateFontSize;

    //On the first boot, keygard will start to receiver TIME_TICK intent.
    //And onScreenTurnedOff will not get called if power off when keyguard is not started.
    //Set initial value to false to skip the above case.
    private boolean mEnableRefresh = false;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            if (mEnableRefresh) {
                refresh();
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refresh();
                updateOwnerInfo();
            }
        }

        @Override
        public void onStartedWakingUp() {
            setEnableMarquee(true);
            mEnableRefresh = true;
            refresh();
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            setEnableMarquee(false);
            mEnableRefresh = false;
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            refresh();
            updateOwnerInfo();
        }
    };

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mLockPatternUtils = new LockPatternUtils(getContext());
    }

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mAlarmStatusView != null) mAlarmStatusView.setSelected(enabled);
        if (mOwnerInfo != null) mOwnerInfo.setSelected(enabled);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
        mDateView = (TextClock) findViewById(R.id.date_view);
        mClockView = (TextClock) findViewById(R.id.clock_view);
        mDateView.setShowCurrentUserTime(true);
        mClockView.setShowCurrentUserTime(true);
		mAmbientDisplayBatteryView = (TextView) findViewById(R.id.ambient_display_battery_view);
        mOwnerInfo = (TextView) findViewById(R.id.owner_info);

        boolean shouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setEnableMarquee(shouldMarquee);
        refresh();
        updateOwnerInfo();

        // Disable elegant text height because our fancy colon makes the ymin value huge for no
        // reason.
        mClockView.setElegantTextHeight(false);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,mLockClockFontSize);
        mClockView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        mDateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, mLockDateFontSize);
        // Some layouts like burmese have a different margin for the clock
        MarginLayoutParams layoutParams = (MarginLayoutParams) mClockView.getLayoutParams();
        layoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.bottom_text_spacing_digital);
        mClockView.setLayoutParams(layoutParams);
        mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
    }

    private int getLockClockFont() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCK_CLOCK_FONTS, 0);
    }

    public void refreshTime() {
        mDateView.setFormat24Hour(Patterns.dateView);
        mDateView.setFormat12Hour(Patterns.dateView);

        mClockView.setFormat12Hour(Patterns.clockView12);
        mClockView.setFormat24Hour(Patterns.clockView24);
    }

    private void refresh() {
        AlarmManager.AlarmClockInfo nextAlarm =
                mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        Patterns.update(mContext, nextAlarm != null);

        refreshTime();
        refreshAlarmStatus(nextAlarm);
        updateSettings(false);
        refreshLockFont();
    }

    void refreshAlarmStatus(AlarmManager.AlarmClockInfo nextAlarm) {
        if (nextAlarm != null) {
            String alarm = formatNextAlarm(mContext, nextAlarm);
            mAlarmStatusView.setText(alarm);
            mAlarmStatusView.setContentDescription(
                    getResources().getString(R.string.keyguard_accessibility_next_alarm, alarm));
            mAlarmStatusView.setVisibility(View.VISIBLE);
        } else {
            mAlarmStatusView.setVisibility(View.GONE);
        }
    }

    public static String formatNextAlarm(Context context, AlarmManager.AlarmClockInfo info) {
        if (info == null) {
            return "";
        }
        String skeleton = DateFormat.is24HourFormat(context, ActivityManager.getCurrentUser())
                ? "EHm"
                : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    private void updateOwnerInfo() {
        if (mOwnerInfo == null) return;
        String ownerInfo = getOwnerInfo();
        if (!TextUtils.isEmpty(ownerInfo)) {
            mOwnerInfo.setVisibility(View.VISIBLE);
            mOwnerInfo.setText(ownerInfo);
        } else {
            mOwnerInfo.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mInfoCallback);
        updateSettings(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mInfoCallback);
    }

    private String getOwnerInfo() {
        String info = null;
        if (mLockPatternUtils.isDeviceOwnerInfoEnabled()) {
            // Use the device owner information set by device policy client via
            // device policy manager.
            info = mLockPatternUtils.getDeviceOwnerInfo();
        } else {
            // Use the current user owner information if enabled.
            final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                    KeyguardUpdateMonitor.getCurrentUser());
            if (ownerInfoEnabled) {
                info = mLockPatternUtils.getOwnerInfo(KeyguardUpdateMonitor.getCurrentUser());
            }
        }
        return info;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private void updateSettings(boolean forceHide) {
        final ContentResolver resolver = getContext().getContentResolver();
        final Resources res = getContext().getResources();
        AlarmManager.AlarmClockInfo nextAlarm =
                mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        boolean showAlarm = Settings.System.getIntForUser(resolver,
                Settings.System.HIDE_LOCKSCREEN_ALARM, 1, UserHandle.USER_CURRENT) == 1;
        boolean showClock = Settings.System.getIntForUser(resolver,
                Settings.System.HIDE_LOCKSCREEN_CLOCK, 1, UserHandle.USER_CURRENT) == 1;
        boolean showDate = Settings.System.getIntForUser(resolver,
                Settings.System.HIDE_LOCKSCREEN_DATE, 1, UserHandle.USER_CURRENT) == 1;

        mClockView = (TextClock) findViewById(R.id.clock_view);
        mClockView.setVisibility(showClock ? View.VISIBLE : View.GONE);

        mDateView.setVisibility(showDate ? View.VISIBLE : View.GONE);
        mDateView = (TextClock) findViewById(R.id.date_view);

        mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
        mAlarmStatusView.setVisibility(showAlarm && nextAlarm != null ? View.VISIBLE : View.GONE);

        mLockClockFontSize = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKCLOCK_FONT_SIZE, getResources().getDimensionPixelSize(R.dimen.widget_big_font_size), UserHandle.USER_CURRENT);
        mLockDateFontSize = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKDATE_FONT_SIZE, getResources().getDimensionPixelSize(R.dimen.widget_label_font_size), UserHandle.USER_CURRENT);
        int dateFont = Settings.System.getIntForUser(resolver,
                Settings.System.LOCK_DATE_FONTS, 4, UserHandle.USER_CURRENT);

        if (mClockView != null) {
            if (mLockClockFontSize != getResources().getDimensionPixelSize(R.dimen.widget_big_font_size)) {
                mClockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, mLockClockFontSize);
            }
        }

        if (mDateView != null) {
            if (mLockDateFontSize != getResources().getDimensionPixelSize(R.dimen.widget_label_font_size)) {
                mDateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, mLockDateFontSize);
            }
        }

        if (dateFont == 0) {
        mDateView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        if (dateFont == 1) {
            mDateView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }
        if (dateFont == 2) {
            mDateView.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
        }
        if (dateFont == 3) {
            mDateView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
        }
        if (dateFont == 4) {
            mDateView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        if (dateFont == 5) {
            mDateView.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
        }
        if (dateFont == 6) {
            mDateView.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        }
        if (dateFont == 7) {
            mDateView.setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
        }
        if (dateFont == 8) {
            mDateView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        }
        if (dateFont == 9) {
            mDateView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }
        if (dateFont == 10) {
            mDateView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
        }
        if (dateFont == 11) {
            mDateView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        }
        if (dateFont == 12) {
            mDateView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        if (dateFont == 13) {
            mDateView.setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
        }
        if (dateFont == 14) {
            mDateView.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        }
        if (dateFont == 15) {
            mDateView.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
        }
        if (dateFont == 16) {
            mDateView.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
        }
        if (dateFont == 17) {
            mDateView.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
        }
        if (dateFont == 18) {
            mDateView.setTypeface(Typeface.create("cursive", Typeface.NORMAL));
        }
        if (dateFont == 19) {
            mDateView.setTypeface(Typeface.create("cursive", Typeface.BOLD));
        }
        if (dateFont == 20) {
            mDateView.setTypeface(Typeface.create("casual", Typeface.NORMAL));
        }
        if (dateFont == 21) {
            mDateView.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        }
        if (dateFont == 22) {
            mDateView.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        }
        if (dateFont == 23) {
            mDateView.setTypeface(Typeface.create("serif", Typeface.BOLD));
        }
        if (dateFont == 24) {
            mDateView.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
        }
    }

	private void refreshBatteryInfo() {
        final Resources res = getContext().getResources();
        KeyguardUpdateMonitor.BatteryStatus batteryStatus =
                KeyguardUpdateMonitor.getInstance(mContext).getBatteryStatus();

        mPrimaryTextColor =
                res.getColor(R.color.keyguard_default_primary_text_color);
        mIconColor =
                res.getColor(R.color.keyguard_default_primary_text_color);

        String percentage = "";
        int resId = 0;
        final int lowLevel = res.getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);
        final boolean useWarningColor = batteryStatus == null || batteryStatus.status == 1
                || (batteryStatus.level <= lowLevel && !batteryStatus.isPluggedIn());

        if (batteryStatus != null) {
            percentage = NumberFormat.getPercentInstance().format((double) batteryStatus.level / 100.0);
        }
        if (batteryStatus == null || batteryStatus.status == 1) {
            resId = R.drawable.ic_battery_unknown;
        } else {
            if (batteryStatus.level >= 96) {
                resId = batteryStatus.isPluggedIn()
                        ? R.drawable.ic_battery_charging_full : R.drawable.ic_battery_full;
            } else if (batteryStatus.level >= 90) {
                resId = batteryStatus.isPluggedIn()
                        ? R.drawable.ic_battery_charging_90 : R.drawable.ic_battery_90;
            } else if (batteryStatus.level >= 80) {
                resId = batteryStatus.isPluggedIn()
                        ? R.drawable.ic_battery_charging_80 : R.drawable.ic_battery_80;
            } else if (batteryStatus.level >= 60) {
                resId = batteryStatus.isPluggedIn()
                        ? R.drawable.ic_battery_charging_60 : R.drawable.ic_battery_60;
            } else if (batteryStatus.level >= 50) {
                resId = batteryStatus.isPluggedIn()
                        ? R.drawable.ic_battery_charging_50 : R.drawable.ic_battery_50;
            } else if (batteryStatus.level >= 30) {
                resId = batteryStatus.isPluggedIn()
                        ? R.drawable.ic_battery_charging_30 : R.drawable.ic_battery_30;
            } else if (batteryStatus.level >= lowLevel) {
                resId = batteryStatus.isPluggedIn()
                        ? R.drawable.ic_battery_charging_20 : R.drawable.ic_battery_20;
            } else {
                resId = batteryStatus.isPluggedIn()
                        ? R.drawable.ic_battery_charging_20 : R.drawable.ic_battery_alert;
            }
        }
        Drawable icon = resId > 0 ? res.getDrawable(resId).mutate() : null;
        if (icon != null) {
            icon.setTintList(ColorStateList.valueOf(useWarningColor ? mWarningColor : mIconColor));

        mAmbientDisplayBatteryView.setText(percentage);
        mAmbientDisplayBatteryView.setTextColor(useWarningColor
                ? mWarningColor : mPrimaryTextColor);
        mAmbientDisplayBatteryView.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
        }
    }

    private void refreshLockFont() {
        final Resources res = getContext().getResources();
        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int lockClockFont = isPrimary ? getLockClockFont() : 0;

        if (lockClockFont == 0) {
            mClockView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        if (lockClockFont == 1) {
            mClockView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }
        if (lockClockFont == 2) {
            mClockView.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
        }
        if (lockClockFont == 3) {
            mClockView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
        }
        if (lockClockFont == 4) {
            mClockView.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
        }
        if (lockClockFont == 5) {
            mClockView.setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
        }
        if (lockClockFont == 6) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        }
        if (lockClockFont == 7) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
        }
        if (lockClockFont == 8) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }
        if (lockClockFont == 9) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        }
        if (lockClockFont == 10) {
            mClockView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        if (lockClockFont == 11) {
            mClockView.setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
        }
    }

    public void setDozing(boolean dozing) {
        if (dozing && showBattery()) {
            refreshBatteryInfo();
            if (mAmbientDisplayBatteryView.getVisibility() != View.VISIBLE) {
                mAmbientDisplayBatteryView.setVisibility(View.VISIBLE);
            }
        } else {
            if (mAmbientDisplayBatteryView.getVisibility() != View.GONE) {
                mAmbientDisplayBatteryView.setVisibility(View.GONE);
            }
        }
    }

    private boolean showBattery() {
        return Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.AMBIENT_DISPLAY_SHOW_BATTERY, 1) == 1;
    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private static final class Patterns {
        static String dateView;
        static String clockView12;
        static String clockView24;
        static String cacheKey;

        static void update(Context context, boolean hasAlarm) {
            final Locale locale = Locale.getDefault();
            final Resources res = context.getResources();
            final ContentResolver resolver = context.getContentResolver();
            final boolean showAlarm = Settings.System.getIntForUser(resolver,
                    Settings.System.HIDE_LOCKSCREEN_ALARM, 1, UserHandle.USER_CURRENT) == 1;
            final String dateViewSkel = res.getString(hasAlarm && showAlarm
                    ? R.string.abbrev_wday_month_day_no_year_alarm
                    : R.string.abbrev_wday_month_day_no_year);
            final String clockView12Skel = res.getString(R.string.clock_12hr_format);
            final String clockView24Skel = res.getString(R.string.clock_24hr_format);
            final String key = locale.toString() + dateViewSkel + clockView12Skel + clockView24Skel;
            if (key.equals(cacheKey)) return;
            if (res.getBoolean(com.android.internal.R.bool.config_dateformat)) {
                final String dateformat = Settings.System.getString(context.getContentResolver(),
                        Settings.System.DATE_FORMAT);
                dateView = dateformat;
            } else {
                dateView = DateFormat.getBestDateTimePattern(locale, dateViewSkel);
            }

            clockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel);
            if(!context.getResources().getBoolean(R.bool.config_showAmpm)){
                // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
                // format.  The following code removes the AM/PM indicator if we didn't want it.
                if (!clockView12Skel.contains("a")) {
                    clockView12 = clockView12.replaceAll("a", "").trim();
                }
            }

            clockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel);

            // Use fancy colon.
            clockView24 = clockView24.replace(':', '\uee01');
            clockView12 = clockView12.replace(':', '\uee01');

            cacheKey = key;
        }
    }
}
