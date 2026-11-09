#include <jni.h>
#include <ftw.h>
#include <sys/stat.h>

namespace NativeNS {
    thread_local size_t total_size{0};

    int on_walking(const char *, const struct stat *p_stat, int, struct FTW *) {
        total_size += p_stat->st_size;
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xayah_libnative_NativeLib_calculateSize(JNIEnv *env, jobject, jstring path) {
    NativeNS::total_size = 0;
    const char *p_path = env->GetStringUTFChars(path, JNI_FALSE);
    nftw(p_path, &NativeNS::on_walking, 1024, FTW_PHYS);
    env->ReleaseStringUTFChars(path, p_path);
    return NativeNS::total_size;
}
