package cn.personal.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UiTest {
    @Before public void initializeWorkManager() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication(),
            new androidx.work.Configuration.Builder().setExecutor(new androidx.work.testing.SynchronousExecutor()).build());
    }
    private List<TextView> texts(View view) {
        List<TextView> result = new ArrayList<>();
        if (view instanceof TextView) result.add((TextView) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) result.addAll(texts(((ViewGroup) view).getChildAt(i)));
        return result;
    }
    private TextView find(Activity activity, String text) {
        return texts(activity.findViewById(android.R.id.content)).stream().filter(v -> text.contentEquals(v.getText())).findFirst().orElse(null);
    }
    private void render(Activity activity, String name) throws Exception {
        View view = activity.findViewById(android.R.id.content);
        view.measure(View.MeasureSpec.makeMeasureSpec(1179, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2556, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1179, 2556);
        Bitmap bitmap = Bitmap.createBitmap(1179, 2556, Bitmap.Config.ARGB_8888); view.draw(new Canvas(bitmap));
        File directory = new File("build/reports/ui"); assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); }
        if (name.endsWith("settings")) {
            android.widget.ScrollView scroll = (android.widget.ScrollView) ((ViewGroup) view).getChildAt(0);
            scroll.scrollTo(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
            bitmap.eraseColor(activity.getColor(R.color.background)); view.draw(new Canvas(bitmap));
            try (FileOutputStream out = new FileOutputStream(new File(directory, name + "-bottom.png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); }
        }
        bitmap.recycle();
    }
    @Test public void lightHomeIsSimpleAndScreenshotsAllowed() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(find(activity, "设置")); assertNotNull(find(activity, "开始录音")); assertNotNull(find(activity, "最近录音"));
            assertNotNull(find(activity, "内容分类")); assertNotNull(find(activity, "隐私录音")); assertNotNull(find(activity, "会议记录")); assertNotNull(find(activity, "课堂记录"));
            assertNull(find(activity, "导入连接配置")); assertNull(find(activity, "备份恢复密钥"));
            assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
            assertTrue(shadowOf(activity).getShowWhenLocked()); assertTrue(shadowOf(activity).getTurnScreenOn());
            find(activity, "设置").performClick(); Intent launched = shadowOf(activity).getNextStartedActivity();
            assertEquals(SettingsActivity.class.getName(), launched.getComponent().getClassName());
            render(activity, "light-home");
        }
    }
    @Test @Config(qualifiers = "w393dp-h852dp-night-xxhdpi") public void darkHomeUsesDarkResources() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0xff101716, activity.getColor(R.color.background));
            assertEquals(activity.getColor(R.color.ink), find(activity, "00:00:00").getCurrentTextColor());
            render(activity, "dark-home");
        }
    }
    @Test public void settingsContainsAllOptionsAndDoesNotStartRecording() throws Exception {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            SettingsActivity activity = controller.get(); shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(find(activity, "服务器连接")); assertNotNull(find(activity, "备份恢复密钥")); assertNotNull(find(activity, "清理已上传副本"));
            assertNull(shadowOf(activity).getNextStartedService()); assertFalse(shadowOf(activity).getShowWhenLocked());
            assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
            render(activity, "light-settings");
        }
    }
    @Test @Config(qualifiers = "w393dp-h852dp-night-xxhdpi") public void darkSettingsUsesDarkResources() throws Exception {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            SettingsActivity activity = controller.get(); shadowOf(Looper.getMainLooper()).idle();
            assertEquals(activity.getColor(R.color.ink), find(activity, "保存与同步").getCurrentTextColor()); render(activity, "dark-settings");
        }
    }
    @Test public void lockedLaunchWaitsForFocusAndDoesNotAskToUnlock() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("vault", Context.MODE_PRIVATE).edit().putString("recording-key", "existing-wrapped-key").putBoolean("ready", true).commit();
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
            MainActivity activity = controller.get(); shadowOf(Looper.getMainLooper()).idle();
            assertNull(shadowOf(activity).getNextStartedService());
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS);
            controller.visible().windowFocusChanged(true); shadowOf(Looper.getMainLooper()).idle();
            Intent requested = shadowOf(activity).getNextStartedService();
            assertNotNull(requested); assertEquals(RecordingService.class.getName(), requested.getComponent().getClassName());
            assertNull(shadowOf(activity).getNextStartedService());
            assertTrue(context.getSystemService(KeyguardManager.class).isKeyguardLocked());
        }
    }
    @Test public void textPageRequiresExplicitActionAndDoesNotUploadOnOpen() throws Exception {
        android.content.Intent intent = new android.content.Intent(RuntimeEnvironment.getApplication(), TextActivity.class)
            .putExtra("session", "10000000-0000-4000-8000-000000000001");
        try (ActivityController<TextActivity> controller = Robolectric.buildActivity(TextActivity.class, intent).setup()) {
            TextActivity activity = controller.get(); shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(find(activity, "本地提取文字")); assertNotNull(find(activity, "生成 AI 总结"));
            assertNotNull(find(activity, "生成大纲")); assertNotNull(find(activity, "生成思维导图"));
            assertNull(shadowOf(activity).getNextStartedService()); assertFalse(shadowOf(activity).getShowWhenLocked());
            render(activity, "light-text");
        }
    }
    @Test @Config(qualifiers = "w393dp-h852dp-night-xxhdpi") public void darkTextPageUsesThemeAndKeepsSummaryExplicit() throws Exception {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), TextActivity.class).putExtra("session", "10000000-0000-4000-8000-000000000001");
        try (ActivityController<TextActivity> controller = Robolectric.buildActivity(TextActivity.class, intent).setup()) {
            TextActivity activity = controller.get(); shadowOf(Looper.getMainLooper()).idle();
            assertEquals(activity.getColor(R.color.ink), find(activity, "录音文字").getCurrentTextColor());
            assertFalse(find(activity, "生成 AI 总结").isEnabled()); assertNull(shadowOf(activity).getNextStartedService()); render(activity, "dark-text");
        }
    }
}
