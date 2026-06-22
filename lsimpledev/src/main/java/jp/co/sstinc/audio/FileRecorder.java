package jp.co.sstinc.audio;

import android.content.Context;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import jp.co.sstinc.sstaudio.AudioError;
import jp.co.sstinc.sstaudio.AudioProperties;
import jp.co.sstinc.sstaudio.SharedAudioHandler;

@SuppressWarnings("WeakerAccess")
public class FileRecorder {

    static {
        System.loadLibrary("c++_shared");
        System.loadLibrary("sstcommon");
        System.loadLibrary("sstaudio");
        System.loadLibrary("tssciv2dev");
    }

    public FileRecorder(@NonNull String filepath) {
        this.filepath = filepath;
        ctx = _prepare(AudioProperties.getSampleRate(), filepath);
    }

    public FileRecorder(@NonNull Context context, @NonNull String filepath) {
        AudioProperties.initialize(context);
        this.filepath = filepath;
        ctx = _prepare(AudioProperties.getSampleRate(), filepath);
    }

    private long ctx;
    public @NonNull String filepath;
    public void release() {
        SharedAudioHandler.getInstance().handler.post(new Runnable() {
            @Override
            public void run() {
                _release(ctx);
            }
        });
    }

    public void startRecord() {
        SharedAudioHandler.getInstance().handler.post(new Runnable() {
            @Override
            public void run() {
                long result = _startRecord(ctx);
                if (result != 0) {
                    if (errorCallbackObj != null) {
                        AudioError.Type type = (result == -1)? AudioError.Type.CommandTimeout : AudioError.Type.OpenSlEsError;
                        errorCallbackObj.onError(new AudioError("RawRecorder.start AudioError " + result, type, result));
                    }
                }
            }
        });
    }

    public void stopRecord(@Nullable Runnable callback) {
        SharedAudioHandler.getInstance().handler.post(new Runnable() {
            @Override
            public void run() {
                _stopRecord(ctx, callback);
            }
        });
    }

    public long totalBytes() {
        return _totalBytes(ctx);
    }

    @Keep
    public void errorCallback(int errorCode) {
        // 別スレッドで呼ばれる可能性がある
        if (errorCallbackObj != null) {
            AudioError.Type errorType = AudioError.Type.fromSSTAudioErrorCode(errorCode);
            errorCallbackObj.onError(new AudioError(errorType));
        }
    }

    interface ErrorCallback {
        void onError(AudioError error);
    }
    @SuppressWarnings("WeakerAccess")
    public ErrorCallback errorCallbackObj = null;

    native long _prepare(int sampleRate, String filepath);
    native void _release(long p);

    native long _startRecord(long p);
    native void _stopRecord(long p, @Nullable Runnable callback);

    native long _totalBytes(long p);

}
