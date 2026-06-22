package jp.co.sstinc.audio;

import android.os.Handler;

import jp.co.sstinc.sstaudio.AudioError;
import jp.co.sstinc.sstaudio.SharedAudioHandler;

@SuppressWarnings("WeakerAccess")
public class RawFloatRecorder {

    static {
        System.loadLibrary("c++_shared");
        System.loadLibrary("sstcommon");
        System.loadLibrary("sstaudio");
        System.loadLibrary("tssciv2dev");
    }

    /**
     * データを受け取るためのインターフェース
     */
    public interface Callback {
        /**
         * データを受け取ったときに呼ばれる
         * RawFloatRecorderのワーカースレッドで呼ばれる
         * @param data 受信データ
         */
        void callback(float[] data);
    }

    /**
     * JNIから呼ばれるエラーコールバック
     */
    public interface ErrorCallback_ {
        /**
         * エラーが発生したときに呼ばれる
         * @param error OpenSL ESのエラーコード
         */
        void audioError(int error);
    }

    /**
     * 外部にエラーを通知するためのインターフェース
     */
    public interface ErrorCallback {
        /**
         * エラーが発生したときに呼ばれる
         * @param error エラー情報
         */
        void audioError(AudioError error);
    }


    // worker thread
    private final Handler workerHandler = SharedAudioHandler.getInstance().handler;

    private long ctx;

    // データ受信コールバックオブジェクト
    // 最初に設定しておくこと
    public Callback callback;

    // エラーコールバックオブジェクト
    // 最初に設定しておくこと
    public ErrorCallback errorCallback;


    public RawFloatRecorder() {
    }

    /**
     * 開始
     */
    public void start() {
        workerHandler.post(() -> {
            ctx = _prepare(data -> {
                workerHandler.post(() -> {
                    callback.callback(data);
                });
            }, errorCode -> {
                workerHandler.post(() -> {
                    AudioError.Type type = AudioError.Type.OpenSlEsError;
                    AudioError error = new AudioError("AudioError " + errorCode, type, errorCode);

                    errorCallback.audioError(error);
                });
            });
            _start(ctx);
        });
    }

    /**
     * 停止
     */
    public void stop() {
        workerHandler.post(() -> _stop(ctx));
    }

    /**
     * 解放
     *
     * 使い終わったら必ず最後に呼ぶこと
     */
    public void release() {
        workerHandler.post(() -> _release(ctx));
    }

    native long _prepare(Callback callback, ErrorCallback_ errorCallback);
    native void _start(long ctx);
    native void _stop(long ctx);
    native void _release(long ctx);
}