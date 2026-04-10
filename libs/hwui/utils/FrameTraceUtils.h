/*
 * Copyright 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <cutils/compiler.h>
#include <cutils/trace.h>
#include <gui/TraceUtils.h>
#include <utils/Trace.h>

#include "Properties.h"

namespace android {
namespace uirenderer {

class ConditionalTraceEnder {
public:
    inline ConditionalTraceEnder(bool active) : mActive(active) {}
    inline ~ConditionalTraceEnder() {
        if (mActive) ATRACE_END();
    }

private:
    bool mActive;
};

}  // namespace uirenderer
}  // namespace android

#define HWUI_FRAME_ATRACE_CALL() \
    ::android::ScopedTrace PASTE(___tracer, __LINE__)(                  \
        CC_UNLIKELY(::android::uirenderer::Properties::traceEachFrame)  \
            ? ATRACE_TAG : 0,                                           \
        __FUNCTION__)

#define HWUI_FRAME_ATRACE_NAME(name) \
    ::android::ScopedTrace PASTE(___tracer, __LINE__)(                  \
        CC_UNLIKELY(::android::uirenderer::Properties::traceEachFrame)  \
            ? ATRACE_TAG : 0,                                           \
        name)

#define HWUI_FRAME_ATRACE_FORMAT(fmt, ...)                                                    \
    ::android::uirenderer::ConditionalTraceEnder PASTE(___tracer, __LINE__)(                  \
        CC_UNLIKELY(::android::uirenderer::Properties::traceEachFrame) &&                     \
            CC_UNLIKELY(ATRACE_ENABLED()) &&                                                  \
                (::android::TraceUtils::atraceFormatBegin(fmt, ##__VA_ARGS__), true))
