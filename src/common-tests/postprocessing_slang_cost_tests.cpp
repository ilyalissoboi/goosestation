// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/postprocessing_slang_cost.h"

#include "common/file_system.h"
#include "common/path.h"
#include "common/timer.h"

#include <gtest/gtest.h>

#include <atomic>
#include <clocale>
#include <cstdlib>
#include <filesystem>
#include <string>

using namespace PostProcessing::Slang;

namespace {

// A fresh folder of hand-written presets, deleted when the test ends.
class PresetFolder
{
public:
  PresetFolder()
  {
    static std::atomic<int> s_counter{0};
    std::error_code ec;
    m_path = Path::ToNativePath((std::filesystem::temp_directory_path(ec) /
                                 ("common-tests-slang-cost-" + std::to_string(std::rand()) + "-" +
                                  std::to_string(s_counter.fetch_add(1))))
                                  .generic_string());
    FileSystem::CreateDirectory(m_path.c_str(), true);
  }
  ~PresetFolder() { FileSystem::RecursiveDeleteDirectory(m_path.c_str()); }

  std::string Write(const std::string& relative, std::string_view contents) const
  {
    const std::string path = Path::ToNativePath(Path::Combine(m_path, relative));
    std::error_code ec;
    std::filesystem::create_directories(std::filesystem::path(path).parent_path(), ec);
    FileSystem::WriteStringToFile(path.c_str(), contents);
    return path;
  }

private:
  std::string m_path;
};

constexpr float SCREEN = 1920.0f * 1080.0f;

} // namespace

TEST(SlangCost, OnePassAtTheViewportIsLight)
{
  const PresetFolder f;
  const std::optional<CostEstimate> e = EstimateCost(f.Write("a.slangp", "shaders = 1\nscale_type0 = viewport\n"));
  ASSERT_TRUE(e.has_value());
  EXPECT_EQ(e->passes, 1u);
  EXPECT_FLOAT_EQ(e->screens, 1.0f);
  EXPECT_EQ(e->cost, Cost::Light);
}

TEST(SlangCost, SourceScaledPassesFollowThePreviousPass)
{
  const PresetFolder f;
  const std::optional<CostEstimate> e = EstimateCost(
    f.Write("a.slangp", "shaders = \"3\"\nscale_type0 = source\nscale0 = 2.0\nscale_type1 = \"source\"\n"
                        "scale1 = \"2\"\n// a comment\n# another\n"));
  ASSERT_TRUE(e.has_value());
  EXPECT_EQ(e->passes, 3u);
  EXPECT_NEAR(e->screens, (640.0f * 480.0f + 1280.0f * 960.0f) / SCREEN + 1.0f, 1e-4f);
}

TEST(SlangCost, AbsoluteAndPerAxisScales)
{
  const PresetFolder f;
  const std::optional<CostEstimate> e =
    EstimateCost(f.Write("a.slangp", "shaders = 2\nscale_type_x0 = absolute\nscale_x0 = 800\n"
                                     "scale_type_y0 = viewport\nscale_y0 = 0.5\nscale_type1 = viewport\n"));
  ASSERT_TRUE(e.has_value());
  EXPECT_NEAR(e->screens, (800.0f * 540.0f) / SCREEN + 1.0f, 1e-4f);
}

TEST(SlangCost, ReferencesAreFollowedAndTheReferrerWins)
{
  const PresetFolder f;
  f.Write("base/b.slangp", "shaders = 2\nscale_type0 = source\nscale0 = 1\nscale_type1 = viewport\n");
  const std::optional<CostEstimate> e =
    EstimateCost(f.Write("a.slangp", "#reference \"base/b.slangp\"\nscale0 = 3\n"));
  ASSERT_TRUE(e.has_value());
  EXPECT_EQ(e->passes, 2u);
  EXPECT_NEAR(e->screens, (960.0f * 720.0f) / SCREEN + 1.0f, 1e-4f);
}

TEST(SlangCost, ACommentedReferenceIsIgnored)
{
  const PresetFolder f;
  f.Write("b.slangp", "shaders = 30\n");
  const std::optional<CostEstimate> e =
    EstimateCost(f.Write("a.slangp", "//#reference \"b.slangp\"\nshaders = 1\nscale_type0 = viewport\n"));
  ASSERT_TRUE(e.has_value());
  EXPECT_EQ(e->passes, 1u);
}

TEST(SlangCost, NumbersReadTheSameInAnyLocale)
{
  // Qt sets the C locale from the environment on Linux: a comma-decimal one mustn't change how "0.5" reads.
  const std::string old = std::setlocale(LC_NUMERIC, nullptr);
  if (!std::setlocale(LC_NUMERIC, "de_DE.UTF-8") && !std::setlocale(LC_NUMERIC, "de_DE"))
    GTEST_SKIP() << "no de_DE locale here";
  const PresetFolder f;
  const std::optional<CostEstimate> e = EstimateCost(
    f.Write("a.slangp", "shaders = 2\nscale_type0 = source\nscale0 = 0.5\nscale_type1 = viewport\n"));
  std::setlocale(LC_NUMERIC, old.c_str());
  ASSERT_TRUE(e.has_value());
  EXPECT_NEAR(e->screens, (160.0f * 120.0f) / SCREEN + 1.0f, 1e-4f);
}

TEST(SlangCost, ThresholdsFromTheThorMeasurements)
{
  EXPECT_EQ(CostOf(1, 1.0f), Cost::Light);     // uborder
  EXPECT_EQ(CostOf(10, 2.3f), Cost::Light);    // Mega Bezel Potato
  EXPECT_EQ(CostOf(17, 2.1f), Cost::Light);    // koko-aio
  EXPECT_EQ(CostOf(12, 5.3f), Cost::Heavy);    // crt-royale
  EXPECT_EQ(CostOf(30, 9.0f), Cost::VeryHeavy); // Mega Bezel standard
  EXPECT_EQ(CostOf(48, 13.4f), Cost::VeryHeavy); // Mega Bezel Smooth-Advanced
}

TEST(SlangCost, BrokenPresetsStillGetALabelOrNone)
{
  const PresetFolder f;
  EXPECT_TRUE(EstimateCost(f.Write("missing-ref.slangp", "#reference \"nope.slangp\"\nshaders = 1\n")).has_value());
  f.Write("loop-b.slangp", "#reference \"loop-a.slangp\"\nshaders = 2\n");
  EXPECT_TRUE(EstimateCost(f.Write("loop-a.slangp", "#reference \"loop-b.slangp\"\n")).has_value());
  const std::optional<CostEstimate> bad = EstimateCost(f.Write("bad.slangp", "shaders = x\n"));
  ASSERT_TRUE(bad.has_value());
  EXPECT_EQ(bad->passes, 0u);
  EXPECT_TRUE(EstimateCost(f.Write("neg.slangp", "shaders = 1\nscale0 = -2\n")).has_value());
  const std::optional<CostEstimate> huge =
    EstimateCost(f.Write("huge.slangp", "shaders = 100000\nscale_type0 = absolute\nscale0 = 1e9\n"));
  ASSERT_TRUE(huge.has_value());
  EXPECT_EQ(huge->cost, Cost::VeryHeavy);
  EXPECT_LE(huge->passes, 256u);
  EXPECT_FALSE(EstimateCost(Path::Combine(f.Write("x.slangp", ""), "../none.slangp")).has_value());
}

TEST(SlangCost, LabelsAreWords)
{
  EXPECT_STREQ(GetCostLabel(Cost::Light), "light");
  EXPECT_STREQ(GetCostLabel(Cost::Heavy), "heavy");
  EXPECT_STREQ(GetCostLabel(Cost::VeryHeavy), "very heavy");
}

TEST(SlangCost, ThePacksBezelsGetTheDesignsLabels)
{
  // GOOSESTATION_SLANG_PACK: libretro's slang-shaders checked out or unzipped; skipped otherwise.
  const char* pack = std::getenv("GOOSESTATION_SLANG_PACK");
  if (!pack || !*pack)
    GTEST_SKIP() << "GOOSESTATION_SLANG_PACK isn't set";

  const auto cost = [pack](const char* name) {
    const std::optional<CostEstimate> e = EstimateCost(Path::ToNativePath(Path::Combine(pack, name)));
    return e.has_value() ? e->cost : Cost::Light;
  };
  EXPECT_EQ(cost("bezel/Mega_Bezel/Presets/MBZ__5__POTATO.slangp"), Cost::Light);
  EXPECT_EQ(cost("bezel/uborder/uborder-bezel-reflections.slangp"), Cost::Light);
  EXPECT_EQ(cost("bezel/Mega_Bezel/Presets/MBZ__3__STD.slangp"), Cost::VeryHeavy);
  EXPECT_EQ(cost("bezel/Mega_Bezel/Presets/MBZ__0__SMOOTH-ADV.slangp"), Cost::VeryHeavy);

  // Every preset in the pack, quickly enough for a browser to list.
  FileSystem::FindResultsArray results;
  FileSystem::FindFiles(pack, "*.slangp", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_RECURSIVE, &results);
  Common::Timer timer;
  u32 labelled = 0;
  for (const FILESYSTEM_FIND_DATA& fd : results)
    labelled += EstimateCost(fd.FileName).has_value();
  const double seconds = timer.GetTimeSeconds();
  std::printf("labelled %u of %zu presets in %.2f s\n", labelled, results.size(), seconds);
  EXPECT_EQ(labelled, results.size());
  EXPECT_LT(seconds, 5.0);
}

TEST(SlangCost, GetCostForNameCachesByPath)
{
  const PresetFolder f;
  const std::string path = f.Write("c.slangp", "shaders = 1\nscale_type0 = viewport\n");
  EXPECT_EQ(GetCostForPath(path), std::optional<Cost>(Cost::Light));
  FileSystem::DeleteFile(path.c_str());
  EXPECT_EQ(GetCostForPath(path), std::optional<Cost>(Cost::Light));
  ClearCostCache();
  EXPECT_EQ(GetCostForPath(path), std::nullopt);
}
