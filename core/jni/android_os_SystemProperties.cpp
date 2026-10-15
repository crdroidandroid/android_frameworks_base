/* //device/libs/android_runtime/android_os_SystemProperties.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "SysPropJNI"

#include <utility>
#include <optional>

#include "android-base/logging.h"
#include "android-base/parsebool.h"
#include "android-base/parseint.h"
#include "android-base/properties.h"
#include "utils/misc.h"
#include <utils/Log.h>
#include "jni.h"
#include "core_jni_helpers.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>

#if defined(__BIONIC__)
#include <sys/system_properties.h>
#include <algorithm>
#include <cerrno>
#include <cinttypes>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <linux/futex.h>
#include <string>
#include <string_view>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <unistd.h>
#include <unordered_map>
#include <vector>
#endif

namespace android {
namespace {

using android::base::ParseBoolResult;

template<typename Functor>
void ReadProperty(const prop_info* prop, Functor&& functor)
{
    auto thunk = [](void* cookie,
                    const char* /*name*/,
                    const char* value,
                    uint32_t /*serial*/) {
        std::forward<Functor>(*static_cast<Functor*>(cookie))(value);
    };
    __system_property_read_callback(prop, thunk, &functor);
}

template<typename Functor>
void ReadProperty(JNIEnv* env, jstring keyJ, Functor&& functor)
{
    ScopedUtfChars key(env, keyJ);
    if (!key.c_str()) {
        return;
    }
    const prop_info* prop = __system_property_find(key.c_str());
    if (!prop) {
        return;
    }
    ReadProperty(prop, std::forward<Functor>(functor));
}

jstring SystemProperties_getSS(JNIEnv* env, jclass clazz, jstring keyJ,
                               jstring defJ)
{
    jstring ret = defJ;
    ReadProperty(env, keyJ, [&](const char* value) {
        if (value[0]) {
            ret = env->NewStringUTF(value);
        }
    });
    if (ret == nullptr && !env->ExceptionCheck()) {
      ret = env->NewStringUTF("");  // Legacy behavior
    }
    return ret;
}

template <typename T>
T SystemProperties_get_integral(JNIEnv *env, jclass, jstring keyJ,
                                       T defJ)
{
    T ret = defJ;
    ReadProperty(env, keyJ, [&](const char* value) {
        android::base::ParseInt<T>(value, &ret);
    });
    return ret;
}

static jboolean jbooleanFromParseBoolResult(ParseBoolResult parseResult, jboolean defJ) {
    jboolean ret;
    switch (parseResult) {
        case ParseBoolResult::kError:
            ret = defJ;
            break;
        case ParseBoolResult::kFalse:
            ret = JNI_FALSE;
            break;
        case ParseBoolResult::kTrue:
            ret = JNI_TRUE;
            break;
    }
    return ret;
}

jboolean SystemProperties_get_boolean(JNIEnv *env, jclass, jstring keyJ,
                                      jboolean defJ)
{
    ParseBoolResult parseResult = ParseBoolResult::kError;
    ReadProperty(env, keyJ, [&](const char* value) {
        parseResult = android::base::ParseBool(value);
    });
    return jbooleanFromParseBoolResult(parseResult, defJ);
}

jlong SystemProperties_find(JNIEnv* env, jclass, jstring keyJ)
{
    ScopedUtfChars key(env, keyJ);
    if (!key.c_str()) {
        return 0;
    }
    const prop_info* prop = __system_property_find(key.c_str());
    return reinterpret_cast<jlong>(prop);
}

jstring SystemProperties_getH(JNIEnv* env, jclass clazz, jlong propJ)
{
    jstring ret;
    auto prop = reinterpret_cast<const prop_info*>(propJ);
    ReadProperty(prop, [&](const char* value) {
        ret = env->NewStringUTF(value);
    });
    return ret;
}

template <typename T>
T SystemProperties_get_integralH(CRITICAL_JNI_PARAMS_COMMA jlong propJ, T defJ)
{
    T ret = defJ;
    auto prop = reinterpret_cast<const prop_info*>(propJ);
    ReadProperty(prop, [&](const char* value) {
        android::base::ParseInt<T>(value, &ret);
    });
    return ret;
}

jboolean SystemProperties_get_booleanH(CRITICAL_JNI_PARAMS_COMMA jlong propJ, jboolean defJ)
{
    ParseBoolResult parseResult = ParseBoolResult::kError;
    auto prop = reinterpret_cast<const prop_info*>(propJ);
    ReadProperty(prop, [&](const char* value) {
        parseResult = android::base::ParseBool(value);
    });
    return jbooleanFromParseBoolResult(parseResult, defJ);
}

void SystemProperties_set(JNIEnv *env, jobject clazz, jstring keyJ,
                          jstring valJ)
{
    ScopedUtfChars key(env, keyJ);
    if (!key.c_str()) {
        return;
    }
    std::optional<ScopedUtfChars> value;
    if (valJ != nullptr) {
        value.emplace(env, valJ);
        if (!value->c_str()) {
            return;
        }
    }
    // Calling SystemProperties.set() with a null value is equivalent to an
    // empty string, but this is not true for the underlying libc function.
    const char* value_c_str = value ? value->c_str() : "";
    // Explicitly clear errno so we can recognize __system_property_set()
    // failures from failed system calls (as opposed to "init rejected your
    // request" failures).
    errno = 0;
    bool success;
    success = !__system_property_set(key.c_str(), value_c_str);
    if (!success) {
        if (errno != 0) {
            jniThrowExceptionFmt(env, "java/lang/RuntimeException",
                                 "failed to set system property \"%s\" to \"%s\": %m",
                                 key.c_str(), value_c_str);
        } else {
            // Must have made init unhappy, which will have logged something,
            // but there's no API to ask for more detail.
            jniThrowExceptionFmt(env, "java/lang/RuntimeException",
                                 "failed to set system property \"%s\" to \"%s\" (check logcat for reason)",
                                 key.c_str(), value_c_str);
        }
    }
}

JavaVM* sVM = nullptr;
jclass sClazz = nullptr;
jmethodID sCallChangeCallbacks;

void do_report_sysprop_change() {
    //ALOGI("Java SystemProperties: VM=%p, Clazz=%p", sVM, sClazz);
    if (sVM != nullptr && sClazz != nullptr) {
        JNIEnv* env;
        if (sVM->GetEnv((void **)&env, JNI_VERSION_1_4) >= 0) {
            //ALOGI("Java SystemProperties: calling %p", sCallChangeCallbacks);
            env->CallStaticVoidMethod(sClazz, sCallChangeCallbacks);
            // There should not be any exceptions. But we must guarantee
            // there are none on return.
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                LOG(ERROR) << "Exception pending after sysprop_change!";
            }
        }
    }
}

void SystemProperties_add_change_callback(JNIEnv *env, jobject clazz)
{
    // This is called with the Java lock held.
    if (sVM == nullptr) {
        env->GetJavaVM(&sVM);
    }
    if (sClazz == nullptr) {
        sClazz = (jclass) env->NewGlobalRef(clazz);
        sCallChangeCallbacks = env->GetStaticMethodID(sClazz, "callChangeCallbacks", "()V");
        add_sysprop_change_callback(do_report_sysprop_change, -10000);
    }
}

void SystemProperties_report_sysprop_change(JNIEnv /**env*/, jobject /*clazz*/)
{
    report_sysprop_change();
}


#if defined(__BIONIC__)
namespace pif_prop_virt {

constexpr const char* kPropertyDirectory = "/dev/__properties__/";
constexpr size_t kPropertyDirectoryLen = 20;
constexpr uint32_t kPropertyAreaMagic = 0x504f5250;
constexpr uint32_t kLongPropertyFlag = 1U << 16;

struct PropertyAreaHeader {
    uint32_t bytesUsed;
    uint32_t serial;
    uint32_t magic;
    uint32_t version;
    uint32_t reserved[28];
};

struct InlinePropertyInfo {
    uint32_t serial;
    char value[PROP_VALUE_MAX];
};

static_assert(sizeof(PropertyAreaHeader) == 128);
static_assert(sizeof(InlinePropertyInfo) == 4 + PROP_VALUE_MAX);

struct Mapping {
    uintptr_t start = 0;
    uintptr_t end = 0;
    int prot = 0;
    uint64_t offset = 0;
    std::string path;
    unsigned devMajor = 0;
    unsigned devMinor = 0;
    uint64_t inode = 0;
    char permLetter = 's';
    int fd = -1;
    bool replaced = false;
};

struct Override {
    Mapping* mapping = nullptr;
    uintptr_t offset = 0;
    std::string name;
    std::string value;
    bool wasLong = false;
    uintptr_t longOffset = 0;
    size_t longLen = 0;
};

struct State {
    std::unordered_map<std::string, std::string> exactMap;
    std::vector<std::pair<std::string, std::string>> suffixRules;
    bool debug = false;
    std::vector<Mapping> mappings;
    std::vector<Override> overrides;
};

struct MapsLine {
    uintptr_t start = 0;
    uintptr_t end = 0;
    uint64_t offset = 0;
    uint64_t inode = 0;
    char permissions[5] = {};
    unsigned devMajor = 0;
    unsigned devMinor = 0;
    std::string path;
};

bool parseMapsLine(const char* line, MapsLine& out) {
    unsigned long start = 0;
    unsigned long end = 0;
    unsigned long long offset = 0;
    unsigned long long inode = 0;
    char permissions[5] = {};
    char device[32] = {};
    char path[256] = {};
    if (sscanf(line, "%lx-%lx %4s %llx %31s %llu %255[^\n]", &start, &end, permissions, &offset,
               device, &inode, path) != 7) {
        return false;
    }

    std::string pathname(path);
    const size_t first = pathname.find_first_not_of(' ');
    if (first == std::string::npos) {
        return false;
    }
    pathname.erase(0, first);

    unsigned devMajor = 0;
    unsigned devMinor = 0;
    if (sscanf(device, "%x:%x", &devMajor, &devMinor) != 2) {
        return false;
    }

    out.start = start;
    out.end = end;
    out.offset = offset;
    out.inode = inode;
    memcpy(out.permissions, permissions, sizeof(out.permissions));
    out.devMajor = devMajor;
    out.devMinor = devMinor;
    out.path = std::move(pathname);
    return true;
}

int protFromPermissions(const char* permissions) {
    int prot = 0;
    if (permissions[0] == 'r') prot |= PROT_READ;
    if (permissions[1] == 'w') prot |= PROT_WRITE;
    if (permissions[2] == 'x') prot |= PROT_EXEC;
    return prot;
}

bool parseMappings(std::vector<Mapping>& mappings, bool debug) {
    FILE* maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) {
        ALOGE("[PROP-VIRT] cannot open /proc/self/maps: %s", strerror(errno));
        return false;
    }

    char line[512];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        MapsLine entry = {};
        if (!parseMapsLine(line, entry) ||
            strncmp(entry.path.c_str(), kPropertyDirectory, kPropertyDirectoryLen) != 0) {
            continue;
        }

        const int prot = protFromPermissions(entry.permissions);
        if ((prot & PROT_READ) == 0 || entry.start >= entry.end || entry.offset != 0) {
            continue;
        }

        auto* header = reinterpret_cast<const PropertyAreaHeader*>(entry.start);
        const size_t length = entry.end - entry.start;
        if (length < sizeof(*header) || header->magic != kPropertyAreaMagic ||
            header->bytesUsed < sizeof(*header) || header->bytesUsed > length) {
            continue;
        }

        mappings.push_back({
                .start = entry.start,
                .end = entry.end,
                .prot = prot,
                .offset = entry.offset,
                .path = std::move(entry.path),
                .devMajor = entry.devMajor,
                .devMinor = entry.devMinor,
                .inode = entry.inode,
                .permLetter = entry.permissions[3],
        });
    }

    fclose(maps);
    if (mappings.empty()) {
        ALOGE("[PROP-VIRT] no valid property areas found");
        return false;
    }
    if (debug) {
        ALOGD("[PROP-VIRT] parsed %zu property area mappings", mappings.size());
    }
    return true;
}

Mapping* mappingFor(State& state, const prop_info* info) {
    const uintptr_t address = reinterpret_cast<uintptr_t>(info);
    for (auto& mapping : state.mappings) {
        if (address >= mapping.start && address + sizeof(InlinePropertyInfo) <= mapping.end) {
            return &mapping;
        }
    }
    return nullptr;
}

const char* overrideFor(const State& state, const char* name) {
    auto it = state.exactMap.find(name);
    if (it != state.exactMap.end() && !it->second.empty()) {
        return it->second.c_str();
    }
    const size_t nameLen = strlen(name);
    for (const auto& [suffix, val] : state.suffixRules) {
        if (!val.empty() && nameLen >= suffix.size() &&
            strcmp(name + nameLen - suffix.size(), suffix.c_str()) == 0) {
            return val.c_str();
        }
    }
    return nullptr;
}

void enumerateProperty(void* cookie, const char* name, const char* value, uint32_t serial) {
    auto* state = static_cast<State*>(cookie);
    if (name == nullptr) {
        return;
    }

    const char* replacement = overrideFor(*state, name);
    if (replacement == nullptr) {
        return;
    }
    if (strlen(replacement) >= PROP_VALUE_MAX) {
        ALOGE("[PROP-VIRT] %s override exceeds PROP_VALUE_MAX; skipping", name);
        return;
    }

    const prop_info* info = __system_property_find(name);
    if (info == nullptr) {
        ALOGE("[PROP-VIRT] %s not found; skipping", name);
        return;
    }
    Mapping* mapping = mappingFor(*state, info);
    if (mapping == nullptr) {
        ALOGE("[PROP-VIRT] %s has no validated owning area; skipping", name);
        return;
    }

    Override entry{.mapping = mapping,
                   .offset = reinterpret_cast<uintptr_t>(info) - mapping->start,
                   .name = name,
                   .value = replacement};
    if ((serial & kLongPropertyFlag) != 0) {
        if (state->debug) {
            ALOGD("[PROP-VIRT] %s is long-encoded; demoting to inline", name);
        }
        const uintptr_t infoAddr = reinterpret_cast<uintptr_t>(info);
        const uintptr_t valueAddr = reinterpret_cast<uintptr_t>(value);
        if (valueAddr >= infoAddr + sizeof(InlinePropertyInfo)) {
            entry.wasLong = true;
            entry.longOffset = valueAddr - infoAddr;
            entry.longLen = strlen(value);
        }
    }
    state->overrides.push_back(std::move(entry));
}

void enumerateCallback(const prop_info* info, void* cookie) {
    if (info == nullptr) {
        return;
    }
    __system_property_read_callback(info, enumerateProperty, cookie);
}

void warmupCallback(const prop_info*, void*) {}

bool preflightOpen(Mapping& mapping) {
    mapping.fd = open(mapping.path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (mapping.fd < 0) {
        ALOGE("[PROP-VIRT] cannot open %s: %s", mapping.path.c_str(), strerror(errno));
        return false;
    }

    struct stat st = {};
    if (fstat(mapping.fd, &st) != 0) {
        ALOGE("[PROP-VIRT] cannot stat %s: %s", mapping.path.c_str(), strerror(errno));
        return false;
    }
    if (major(st.st_dev) != mapping.devMajor || minor(st.st_dev) != mapping.devMinor ||
        static_cast<uint64_t>(st.st_ino) != mapping.inode) {
        ALOGE("[PROP-VIRT] %s identity mismatch", mapping.path.c_str());
        return false;
    }

    PropertyAreaHeader header = {};
    const size_t length = mapping.end - mapping.start;
    const ssize_t readBytes = pread(mapping.fd, &header, sizeof(header), 0);
    if (readBytes != static_cast<ssize_t>(sizeof(header)) || header.magic != kPropertyAreaMagic ||
        header.bytesUsed < sizeof(header) || header.bytesUsed > length) {
        ALOGE("[PROP-VIRT] %s failed area header revalidation", mapping.path.c_str());
        return false;
    }
    return true;
}

bool remapPrivate(Mapping& mapping) {
    const size_t length = mapping.end - mapping.start;
    mapping.replaced = true;
    void* remapped = mmap(reinterpret_cast<void*>(mapping.start), length,
                          PROT_READ | PROT_WRITE, MAP_FIXED | MAP_PRIVATE, mapping.fd, 0);
    if (remapped == MAP_FAILED) {
        ALOGE("[PROP-VIRT] private remap failed for %s: %s", mapping.path.c_str(), strerror(errno));
        return false;
    }
    return true;
}

bool applyOverride(const Mapping& mapping, const Override& override) {
    const size_t length = mapping.end - mapping.start;
    if (override.offset + sizeof(InlinePropertyInfo) > length) {
        return false;
    }

    auto* info = reinterpret_cast<InlinePropertyInfo*>(mapping.start + override.offset);

    if (override.wasLong) {
        const size_t residueStart = override.offset + override.longOffset;
        if (residueStart < override.offset || override.longLen >= length ||
            residueStart + override.longLen + 1 > length) {
            return false;
        }
        memset(reinterpret_cast<void*>(mapping.start + residueStart), 0,
               override.longLen + 1);
    }

    const uint32_t len = static_cast<uint32_t>(override.value.size());
    const uint32_t oldSerial = __atomic_load_n(&info->serial, __ATOMIC_RELAXED);
    const uint32_t serialDirty = oldSerial | 1U;
    __atomic_store_n(&info->serial, serialDirty, __ATOMIC_RELAXED);
    memset(info->value, 0, sizeof(info->value));
    memcpy(info->value, override.value.c_str(), len + 1);

    __atomic_thread_fence(__ATOMIC_RELEASE);

    constexpr uint32_t kCounterMask = 0x00ffffffU & ~kLongPropertyFlag;
    const uint32_t lenFlags = (len << 24) & 0xff000000U;
    const uint32_t visible = lenFlags | ((serialDirty + 1U) & kCounterMask);
    __atomic_store_n(&info->serial, visible, __ATOMIC_RELAXED);
    syscall(SYS_futex, &info->serial, FUTEX_WAKE, INT_MAX, nullptr, nullptr, 0);

    __atomic_thread_fence(__ATOMIC_RELEASE);
    const uint32_t hidden = lenFlags | ((serialDirty & ~1U) & kCounterMask);
    __atomic_store_n(&info->serial, hidden, __ATOMIC_RELAXED);
    return true;
}

bool verifyMappingsIdentity(const std::vector<Mapping*>& affected) {
    FILE* maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) {
        ALOGE("[PROP-VIRT] cannot reopen /proc/self/maps: %s", strerror(errno));
        return false;
    }

    std::vector<Mapping*> pending = affected;
    bool ok = true;
    char line[512];
    while (!pending.empty() && fgets(line, sizeof(line), maps) != nullptr) {
        MapsLine entry = {};
        if (!parseMapsLine(line, entry)) {
            continue;
        }
        auto it = std::find_if(pending.begin(), pending.end(),
                               [&](const Mapping* m) { return m->start == entry.start; });
        if (it == pending.end()) {
            continue;
        }
        const Mapping& mapping = **it;
        pending.erase(it);

        const char access[4] = {
                (mapping.prot & PROT_READ) != 0 ? 'r' : '-',
                (mapping.prot & PROT_WRITE) != 0 ? 'w' : '-',
                (mapping.prot & PROT_EXEC) != 0 ? 'x' : '-',
                '\0',
        };

        const bool match = entry.end == mapping.end && entry.offset == 0 &&
                           entry.devMajor == mapping.devMajor && entry.devMinor == mapping.devMinor &&
                           entry.inode == mapping.inode && entry.path == mapping.path &&
                           entry.permissions[3] == 'p' &&
                           strncmp(entry.permissions, access, 3) == 0;
        if (!match) {
            ALOGE("[PROP-VIRT] identity check failed for %s", mapping.path.c_str());
            ok = false;
        }
    }
    fclose(maps);

    for (const Mapping* mapping : pending) {
        ALOGE("[PROP-VIRT] VMA at start=%p for %s vanished from /proc/self/maps",
              reinterpret_cast<void*>(mapping->start), mapping->path.c_str());
        ok = false;
    }
    return ok;
}

void cleanup(std::vector<Mapping>& mappings) {
    for (auto& mapping : mappings) {
        if (mapping.fd >= 0) {
            close(mapping.fd);
            mapping.fd = -1;
        }
    }
}

void rollback(std::vector<Mapping>& mappings, bool debug) {
    for (auto& mapping : mappings) {
        if (!mapping.replaced || mapping.fd < 0) continue;
        const int flags = MAP_FIXED | (mapping.permLetter == 's' ? MAP_SHARED : MAP_PRIVATE);
        void* restored = mmap(reinterpret_cast<void*>(mapping.start), mapping.end - mapping.start,
                              mapping.prot, flags, mapping.fd, 0);
        if (debug) {
            ALOGD("[PROP-VIRT] rollback %s %s", mapping.path.c_str(),
                  restored == MAP_FAILED ? "failed" : "ok");
        }
        if (restored != MAP_FAILED) {
            mapping.replaced = false;
        }
    }
}

bool install(State& state) {
    __system_property_foreach(warmupCallback, nullptr);
    if (!parseMappings(state.mappings, state.debug)) {
        return false;
    }
    if (__system_property_foreach(enumerateCallback, &state) != 0) {
        ALOGE("[PROP-VIRT] property enumeration failed");
        return false;
    }
    if (state.overrides.empty()) {
        ALOGE("[PROP-VIRT] no configured property rules matched");
        return false;
    }

    std::vector<Mapping*> affected;
    for (const auto& override : state.overrides) {
        Mapping* mapping = override.mapping;
        if (mapping == nullptr) {
            cleanup(state.mappings);
            return false;
        }
        if (std::find(affected.begin(), affected.end(), mapping) == affected.end()) {
            affected.push_back(mapping);
        }
    }

    for (Mapping* mapping : affected) {
        if (!preflightOpen(*mapping)) {
            cleanup(state.mappings);
            return false;
        }
    }

    for (Mapping* mapping : affected) {
        if (!remapPrivate(*mapping)) {
            rollback(state.mappings, state.debug);
            cleanup(state.mappings);
            return false;
        }
    }

    for (const auto& override : state.overrides) {
        if (override.mapping == nullptr || !applyOverride(*override.mapping, override)) {
            ALOGE("[PROP-VIRT] cannot patch %s", override.name.c_str());
            rollback(state.mappings, state.debug);
            cleanup(state.mappings);
            return false;
        }
    }

    for (Mapping* mapping : affected) {
        if (mprotect(reinterpret_cast<void*>(mapping->start), mapping->end - mapping->start,
                     mapping->prot) != 0) {
            ALOGE("[PROP-VIRT] cannot restore protections on %s: %s", mapping->path.c_str(),
                  strerror(errno));
            rollback(state.mappings, state.debug);
            cleanup(state.mappings);
            return false;
        }
    }

    for (const auto& override : state.overrides) {
        char value[PROP_VALUE_MAX] = {};
        const prop_info* info = __system_property_find(override.name.c_str());
        const int valueLength = __system_property_get(override.name.c_str(), value);
        auto* expected = reinterpret_cast<const InlinePropertyInfo*>(override.mapping->start +
                                                                     override.offset);
        if (reinterpret_cast<const prop_info*>(expected) != info || valueLength < 0 ||
            override.value != value || expected->value != override.value) {
            ALOGE("[PROP-VIRT] verification failed for %s", override.name.c_str());
            rollback(state.mappings, state.debug);
            cleanup(state.mappings);
            return false;
        }
    }
    if (!verifyMappingsIdentity(affected)) {
        rollback(state.mappings, state.debug);
        cleanup(state.mappings);
        return false;
    }

    cleanup(state.mappings);
    if (state.debug) {
        ALOGD("[PROP-VIRT] installed %zu overrides across %zu areas",
              state.overrides.size(), affected.size());
    }
    return true;
}

}  // namespace pif_prop_virt
#endif

jboolean PlayIntegritySpoofService_nativeSpoofProperties(
        JNIEnv* env, jclass /*clazz*/, jobjectArray keysJ, jobjectArray valuesJ, jboolean debugJ) {
#if defined(__BIONIC__)
    if (keysJ == nullptr || valuesJ == nullptr) {
        return JNI_FALSE;
    }
    const jsize count = env->GetArrayLength(keysJ);
    if (count <= 0 || count != env->GetArrayLength(valuesJ)) {
        return JNI_FALSE;
    }

    pif_prop_virt::State state;
    state.debug = (debugJ == JNI_TRUE);

    for (jsize i = 0; i < count; ++i) {
        jstring kStr = static_cast<jstring>(env->GetObjectArrayElement(keysJ, i));
        jstring vStr = static_cast<jstring>(env->GetObjectArrayElement(valuesJ, i));
        if (kStr != nullptr && vStr != nullptr) {
            ScopedUtfChars kUtf(env, kStr);
            ScopedUtfChars vUtf(env, vStr);
            if (kUtf.c_str() != nullptr && vUtf.c_str() != nullptr && kUtf.size() > 0) {
                if (kUtf.c_str()[0] == '*' && kUtf.size() > 1) {
                    state.suffixRules.emplace_back(kUtf.c_str() + 1, vUtf.c_str());
                } else {
                    state.exactMap.emplace(kUtf.c_str(), vUtf.c_str());
                }
            }
        }
        if (kStr != nullptr) env->DeleteLocalRef(kStr);
        if (vStr != nullptr) env->DeleteLocalRef(vStr);
    }

    return pif_prop_virt::install(state) ? JNI_TRUE : JNI_FALSE;
#else
    (void)env;
    (void)keysJ;
    (void)valuesJ;
    (void)debugJ;
    return JNI_FALSE;
#endif
}
}  // namespace

int register_android_os_SystemProperties(JNIEnv *env)
{
    const JNINativeMethod method_table[] = {
        { "native_get",
          "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
          (void*) SystemProperties_getSS },
        { "native_get_int", "(Ljava/lang/String;I)I",
          (void*) SystemProperties_get_integral<jint> },
        { "native_get_long", "(Ljava/lang/String;J)J",
          (void*) SystemProperties_get_integral<jlong> },
        { "native_get_boolean", "(Ljava/lang/String;Z)Z",
          (void*) SystemProperties_get_boolean },
        { "native_find",
          "(Ljava/lang/String;)J",
          (void*) SystemProperties_find },
        { "native_get",
          "(J)Ljava/lang/String;",
          (void*) SystemProperties_getH },
        { "native_get_int", "(JI)I",
          (void*) SystemProperties_get_integralH<jint> },
        { "native_get_long", "(JJ)J",
          (void*) SystemProperties_get_integralH<jlong> },
        { "native_get_boolean", "(JZ)Z",
          (void*) SystemProperties_get_booleanH },
        { "native_set", "(Ljava/lang/String;Ljava/lang/String;)V",
          (void*) SystemProperties_set },
        { "native_add_change_callback", "()V",
          (void*) SystemProperties_add_change_callback },
        { "native_report_sysprop_change", "()V",
          (void*) SystemProperties_report_sysprop_change },
    };
    int res = RegisterMethodsOrDie(env, "android/os/SystemProperties",
                                   method_table, NELEM(method_table));
    const JNINativeMethod pif_methods[] = {
        { "nativeSpoofProperties",
          "([Ljava/lang/String;[Ljava/lang/String;Z)Z",
          (void*) PlayIntegritySpoofService_nativeSpoofProperties },
    };
    jclass pifClazz = env->FindClass("android/security/pif/PlayIntegritySpoofService");
    if (pifClazz != nullptr) {
        env->RegisterNatives(pifClazz, pif_methods, NELEM(pif_methods));
        env->DeleteLocalRef(pifClazz);
    } else {
        env->ExceptionClear();
    }
    return res;
}

}  // namespace android
