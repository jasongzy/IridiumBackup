#include <jni.h>
#include <fts.h>
#include <sys/stat.h>
#include <string.h>

// A slash separates names because a filesystem component cannot contain one.
static bool matches(const char *names, const char *name, bool prefix) {
    while (*names) {
        const size_t length = strcspn(names, "/");
        if (strncmp(names, name, length) == 0 && (prefix || name[length] == '\0')) return true;
        names += length;
        if (*names) ++names;
    }
    return false;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xayah_libnative_NativeLib_measure(JNIEnv *env, jobject, jstring path, jstring children, jstring prefixes) {
    const char *root = env->GetStringUTFChars(path, nullptr);
    const char *excludedChildren = env->GetStringUTFChars(children, nullptr);
    const char *excludedPrefixes = env->GetStringUTFChars(prefixes, nullptr);
    char *paths[] = {const_cast<char *>(root), nullptr};
    FTS *tree = fts_open(paths, FTS_PHYSICAL | FTS_NOCHDIR, nullptr);
    jlong size = 0;
    if (tree) {
        while (FTSENT *entry = fts_read(tree)) {
            if (entry->fts_info == FTS_DP || entry->fts_info == FTS_ERR || entry->fts_info == FTS_NS) continue;
            const char *name = entry->fts_name;
            if ((entry->fts_level == 1 && matches(excludedChildren, name, false)) ||
                (entry->fts_level > 0 && matches(excludedPrefixes, name, true))) {
                if (entry->fts_info == FTS_D) fts_set(tree, entry, FTS_SKIP);
                continue;
            }
            if (!S_ISDIR(entry->fts_statp->st_mode)) size += entry->fts_statp->st_size;
        }
        fts_close(tree);
    }
    env->ReleaseStringUTFChars(prefixes, excludedPrefixes);
    env->ReleaseStringUTFChars(children, excludedChildren);
    env->ReleaseStringUTFChars(path, root);
    return size;
}
