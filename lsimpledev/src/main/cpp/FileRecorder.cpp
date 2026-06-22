#include <jni.h>
#include "raw.h"
#include "log.h"

#include "raw.h"
#include <functional>
#include "audio.h"
#include "shared_audio.h"
#include "log.h"

#include <iostream>
#include <fstream>

namespace {

    void write_wav_header(void *out, int sample_rate, sst::audio::SamplingType format_type, size_t data_bytes) {

        size_t totalSizeByteSize = data_bytes + 44; // NOLINT(readability-magic-numbers,cppcoreguidelines-avoid-magic-numbers)

        uint16_t packet_size = (format_type == sst::audio::SamplingType_Float) ? (uint16_t)4 : (uint16_t)2;

        uint16_t channel = 1;
        uint16_t bytes_ber_frame = (uint16_t)(packet_size * channel);
        uint16_t bits_per_channel = (uint16_t)(8 * packet_size);
        uint16_t format_tag = (format_type == sst::audio::SamplingType_Float) ? (uint16_t)3 : (uint16_t)1;
        uint32_t bytes_per_sec = bytes_ber_frame * (uint32_t)sample_rate;
        uint16_t block_align = bytes_ber_frame;


        uint32_t* p = (uint32_t*)out;
        uint16_t* p16;
        *(p++) = 'FFIR'; //'RIFF';
        *(p++) = (uint32_t)totalSizeByteSize - 8;
        *(p++) = 'EVAW'; // 'WAVE';
        *(p++) = ' tmf'; // 'fmt ';
        *(p++) = 16;     // fmt のチャンクサイズ
        p16 = (uint16_t*)p;
        *(p16++) = format_tag;
        *(p16++) = channel;
        p++;
        *(p++) = (uint32_t)sample_rate;
        *(p++) = bytes_per_sec;
        p16 = (uint16_t*)p;
        *(p16++) = block_align;
        *(p16++) = bits_per_channel;
        p++;
        *(p++) = 'atad'; // 'data';
        *(p++) = (uint32_t)data_bytes;
    }

    class FileRecorder : public std::enable_shared_from_this<FileRecorder> {
    private:
        const int sampleRate;
        const std::function<void(int)> errorCallback;
        bool initialized = false;

        std::unique_ptr<sst::audio::TaskQueueThread> workerThread;

        const std::string filename;
        std::ofstream file;
        int total_len_bytes;

        sst::audio::SamplingType type;

        std::shared_ptr<sst::audio::AudioRecorder> recorder;
        std::function<void()> stopFunc = nullptr;

        FileRecorder(int sampleRate, const std::string &filename, std::function<void(int)> errorCallback)
                : sampleRate(sampleRate)
                , filename(filename)
                , workerThread(std::make_unique<sst::audio::TaskQueueThread>(10)) // NOLINT(readability-magic-numbers)
                , errorCallback(std::move(errorCallback))
                , recorder{sst::audio::SharedAudioRecorderProvider::sharedInstance().audioRecorder()}
                , total_len_bytes(0)
        {
        }
    public:
        static std::shared_ptr<FileRecorder> create(int sampleRate, const std::string &file_name, std::function<void(int)> errorCallback)
        {
            // make_sharedでprivateコンストラクタを呼べないため
            return std::shared_ptr<FileRecorder>(new FileRecorder(sampleRate, file_name, errorCallback));

        }

        virtual ~FileRecorder() {
            if (stopFunc != nullptr) {
                stopFunc();
                stopFunc = nullptr;
            }
        };

        int64_t start() {
            if (stopFunc != nullptr) {
                return UINT32_MAX; // エラー理由は一つしかないのでOpenSLのエラーとかぶらないものを使っている
            }
            workerThread->start();

            auto result = recorder->startRecordRaw(
                    [self = this->shared_from_this() ](const uint8_t *a, int32_t num) {

                        auto buf = std::make_shared<std::vector<uint8_t>>(num);
                        std::copy(a, a + num, buf->begin());

                        self->workerThread->enqueue_task([self, buf, num]() {

                            if (!self->initialized) {
                                self->type = self->recorder->formatType();

                                self->file.open(self->filename, std::ios::out | std::ios::binary);

                                self->initialized = true;
                            }

                            self->file.seekp(44 + self->total_len_bytes);
                            self->file.write((const char*)buf->data(), buf->size());
                            self->total_len_bytes += num;
                        });
                    },
                    errorCallback
            );
            if (result.code == SL_RESULT_SUCCESS) {
                stopFunc = result.stopFunc;
            }

            return result.code;
        }

        void stop(std::function<void()> callback) {
            if (stopFunc != nullptr) {
                auto sample_rate = recorder->sampleRate();
                auto type = recorder->formatType();

                stopFunc();
                stopFunc = nullptr;

                workerThread->enqueue_task([self = shared_from_this(), sample_rate, type, callback]() {
                    self->file.seekp(0);

                    int8_t buf[44];
                    write_wav_header(buf, sample_rate, type, self->total_len_bytes);
                    self->file.write((const char*)buf, sizeof(buf));
                    self->file.close();

                    callback();
                });
            }
        }

        long total_bytes() {
            return total_len_bytes + 44;
        }

    };
}

template <typename T>
struct Container {
    std::shared_ptr<T> o;
    Container(std::shared_ptr<T> o) : o(o) {}
    ~Container() = default;
};

extern "C" {
JNIEXPORT jlong JNICALL
Java_jp_co_sstinc_audio_FileRecorder__1prepare(JNIEnv *env0, jobject instance, jint sampleRate, jstring file_name) {

    JavaVM *javaVM;
    env0->GetJavaVM(&javaVM);

    auto deleter = [javaVM](_jobject *o) {
        JNIEnv *env2 = nullptr;
        javaVM->GetEnv(reinterpret_cast<void**>(&env2), JNI_VERSION_1_6);
        if (env2 == nullptr) {
            LOGD("GetEnv error");
            return;
        }
        env2->DeleteGlobalRef(o);
    };

    std::shared_ptr<_jobject> globalInstance(env0->NewGlobalRef(instance), std::move(deleter));

    auto errorCallback = [javaVM, globalInstance](int errorCode){
        // 頻繁に呼ばれないことを前提に、都度java側にスレッドをアタッチしている
        JNIEnv *env;
        jint result = javaVM->AttachCurrentThread(&env, nullptr);

        if (result != 0) {
            LOGE("RawRecorder : AttachCurrentThread error %u", result);
            return;
        }

        jclass c = env->GetObjectClass(globalInstance.get());

        jmethodID m = env->GetMethodID(c, "errorCallback", "(I)V");

        env->CallVoidMethod(globalInstance.get(), m, errorCode);

        result = javaVM->DetachCurrentThread();

        if (result != 0) {
            LOGE("RawRecorder : DetachCurrentThread error %u", result);
        }
    };

    const char* filename_c = env0->GetStringUTFChars(file_name, nullptr);

    auto p = new Container<FileRecorder>(FileRecorder::create(sampleRate, filename_c, errorCallback));

    env0->ReleaseStringUTFChars(file_name, filename_c);

    return reinterpret_cast<jlong>(p);
}

JNIEXPORT void JNICALL
Java_jp_co_sstinc_audio_FileRecorder__1release(JNIEnv *env, jobject instance, jlong p) {
    delete (Container<FileRecorder> *) p;
}

JNIEXPORT jlong JNICALL
Java_jp_co_sstinc_audio_FileRecorder__1startRecord(JNIEnv *env, jobject instance, jlong p) {

    auto _p = (Container<FileRecorder> *) p;
    auto result = _p->o->start();

    return result;
}

JNIEXPORT void JNICALL
Java_jp_co_sstinc_audio_FileRecorder__1stopRecord(JNIEnv *env, jobject instance, jlong p, jobject jcallback) {

    JavaVM *javaVM;
    env->GetJavaVM(&javaVM);

    auto callback_ref = env->NewGlobalRef(jcallback);

    auto _p = (Container<FileRecorder> *) p;
    _p->o->stop([javaVM, callback_ref]() {
        // 頻繁に呼ばれないことを前提に、都度java側にスレッドをアタッチしている
        JNIEnv *env;
        jint result = javaVM->AttachCurrentThread(&env, nullptr);

        if (result != 0) {
            LOGE("FileRecorder : AttachCurrentThread error %u", result);
            return;
        }

        jclass c = env->GetObjectClass(callback_ref);

        jmethodID m = env->GetMethodID(c, "run", "()V");

        env->CallVoidMethod(callback_ref, m);
        env->DeleteGlobalRef(callback_ref);

        result = javaVM->DetachCurrentThread();

        if (result != 0) {
            LOGE("FileRecorder : DetachCurrentThread error %u", result);
        }
    });
}

JNIEXPORT jlong JNICALL
Java_jp_co_sstinc_audio_FileRecorder__1totalBytes(JNIEnv *env, jobject instance, jlong p) {
    auto _p = (Container<FileRecorder> *) p;
    return _p->o->total_bytes();
}

}
