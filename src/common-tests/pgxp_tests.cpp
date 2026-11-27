// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/bus.h"
#include "core/cpu_core.h"
#include "core/cpu_pgxp.h"
#include "core/settings.h"

#include <gtest/gtest.h>

namespace {

u32 Pack(s16 x, s16 y)
{
  return static_cast<u32>(static_cast<u16>(x)) | (static_cast<u32>(static_cast<u16>(y)) << 16);
}

CPU::Instruction MakeInstruction(CPU::InstructionOp op, u32 rt, u32 rs = 0)
{
  CPU::Instruction instr;
  instr.bits = (static_cast<u32>(op) << 26) | (rs << 21) | (rt << 16);
  return instr;
}

class PGXPTest : public ::testing::Test
{
protected:
  struct Found
  {
    float x, y, w;
    bool z_valid;
  };

  void SetUp() override
  {
    m_saved_ram_mask = Bus::g_ram_mask;
    m_saved_settings = g_settings;
    Bus::g_ram_mask = Bus::RAM_2MB_MASK;
    g_settings.gpu_pgxp_enable = true;
    g_settings.gpu_pgxp_vertex_cache = true;
    g_settings.gpu_pgxp_tolerance = -1.0f;
    CPU::PGXP::Initialize();
  }

  void TearDown() override
  {
    CPU::PGXP::Shutdown();
    g_settings = m_saved_settings;
    Bus::g_ram_mask = m_saved_ram_mask;
  }

  /// A vertex as the GTE makes it (RTPS), stored to memory with SWC2 of SXY2.
  static void StorePreciseVertex(u32 addr, float x, float y, float z)
  {
    const u32 native = Pack(static_cast<s16>(x), static_cast<s16>(y));
    CPU::PGXP::GTE_RTPS(x, y, z, native);
    CPU::PGXP::CPU_SWC2(MakeInstruction(CPU::InstructionOp::swc2, 14), addr, native);
  }

  /// What the GPU would draw for the vertex at addr, with drawing offset 0.
  static Found Lookup(u32 addr, u32 value)
  {
    Found f;
    f.z_valid = CPU::PGXP::GetPreciseVertex(addr, value, static_cast<s16>(value), static_cast<s16>(value >> 16), 0, 0,
                                            &f.x, &f.y, &f.w);
    return f;
  }

private:
  u32 m_saved_ram_mask = 0;
  Settings m_saved_settings;
};

} // namespace

TEST_F(PGXPTest, StoreThenLookupIsPrecise)
{
  StorePreciseVertex(0x1000, 10.25f, 20.5f, 300.0f);
  const Found f = Lookup(0x1000, Pack(10, 20));
  EXPECT_FLOAT_EQ(f.x, 10.25f);
  EXPECT_FLOAT_EQ(f.y, 20.5f);
  EXPECT_FLOAT_EQ(f.w, 300.0f / 32768.0f);
  EXPECT_TRUE(f.z_valid);
}

TEST_F(PGXPTest, VertexCacheFindsBottomRows)
{
  CPU::PGXP::GTE_RTPS(5.25f, 1020.5f, 100.0f, Pack(5, 1020));

  // Nothing in the memory shadow at this address, so the lookup falls through to the vertex cache.
  const Found f = Lookup(0x3000, Pack(5, 1020));
  EXPECT_FLOAT_EQ(f.x, 5.25f);
  EXPECT_FLOAT_EQ(f.y, 1020.5f);
}
