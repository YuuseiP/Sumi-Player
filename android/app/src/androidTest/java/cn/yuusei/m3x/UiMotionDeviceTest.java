package cn.yuusei.m3x;

import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** On-device motion probe: existing catalog, real playback, restored state, no scan. */
@androidx.media3.common.util.UnstableApi
public final class UiMotionDeviceTest extends InstrumentationTestCase {
    private MainActivity activity;
    private final JSONArray samples = new JSONArray();
    private String js(String code) throws Exception {
        CountDownLatch latch=new CountDownLatch(1); String[] result={""};
        getInstrumentation().runOnMainSync(()->activity.web.evaluateJavascript(code,r->{result[0]=r;latch.countDown();}));
        assertTrue("JS callback",latch.await(20,TimeUnit.SECONDS));return result[0];
    }
    private void act(String code) throws Exception {
        assertEquals("JS action", "\"ok\"",js("(function(){try{"+code+";return 'ok'}catch(e){return String(e)}})()"));
    }
    private void waitJs(String code) throws Exception {
        long end=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<end){if("true".equals(js(code)))return;Thread.sleep(100);}
        fail("Timed out: "+code);
    }
    private String click(String selector) { return "document.querySelector("+JSONObject.quote(selector)+").click()"; }
    private Field field(String name) throws Exception {Field f=PlaybackService.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private void setting(String key,String value) throws Exception {
        act("var e=document.querySelector('[data-setting="+key+"]');e.value="+JSONObject.quote(value)+";e.dispatchEvent(new Event('change',{bubbles:true}))");
    }
    private void measure(String name,String action) throws Exception {
        act("window.__motionSample=null;requestAnimationFrame(function(){var last=performance.now(),start=last,frames=[],handler=0,first=null;"
            +"function tick(){var now=performance.now();frames.push(now-last);last=now;if(first===null)first=now-start;"
            +"if(now-start<420)requestAnimationFrame(tick);else window.__motionSample={name:"+JSONObject.quote(name)+",handlerMs:handler,firstFrameMs:first,intervals:frames};}"
            +"requestAnimationFrame(tick);"+action+";handler=performance.now()-start;})");
        waitJs("window.__motionSample!==null");
        JSONObject sample=new JSONObject(js("__motionSample")); samples.put(sample);
        assertTrue(name+" response budget (350ms navigation / 600ms full theme repaint)",sample.getDouble("firstFrameMs")<(name.startsWith("theme-")?600:350));
    }
    private void capture(File directory,String name) throws Exception {
        android.graphics.Bitmap bitmap=getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
        Thread.sleep(400);
    }
    /** The lyric steps must not depend on which song was last playing; a queued song leaves the queue unchanged. */
    private String queuedSongWithLyrics(PlayerApp app) throws Exception {
        String[][] ids={null};
        getInstrumentation().runOnMainSync(()->{int count=app.playback.player.getMediaItemCount();ids[0]=new String[count];for(int i=0;i<count;i++)ids[0][i]=app.playback.player.getMediaItemAt(i).mediaId;});
        for(String id:ids[0])if(app.library.details(id).optString("lyrics").contains("["))return id;
        return null;
    }
    public void testMotionAndPlayback() throws Exception {
        assertEquals("Physical M3X","Shanling M3X",android.os.Build.MODEL.replace('_',' '));
        String phase=((android.test.InstrumentationTestRunner)getInstrumentation()).getArguments().getString("phase","after");
        assertTrue(phase.matches("[a-z0-9-]+"));boolean baseline=phase.equals("before");
        PlayerApp app=PlayerApp.instance;app.awaitLibrary();
        activity=(MainActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD));
        waitJs("!!window.M3X_UI&&!!nativeCommand('poll').stats&&M3X_UI.tracks.length>0");
        JSONObject savedUi=new JSONObject(app.settings("ui").toString()),savedTools=new JSONObject(app.settings("tools").toString()),savedAudio=new JSONObject(app.settings("audio").toString());
        JSONObject scan=new JSONObject(app.library.scanStatus().toString());int tracks=app.library.all().size();
        JSONObject original=new JSONObject(js("nativeCommand('poll')"));
        assertEquals("No active sleep timer",0,original.optLong("sleepRemaining"));
        String queue=js("nativeCommand('queue').ids");
        android.view.accessibility.AccessibilityManager accessibility=(android.view.accessibility.AccessibilityManager)app.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
        assertFalse("Accessibility inflates WebView timing; a screen reader or UiAutomation is connected",accessibility.isEnabled());
        File directory=new File(app.getExternalFilesDir(null),"motion-"+phase);assertTrue(directory.isDirectory()||directory.mkdirs());
        JSONObject evidence=new JSONObject().put("phase",phase).put("device",android.os.Build.MODEL).put("tracks",tracks).put("samples",samples);
        Map<String,Object> savedFields=new LinkedHashMap<>();
        for(String name:new String[]{"manualGain","rampStart","pauseAfterRamp","rampFrom","rampTarget","fadeTrackStart","listenedMs","counted","previousAlbum","statsPlaying"})savedFields.put(name,field(name).get(app.playback));
        JSONObject savedStats=new JSONObject(original.getJSONObject("stats").toString());
        long[] position={0};float[] volume={1};int[] underruns={0};
        AnalyticsListener listener=new AnalyticsListener(){@Override public void onAudioUnderrun(EventTime time,int size,long duration,long elapsed){underruns[0]++;}};
        getInstrumentation().runOnMainSync(()->{position[0]=app.playback.player.getCurrentPosition();volume[0]=app.playback.player.getVolume();});
        try {
            getInstrumentation().runOnMainSync(()->{try{app.playback.player.pause();field("manualGain").setFloat(app.playback,0);field("rampStart").setLong(app.playback,0);app.playback.player.setVolume(0);}catch(Exception e){throw new RuntimeException(e);}});
            setting("visualTheme","endfield");setting("theme","light");setting("layout","album");
            if(!baseline){
                waitJs("!!window.M3X_MOTION");
                act("var e=document.querySelector('[data-setting=reduceMotion]');e.checked=false;e.dispatchEvent(new Event('change',{bubbles:true}))");
                assertEquals("Motion enabled","\"endfield\"",js("document.querySelector('.m3x-screen').dataset.motion"));
                assertEquals("Native Web Animations available","\"function\"",js("typeof document.querySelector('.m3x-screen').animate"));
            }
            evidence.put("userAgent",new JSONArray("["+js("navigator.userAgent")+"]").getString(0));
            // Warm font/image resources equally before comparing repeated transitions.
            for(String page:new String[]{"profile","player","library"}){act(click(".m3x-nav [data-page="+page+"]"));Thread.sleep(500);}
            for(int round=0;round<3;round++)for(String page:new String[]{"profile","player","library"})measure("paused-"+page,click(".m3x-nav [data-page="+page+"]"));
            act(click(".m3x-nav [data-page=player]"));Thread.sleep(300);
            for(int round=0;round<3;round++){
                measure("sheet-open",click(".m3x-output"));
                measure("sheet-close",click("[data-action=close-output]"));
                assertEquals("Sheet closes","false",js("document.getElementById('output-dialog').open"));
            }
            act(click(".m3x-output")+";M3X_UI.closeDialog(document.getElementById('output-dialog'));M3X_UI.openDialog(document.getElementById('output-dialog'))");
            Thread.sleep(250);assertEquals("Reopen cancels pending close","true",js("document.getElementById('output-dialog').open"));
            act("M3X_UI.closeDialog(document.getElementById('output-dialog'),true)");
            act("['player','library','profile','player','profile'].forEach(function(p){document.querySelector('.m3x-nav [data-page='+p+']').click()})");
            Thread.sleep(300);assertEquals("Latest navigation wins","false",js("document.querySelector('[data-panel=profile]').hidden"));
            assertEquals("Only one visible page","1",js("document.querySelectorAll('[data-panel]:not([hidden])').length"));
            // Actual device song, muted after decoding; no synthetic song or catalog changes.
            getInstrumentation().runOnMainSync(()->{app.playback.player.seekTo(Math.min(30000,Math.max(0,app.playback.player.getDuration()/2)));app.playback.player.prepare();app.playback.player.play();});
            waitJs("nativeCommand('poll').playing");Thread.sleep(700);
            getInstrumentation().runOnMainSync(()->app.playback.player.addAnalyticsListener(listener));
            int[] buffers={0,0};
            getInstrumentation().runOnMainSync(()->{androidx.media3.exoplayer.DecoderCounters c=app.playback.player.getAudioDecoderCounters();if(c!=null){c.ensureUpdated();buffers[0]=c.renderedOutputBufferCount;}});
            JSONObject audioStart=new JSONObject(js("nativeCommand('poll')"));
            for(int round=0;round<3;round++)for(String page:new String[]{"profile","player","library"})measure("playing-"+page,click(".m3x-nav [data-page="+page+"]"));
            JSONObject audioEnd=new JSONObject(js("nativeCommand('poll')"));
            getInstrumentation().runOnMainSync(()->{androidx.media3.exoplayer.DecoderCounters c=app.playback.player.getAudioDecoderCounters();if(c!=null){c.ensureUpdated();buffers[1]=c.renderedOutputBufferCount;}app.playback.player.removeAnalyticsListener(listener);app.playback.player.pause();});
            assertTrue("Audio advances during animation",audioEnd.getDouble("position")>audioStart.getDouble("position")+3);
            assertTrue("Decoded audio output progresses",buffers[1]>buffers[0]);
            assertEquals("No audio underrun during measured navigation",0,underruns[0]);
            evidence.put("audio",new JSONObject().put("muted",true).put("decoder",audioEnd.optString("decoder")).put("outputRate",audioEnd.optInt("outputRate")).put("renderedBufferDelta",buffers[1]-buffers[0]).put("underruns",underruns[0]).put("positionDelta",audioEnd.getDouble("position")-audioStart.getDouble("position")));
            if(!baseline){
                act(click("[data-page=player]"));
                String id=js("M3X_UI.currentTrack().id");
                act("M3X_UI.next();M3X_UI.next();M3X_UI.previous();document.getElementById('audio').pause()");
                waitJs("document.querySelector('.m3x-cover').getAttribute('src')===(M3X_UI.currentTrack().cover||'cover-placeholder.svg')");
                Thread.sleep(400);
                assertEquals("Final mini cover agrees","true",js("document.querySelector('.m3x-mini-cover').getAttribute('src')===(M3X_UI.currentTrack().cover||'cover-placeholder.svg')"));
                assertFalse("Real track switch exercised",id.equals(js("M3X_UI.currentTrack().id")));
                String lyricSong=queuedSongWithLyrics(app);assertNotNull("Queued song with timed lyrics",lyricSong);
                act("M3X_UI.showTrack("+JSONObject.quote(lyricSong)+");document.getElementById('audio').pause()");
                act(click("[data-page=lyrics]"));waitJs("document.querySelectorAll('#lyrics-lines button').length>3");
                act("var lines=document.querySelectorAll('#lyrics-lines button');M3X_MOTION.centerLyric(lines[lines.length-1]);M3X_MOTION.centerLyric(lines[2]);");
                Thread.sleep(350);
                assertEquals("Lyric scroll stays contained","true",js("document.documentElement.scrollTop===0&&document.querySelector('.m3x-nav').getBoundingClientRect().bottom<=innerHeight+1"));
                act("M3X_MOTION.centerLyric(document.querySelector('#lyrics-lines button:last-child'));document.getElementById('lyrics-lines').dispatchEvent(new Event('touchstart'));window.__scroll=document.getElementById('lyrics-lines').scrollTop");
                Thread.sleep(300);assertEquals("Touch cancels lyric follow",js("__scroll"),js("document.getElementById('lyrics-lines').scrollTop"));
                act("var e=document.querySelector('[data-setting=reduceMotion]');e.checked=true;e.dispatchEvent(new Event('change',{bubbles:true}))");
                measure("reduced-navigation",click(".m3x-nav [data-page=profile]"));
                assertEquals("Reduced motion settles the visible page", "\"none\"",js("getComputedStyle(document.querySelector('[data-panel=profile]')).transform"));
                act("M3X_UI.openDialog(document.getElementById('output-dialog'));M3X_UI.closeDialog(document.getElementById('output-dialog'))");
                assertEquals("Reduced sheet closes immediately","false",js("document.getElementById('output-dialog').open"));
                act("var e=document.querySelector('[data-setting=reduceMotion]');e.checked=false;e.dispatchEvent(new Event('change',{bubbles:true}))");
                setting("theme","dark");measure("theme-dark-navigation",click(".m3x-nav [data-page=player]"));
                measure("dark-sheet",click(".m3x-output"));act("M3X_UI.closeDialog(document.getElementById('output-dialog'),true)");Thread.sleep(700);
                setting("theme","light");measure("theme-light-navigation",click(".m3x-nav [data-page=library]"));
                setting("visualTheme","classic");measure("classic-navigation",click(".m3x-nav [data-page=player]"));
                assertEquals("Classic appearance has its own motion set","\"classic\"",js("document.querySelector('.m3x-screen').dataset.motion"));
                assertFalse("Accessibility stayed off for every timed step",accessibility.isEnabled());
                // Screenshots connect UiAutomation, which enables accessibility and slows WebView for the rest of the process: keep them after all timing.
                setting("visualTheme","endfield");setting("theme","dark");act(click(".m3x-nav [data-page=player]"));Thread.sleep(900);capture(directory,"dark-player");
                act(click(".m3x-output"));Thread.sleep(900);capture(directory,"dark-sheet");act("M3X_UI.closeDialog(document.getElementById('output-dialog'),true)");Thread.sleep(700);
                setting("theme","light");act(click(".m3x-nav [data-page=library]"));Thread.sleep(900);capture(directory,"light-library");
            }
            assertEquals("Catalog unchanged",tracks,app.library.all().size());assertEquals("No scan",scan.toString(),app.library.scanStatus().toString());
            evidence.put("passed",true);
        } finally {
            try{act("window.nativeCommand=function(){return {}}");getInstrumentation().runOnMainSync(()->{activity.web.loadUrl("about:blank");activity.finish();});getInstrumentation().waitForIdleSync();}
            finally{
                getInstrumentation().runOnMainSync(()->{try{
                    app.playback.player.removeAnalyticsListener(listener);app.playback.player.pause();app.playback.load(original.getString("id"),false);app.playback.player.seekTo(position[0]);
                    for(Map.Entry<String,Object> entry:savedFields.entrySet())field(entry.getKey()).set(app.playback,entry.getValue());
                    field("stats").set(app.playback,new JSONObject(savedStats.toString()));field("lastStats").setLong(app.playback,SystemClock.elapsedRealtime());field("lastSave").setLong(app.playback,SystemClock.elapsedRealtime());
                    app.playback.player.setVolume(volume[0]);app.save("ui",savedUi);app.save("tools",savedTools);app.playback.player.setPlayWhenReady(!original.getBoolean("paused"));
                    org.json.JSONArray restoredQueue=new org.json.JSONArray();for(int i=0;i<app.playback.player.getMediaItemCount();i++)restoredQueue.put(app.playback.player.getMediaItemAt(i).mediaId);
                    evidence.put("queueRestored",queue.equals(restoredQueue.toString())).put("restoredTrackId",app.playback.currentTrack().getString("id"));
                }catch(Exception e){throw new RuntimeException(e);}});
                assertTrue("Queue restored",evidence.getBoolean("queueRestored"));assertEquals("Original track restored",original.getString("id"),evidence.getString("restoredTrackId"));
                evidence.put("uiRestored",savedUi.toString().equals(app.settings("ui").toString())).put("toolsRestored",savedTools.toString().equals(app.settings("tools").toString())).put("audioSettingsPreserved",savedAudio.toString().equals(app.settings("audio").toString()));
                try(FileOutputStream out=new FileOutputStream(new File(directory,"results.json"))){out.write(evidence.toString(2).getBytes("UTF-8"));}
                assertTrue("UI restored",evidence.getBoolean("uiRestored"));assertTrue("Tools restored",evidence.getBoolean("toolsRestored"));assertTrue("Audio settings preserved",evidence.getBoolean("audioSettingsPreserved"));
            }
        }
    }
    /** Separate recording pass. Screen recording is never used for performance numbers. */
    public void testMotionPreview() throws Exception {
        assertEquals("Physical M3X","Shanling M3X",android.os.Build.MODEL.replace('_',' '));
        PlayerApp app=PlayerApp.instance;app.awaitLibrary();
        JSONObject savedUi=new JSONObject(app.settings("ui").toString());
        activity=(MainActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON));
        try {
            waitJs("!!window.M3X_UI&&!!window.M3X_MOTION");
            setting("visualTheme","endfield");setting("theme","light");
            act("var e=document.querySelector('[data-setting=reduceMotion]');e.checked=false;e.dispatchEvent(new Event('change',{bubbles:true}))");
            for(String theme:new String[]{"light","dark"}) {
                setting("theme",theme);
                for(String page:new String[]{"library","player","profile","player"}){act(click(".m3x-nav [data-page="+page+"]"));Thread.sleep(1000);}
                act(click(".m3x-output"));Thread.sleep(1000);
                act(click("[data-action=close-output]"));Thread.sleep(600);
                act(click("[data-page=lyrics]"));Thread.sleep(1000);
                act(click("#lyrics-more"));Thread.sleep(1000);
                act("Array.from(document.querySelectorAll('dialog[open]')).forEach(function(d){M3X_UI.closeDialog(d)})");Thread.sleep(600);
                act(click(".m3x-nav [data-page=player]"));Thread.sleep(1000);
            }
        } finally {
            try{act("window.nativeCommand=function(){return {}}");getInstrumentation().runOnMainSync(()->{activity.web.loadUrl("about:blank");activity.finish();});getInstrumentation().waitForIdleSync();}
            finally{getInstrumentation().runOnMainSync(()->app.save("ui",savedUi));}
            assertEquals("Preview settings restored",savedUi.toString(),app.settings("ui").toString());
        }
    }

}
