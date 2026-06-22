#include <jni.h>
#include <android/log.h>

#include <shared_audio.h>
#include <TaskQueueThread.hpp>

namespace {

    using namespace sst::audio;

    JavaVM* getJavaVM(JNIEnv *env) {
        JavaVM *javaVM_;
        env->GetJavaVM(&javaVM_);
        return javaVM_;
    }

    class RawFloatRecorder final : public std::enable_shared_from_this<RawFloatRecorder> {
    private:
        std::shared_ptr<AudioRecorder> recorder;
        std::function<void()> recordStopFunc = nullptr;

        JavaVM  *javaVM;

        JNIEnv  *envInWorkerThread;
        jobject dataCallbackObj;
        jclass dataCallbackClass;
        jmethodID dataCallbackMethod;

        jobject  errorObj;

        std::unique_ptr<TaskQueueThread> workerThreadQueue;

    public:

        RawFloatRecorder(const RawFloatRecorder&) = delete;
        RawFloatRecorder(RawFloatRecorder&&) = delete;
        RawFloatRecorder operator =(RawFloatRecorder const &) = delete;
        RawFloatRecorder operator =(RawFloatRecorder&&) = delete;

        RawFloatRecorder(JNIEnv *env, jobject callbackObj, jobject errorObj)
                : javaVM(getJavaVM(env)),
                  dataCallbackObj(env->NewGlobalRef(callbackObj)),
                  errorObj(env->NewGlobalRef(errorObj))
        {
            workerThreadQueue = std::make_unique<TaskQueueThread>(20);
        }

        virtual ~RawFloatRecorder() = default;

        int64_t startRecord() {

            recorder = SharedAudioRecorderProvider::sharedInstance().audioRecorder();
            workerThreadQueue->start();
            workerThreadQueue->enqueue_task([this]() {
                // 最初にjavaにスレッドアタッチしておく

                javaVM->AttachCurrentThread(&this->envInWorkerThread, nullptr);

                this->dataCallbackClass = this->envInWorkerThread->GetObjectClass(this->dataCallbackObj);
                this->dataCallbackMethod = this->envInWorkerThread->GetMethodID(this->dataCallbackClass, "callback", "([F)V");
            });

            if (!recorder->isAvailable()) {
                return recorder->initializationResult();
            }

            auto weak_self = std::weak_ptr<RawFloatRecorder>(shared_from_this());
            auto result = recorder->startRecordFloat(
                    [weak_self](const float* nextBuffer, int32_t size) {
                        // ここはsstaudioのワーカースレッドで呼ばれる
                        auto self = weak_self.lock();
                        if (self == nullptr) {
                            return;
                        }
                        auto buffer = std::make_shared<std::vector<float>>(size);
                        std::copy(nextBuffer, nextBuffer + size, buffer->begin());

                        self->workerThreadQueue->enqueue_task([weak_self, buffer]() {
                            // ここはTaskQueueThreadのスレッドで呼ばれる
                            auto self = weak_self.lock();
                            if (self == nullptr) {
                                return;
                            }

                            if (self->dataCallbackObj == nullptr) {
                                // stopRecord()が呼ばれたあと
                                return;
                            }

                            // javaのfloat arrayを作成
                            jfloatArray jarray = self->envInWorkerThread->NewFloatArray(buffer->size());
                            auto *jarrayPtr = self->envInWorkerThread->GetFloatArrayElements(jarray, nullptr);
                            // jarrayPtrにbufferの内容をコピー
                            std::copy(buffer->begin(), buffer->end(), jarrayPtr);
                            // jarrayPtrの変更をjarrayに反映
                            self->envInWorkerThread->ReleaseFloatArrayElements(jarray, jarrayPtr, 0);

                            self->envInWorkerThread->CallVoidMethod(self->dataCallbackObj, self->dataCallbackMethod, jarray);

                            self->envInWorkerThread->DeleteLocalRef(jarray);
                        });

                    }, [weak_self](int32_t errorCode) {
                        // ここはOpenSLESのAudioスレッドで呼ばれる
                        auto self = weak_self.lock();
                        if (self == nullptr) {
                            return;
                        }
                        self->callJavaAudioErrorCallback(errorCode);
                    });
            if (result.code == SL_RESULT_SUCCESS) {
                recordStopFunc = result.stopFunc;
            }

            return result.code;
        }

        void stopRecord() {
            if (recordStopFunc != nullptr) {
                recordStopFunc();
                recordStopFunc = nullptr;
            }
            recorder = nullptr;

            // すべての処理を行ってから終わる
            workerThreadQueue->drain();
            workerThreadQueue->enqueue_task([this]() {
                envInWorkerThread->DeleteGlobalRef(dataCallbackObj);
                envInWorkerThread->DeleteGlobalRef(errorObj);
                dataCallbackObj = nullptr;
                errorObj = nullptr;

                // 最後にjavaにスレッドデタッチしておく
                javaVM->DetachCurrentThread();
            });
            workerThreadQueue->drain();
            workerThreadQueue->stop();
        }

    private:


        void callJavaAudioErrorCallback(int errorCode) {
            // 頻繁に呼ばれないことを前提に、都度java側にスレッドをアタッチしている
            JNIEnv *env;
            jint result = this->javaVM->AttachCurrentThread(&env, nullptr);

            if (result != 0) {
                __android_log_print(ANDROID_LOG_ERROR, "sstaudio", "jni AttachCurrentThread error %d", result);
            } else {

                jclass c = env->GetObjectClass(this->errorObj);

                jmethodID m = env->GetMethodID(c, "audioError", "(I)V");

                env->CallVoidMethod(this->errorObj, m, errorCode);

                result = this->javaVM->DetachCurrentThread();
                if (result != 0) {
                    __android_log_print(ANDROID_LOG_ERROR, "sstaudio", "jni DetachCurrentThread error %d", result);
                }
            }
        }
    };

}

extern "C" {
JNIEXPORT jlong JNICALL
Java_jp_co_sstinc_audio_RawFloatRecorder__1prepare(JNIEnv *env, jobject __unused instance, jobject callbackObj, jobject errorObj) {
    auto rawRecord = std::make_shared<RawFloatRecorder>(env, callbackObj, errorObj);

    // jlong rawRecordPtr = reinterpret_cast<jlong>(&rawRecord);
    // だと参照カウントが増えないので破棄されてしまう。
    // なので、shared_ptrのポインタをヒープに置いてそれを返す。
    return reinterpret_cast<jlong>(new std::shared_ptr<RawFloatRecorder>(rawRecord));
}

JNIEXPORT void JNICALL
Java_jp_co_sstinc_audio_RawFloatRecorder__1start(JNIEnv *env, jobject __unused instance, jlong p) {
    auto rawRecordPtr = reinterpret_cast<std::shared_ptr<RawFloatRecorder>*>(p);
    (*rawRecordPtr)->startRecord();
}

JNIEXPORT void JNICALL
Java_jp_co_sstinc_audio_RawFloatRecorder__1stop(JNIEnv *env, jobject __unused instance, jlong p) {
    auto rawRecordPtr = reinterpret_cast<std::shared_ptr<RawFloatRecorder>*>(p);
    (*rawRecordPtr)->stopRecord();
}

JNIEXPORT void JNICALL
Java_jp_co_sstinc_audio_RawFloatRecorder__1release(JNIEnv *env, jobject __unused instance, jlong p) {
    auto rawRecordPtr = reinterpret_cast<std::shared_ptr<RawFloatRecorder>*>(p);
    delete rawRecordPtr;
}

}
