/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include "androidfw/ResourceTypes.h"

#include "androidfw/StringPool.h"
#include "androidfw/Util.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"

using ::testing::Eq;

namespace android {

TEST(ResourceTypesTest, ResStringPool_PotentialOverflowFindingStyles) {
  using namespace android;  // For NO_ERROR on Windows.

  // Create proper ResStringPool with 1 style
  StringPool pool;
  pool.MakeRef(StyleString{{"foo"}, {Span{{"b"}, 0, 1}}});

  NoOpDiagnostics diag;
  BigBuffer buffer(1024);
  StringPool::FlattenUtf8(&buffer, pool, &diag);

  ResStringPool test;
  std::unique_ptr<uint8_t[]> data = android::util::Copy(buffer);
  ASSERT_THAT(test.setTo(data.get(), buffer.size()), Eq(NO_ERROR));

  // Change style count to cause potential overflow when finding styles
  ResStringPool_header* header =
      const_cast<ResStringPool_header*>(test.data().convert<ResStringPool_header>().unsafe_ptr());
  header->styleCount = util::HostToDevice32(UINT32_MAX);

  ResStringPool modified;
  ASSERT_THAT(modified.setTo(test.data(), test.bytes()), Eq(BAD_TYPE));
}

TEST(ResourceTypesTest, ResStringPool_HeaderStyleCountExceedsStyleOffsetCount) {
  using namespace android;  // For NO_ERROR on Windows.

  // Create proper ResStringPool with 1 style
  StringPool pool;
  pool.MakeRef(StyleString{{"foo"}, {Span{{"b"}, 0, 1}}});

  NoOpDiagnostics diag;
  BigBuffer buffer(1024);
  StringPool::FlattenUtf8(&buffer, pool, &diag);

  ResStringPool test;
  std::unique_ptr<uint8_t[]> data = android::util::Copy(buffer);
  ASSERT_THAT(test.setTo(data.get(), buffer.size()), Eq(NO_ERROR));

  // Change style count to exceed the actual style count (which should be 1)
  ResStringPool_header* header =
      const_cast<ResStringPool_header*>(test.data().convert<ResStringPool_header>().unsafe_ptr());
  header->styleCount = 2;

  ResStringPool modified;
  ASSERT_THAT(modified.setTo(test.data(), test.bytes()), Eq(BAD_TYPE));
}

}  // namespace android
