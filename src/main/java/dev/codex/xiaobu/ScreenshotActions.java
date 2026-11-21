package dev.codex.xiaobu;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XposedBridge;

/** Native menu, gallery save and in-process image search handoff. */
final class ScreenshotActions {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int[] MENU_ENGINES = {0, 1, 2, 3, 4, 6};
    private static final String[] PACKAGES = {
            "com.google.android.googlequicksearchbox", "ru.yandex.searchplugin" };
    private static final String[] ENTRY_POINTS = {
            "com.google.android.apps.search.lens.LensShareEntryPointActivity",
            "com.yandex.browser.ShareImageActivity" };
    static final String MODULE_PACKAGE = "dev.codex.xiaobu.launcherhook";
    static final String SEARCH_ACTIVITY = "dev.codex.xiaobu.WebSearchActivity";
    static final String EXTRA_ENGINE = "engine";
    // Defaults to the old web upload for hosts that do not send this extra.
    static final String EXTRA_WEB = "web";
    // A bounded JPEG keeps the Intent under the Binder transaction limit; Android
    // forbids file descriptors in Intent extras, so bitmaps cannot be parceled.
    static final String EXTRA_IMAGE = "image";
    private static final int SEARCH_EDGE = 1600;
    private static final int SEARCH_BYTES = 512 * 1024;
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1), runnable -> {
                Thread thread = new Thread(runnable, "XiaobuSaveScreenshot");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { WRITER.allowCoreThreadTimeOut(true); }

    static Activity activity(Context context) {
        // Bound traversal also tolerates malformed wrappers from an unsupported host.
        for (int depth = 0; depth < 32; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return null;
    }

    static void saveAndClose(View alert, ScreenshotButtons.Binding binding, ScreenshotButtons.NativeUi ui) {
        Context app = alert.getContext().getApplicationContext();
        ScreenshotSession.Action<Bitmap> action = claim(app, binding);
        if (action != null && enqueue(app, action)) closeRecognition(alert, ui);
    }

    static void showMenu(View alert, View anchor, ScreenshotButtons.Binding binding,
            ScreenshotButtons.NativeUi ui) {
        showMenu(alert, anchor, binding, ui, -1);
    }

    private static void showMenu(View alert, View anchor, ScreenshotButtons.Binding binding,
            ScreenshotButtons.NativeUi ui, final int selectedEngine) {
        Context context = alert.getContext();
        if (!binding.isCurrent()) return;
        if (!binding.session.hasImage(binding.generation)) {
            toast(context, "未找到识屏截图，请重新启动识屏后再试");
            return;
        }
        if (binding.session.actionUnavailable(binding.generation)) return;
        try {
            Object previous = binding.popup.get();
            if (previous != null && Boolean.TRUE.equals(ui.popupShowing.invoke(previous))) return;
            Object popup = ui.popup.newInstance(context);
            List<Object> items = new ArrayList<Object>();
            if (selectedEngine < 0) {
                String[] names = {"Google", "Yandex", "抖音", "百度", "搜狗", "WhatAnime"};
                for (int i = 0; i < names.length; i++)
                    items.add(ui.popupItem.newInstance(names[i], engineIcon(context, MENU_ENGINES[i]), null));
            } else {
                items.add(ui.popupItem.newInstance("应用", engineIcon(context, selectedEngine), null));
                items.add(ui.popupItem.newInstance("网页", engineIcon(context, selectedEngine), null));
            }
            ui.popupItems.invoke(popup, items);
            final Context app = context.getApplicationContext();
            final WeakReference<View> target = new WeakReference<View>(alert);
            final AtomicBoolean selected = new AtomicBoolean();
            Object callback = Proxy.newProxyInstance(ui.loader, new Class<?>[]{ui.function},
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) {
                            if (args == null || args.length < 2 || !(args[1] instanceof Integer)) return ui.unit;
                            int choice = (Integer) args[1];
                            if (choice < 0 || choice >= (selectedEngine < 0 ? MENU_ENGINES.length : 2) || !selected.compareAndSet(false, true)) return ui.unit;
                            View view = target.get();
                            if (view == null || !binding.isCurrent()) return ui.unit;
                            if (selectedEngine < 0 && choice < 2) {
                                MAIN.postDelayed(() -> {
                                    try { if (!binding.closed) showMenu(view, anchor, binding, ui, choice); }
                                    catch (Throwable error) { ScreenshotButtons.log("search mode menu failed", error); }
                                }, 120);
                                return ui.unit;
                            }
                            final int engine = selectedEngine < 0 ? MENU_ENGINES[choice] : selectedEngine;
                            final boolean web = engine >= 2 || (selectedEngine >= 0 && choice == 1);
                            if (!web && resolveShare(app, engine, null, null) == null) {
                                toast(app, "未找到对应的识图应用");
                                return ui.unit;
                            }
                            // Selection is the first point where an image is borrowed or saved.
                            ScreenshotSession.Action<Bitmap> action = claim(app, binding);
                            if (action == null) return ui.unit;
                            View current = target.get();
                            if (current == null || binding.closed || !binding.isCurrent()) {
                                action.finish(false);
                                return ui.unit;
                            }
                            // Android 11+ hides other packages from the host, so the host
                            // cannot write into our provider. Hand the bitmap to the module
                            // process, which owns the provider and grants the engines access.
                            if (startSearch(app, action, engine, web)) closeRecognition(current, ui);
                            return ui.unit;
                        }
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
                        return "XiaobuImageSearchCallback";
                    });
            ui.popupCallback.invoke(popup, callback);
            binding.popup = new WeakReference<Object>(popup);
            ui.popupShow.invoke(popup, anchor);
        } catch (Throwable error) {
            ScreenshotButtons.log("native search menu failed", error);
            toast(context, "无法显示识图菜单");
        }
    }

    private static Drawable engineIcon(Context context, int engine) {
        return new SearchEngineIcon(context, engine);
    }

    private static ScreenshotSession.Action<Bitmap> claim(Context app, ScreenshotButtons.Binding binding) {
        if (!binding.isCurrent()) return null;
        ScreenshotSession.Action<Bitmap> action = binding.session.beginAction(binding.generation);
        if (action == null && !binding.session.hasImage(binding.generation)) {
            toast(app, "未找到识屏截图，请重新启动识屏后再试");
        }
        return action;
    }

    /** Compresses off the main thread, then hands the bytes to the module process. */
    private static boolean startSearch(Context app, ScreenshotSession.Action<Bitmap> action,
            int engine, boolean web) {
        try {
            WRITER.execute(() -> {
                boolean started = false;
                try {
                    byte[] picture = searchImage(action.value());
                    Intent request = new Intent(Intent.ACTION_VIEW)
                            .setClassName(MODULE_PACKAGE, SEARCH_ACTIVITY)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(EXTRA_ENGINE, engine)
                            .putExtra(EXTRA_WEB, web)
                            .putExtra(EXTRA_IMAGE, picture);
                    app.startActivity(request);
                    started = true;
                    XposedBridge.log("XiaobuLauncherHook: image search handoff " + picture.length + " bytes");
                } catch (Throwable error) {
                    ScreenshotButtons.log("image search handoff failed", error);
                    toast(app, "无法启动识图应用");
                } finally {
                    action.finish(started);
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            action.finish(false);
            toast(app, "正在保存截图，请稍后重试");
            return false;
        } catch (Throwable error) {
            action.finish(false);
            ScreenshotButtons.log("search handoff writer unavailable", error);
            toast(app, "无法启动识图应用");
            return false;
        }
    }

    /** JPEG at the same resolution cap the web engines used to receive. */
    private static byte[] searchImage(Bitmap source) throws Exception {
        if (source == null || source.isRecycled()) throw new IllegalStateException("Screenshot no longer available");
        if (source.getWidth() < 1 || source.getHeight() < 1
                || source.getWidth() > 20000 || source.getHeight() > 20000)
            throw new IllegalStateException("Invalid screenshot dimensions");
        int sample = 1;
        while (Math.max(source.getWidth(), source.getHeight()) / sample > SEARCH_EDGE) sample *= 2;
        Bitmap picture = sample == 1 ? source : Bitmap.createScaledBitmap(source,
                Math.max(1, source.getWidth() / sample), Math.max(1, source.getHeight() / sample), true);
        try {
            int quality = 92;
            while (true) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                if (!picture.compress(Bitmap.CompressFormat.JPEG, quality, output))
                    throw new IllegalStateException("JPEG encoding failed");
                byte[] data = output.toByteArray();
                if (data.length <= SEARCH_BYTES) return data;
                if (quality > 60) { quality -= 12; continue; }
                int width = picture.getWidth() * 3 / 4, height = picture.getHeight() * 3 / 4;
                if (width < 64 || height < 64) return data;
                Bitmap smaller = Bitmap.createScaledBitmap(picture, width, height, true);
                if (picture != source) picture.recycle();
                picture = smaller;
                quality = 85;
            }
        } finally {
            if (picture != source && !picture.isRecycled()) picture.recycle();
        }
    }

    private static boolean enqueue(Context app, ScreenshotSession.Action<Bitmap> action) {
        try {
            WRITER.execute(() -> write(app, action));
            return true;
        } catch (RejectedExecutionException full) {
            action.finish(false);
            toast(app, "正在保存截图，请稍后重试");
            return false;
        } catch (Throwable error) {
            action.finish(false);
            ScreenshotButtons.log("screenshot writer unavailable", error);
            toast(app, "保存截图失败");
            return false;
        }
    }

    private static void write(Context app, ScreenshotSession.Action<Bitmap> action) {
        Uri uri = null;
        boolean success = false;
        try {
            Bitmap picture = action.value();
            if (picture.isRecycled()) throw new IllegalStateException("Screenshot no longer available");
            ContentValues values = new ContentValues();
            String date = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "小布识屏_" + date + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put("relative_path", "Pictures/小布识屏增强");
                values.put("is_pending", 1);
            }
            uri = app.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
            try (OutputStream stream = app.getContentResolver().openOutputStream(uri)) {
                if (stream == null || !picture.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IllegalStateException("PNG write failed");
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put("is_pending", 0);
                if (app.getContentResolver().update(uri, ready, null, null) != 1) {
                    throw new IllegalStateException("MediaStore image was not published");
                }
            }
            XposedBridge.log("XiaobuLauncherHook: screenshot saved to " + uri);
            success = true;
        } catch (Throwable error) {
            if (uri != null) {
                try { app.getContentResolver().delete(uri, null, null); }
                catch (Throwable cleanup) { ScreenshotButtons.log("partial screenshot cleanup failed", cleanup); }
            }
            ScreenshotButtons.log("screenshot save failed", error);
            toast(app, "保存截图失败");
        } finally {
            action.finish(success);
        }
    }

    private static Intent imageIntent(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        if (uri != null) {
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setClipData(ClipData.newRawUri("识屏截图", uri));
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static boolean canOpen(Context context, ActivityInfo info, String requiredPackage) {
        return info != null && requiredPackage.equals(info.packageName) && info.exported && info.enabled
                && (info.applicationInfo == null || info.applicationInfo.enabled)
                && (info.permission == null || context.getPackageManager().checkPermission(
                        info.permission, context.getPackageName()) == PackageManager.PERMISSION_GRANTED);
    }

    static Intent resolveShare(Context app, int engine, Uri uri, String excludedClass) {
        try {
            if (engine == 2) {
                Intent search = new Intent(Intent.ACTION_VIEW, Uri.parse("snssdk1128://search/visual").buildUpon()
                        .appendQueryParameter("src_image", uri == null ? "" : uri.toString())
                        .appendQueryParameter("page_type", "result").build());
                search.setPackage("com.ss.android.ugc.aweme");
                search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (uri != null) search.setClipData(ClipData.newRawUri("识屏图片", uri));
                return search;
            }
            PackageManager packages = app.getPackageManager();
            String name = PACKAGES[engine];
            Intent explicit = imageIntent(uri).setClassName(name, ENTRY_POINTS[engine]);
            // Host targets modern Android: package visibility may hide an installed
            // share receiver from queries. Explicit launch is still allowed and the
            // system checks export/permission/enabled state when starting it.
            if (!ENTRY_POINTS[engine].equals(excludedClass)) return explicit;
            Intent withinEngine = imageIntent(uri).setPackage(name);
            List<ResolveInfo> matches = packages.queryIntentActivities(withinEngine, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo match : matches) {
                ActivityInfo info = match.activityInfo;
                if (canOpen(app, info, name) && !info.name.equals(excludedClass)) {
                    // Keep fallback inside the selected engine, even with multiple share targets.
                    return imageIntent(uri).setClassName(name, info.name);
                }
            }
        } catch (Throwable error) { ScreenshotButtons.log("image engine resolution failed", error); }
        return null;
    }

    static void launch(Context app, int engine, Uri uri, Intent intent) {
        try {
            app.startActivity(intent);
        } catch (Exception first) {
            String previous = intent.getComponent() == null ? null : intent.getComponent().getClassName();
            Intent fallback = resolveShare(app, engine, uri, previous);
            if (fallback != null) {
                try { app.startActivity(fallback); return; }
                catch (Exception second) { ScreenshotButtons.log("image engine fallback failed", second); }
            }
            ScreenshotButtons.log("image engine launch failed", first);
            toast(app, "无法打开识图应用");
        }
    }

    private static void closeRecognition(View alert, ScreenshotButtons.NativeUi ui) {
        Activity activity = activity(alert.getContext());
        if (activity != null && !activity.isFinishing()) {
            try { activity.onBackPressed(); return; }
            catch (Throwable error) { ScreenshotButtons.log("native back failed", error); }
        }
        try { ui.dismiss.invoke(alert); }
        catch (Throwable error) { ScreenshotButtons.log("dismiss fallback failed", error); }
    }

    private static void toast(Context context, String message) {
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            try { Toast.makeText(app, message, Toast.LENGTH_SHORT).show(); }
            catch (Throwable error) { ScreenshotButtons.log("screenshot failure notice unavailable", error); }
        });
    }
}
