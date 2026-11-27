// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/bus.h"
#include "core/cpu_core.h"
#include "core/cpu_pgxp.h"
#include "core/settings.h"

#include <gtest/gtest.h>

#include <vector>

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

namespace {

// The merges cpu_core.cpp does for LWL/LWR (into a register) and SWL/SWR (into memory); they call PGXP with the
// unaligned address and the merged word.
u32 LoadLeftRight(CPU::InstructionOp op, u32 addr, u32 mem_word, u32 reg_old, u32 rt)
{
  const u32 shift = (addr & 3u) * 8u;
  const u32 merged = (op == CPU::InstructionOp::lwl) ?
                       ((reg_old & (0x00FFFFFFu >> shift)) | (mem_word << (24 - shift))) :
                       ((reg_old & (0xFFFFFF00u << (24 - shift))) | (mem_word >> shift));
  CPU::PGXP::CPU_LWx(MakeInstruction(op, rt), addr, merged);
  return merged;
}

u32 StoreLeftRight(CPU::InstructionOp op, u32 addr, u32 mem_word, u32 reg, u32 rt)
{
  const u32 shift = (addr & 3u) * 8u;
  const u32 merged = (op == CPU::InstructionOp::swl) ?
                       ((mem_word & (0xFFFFFF00u << shift)) | (reg >> (24 - shift))) :
                       ((mem_word & (0x00FFFFFFu >> (24 - shift))) | (reg << shift));
  CPU::PGXP::CPU_SWx(MakeInstruction(op, rt), addr, merged);
  return merged;
}

// Stores register rt with SW, as a game would before drawing from it.
void StoreRegister(u32 rt, u32 addr, u32 value)
{
  CPU::PGXP::CPU_SW(MakeInstruction(CPU::InstructionOp::sw, rt), addr, value);
}

constexpr u32 RT = 5;
const u32 A = Pack(10, 20); // at 0x1000: (10.25, 20.5), z 300
const u32 B = Pack(30, 40); // at 0x1004: (30.75, 40.125), z 500

} // namespace

class PGXPUnalignedTest : public PGXPTest
{
protected:
  void SetUp() override
  {
    PGXPTest::SetUp();
    StorePreciseVertex(0x1000, 10.25f, 20.5f, 300.0f);
    StorePreciseVertex(0x1004, 30.75f, 40.125f, 500.0f);
  }
};

TEST_F(PGXPUnalignedTest, LwrLwlAtOffsetTwoKeepsBothHalves)
{
  u32 reg = LoadLeftRight(CPU::InstructionOp::lwr, 0x1002, A, 0, RT);
  reg = LoadLeftRight(CPU::InstructionOp::lwl, 0x1005, B, reg, RT);
  ASSERT_EQ(reg, Pack(20, 30));

  StoreRegister(RT, 0x2000, reg);
  const Found f = Lookup(0x2000, reg);
  EXPECT_FLOAT_EQ(f.x, 20.5f);
  EXPECT_FLOAT_EQ(f.y, 30.75f);
  EXPECT_FALSE(f.z_valid); // Assembled from two vertices: no depth of its own.
}

TEST_F(PGXPUnalignedTest, LwlInLoadDelayKeepsFirstHalf)
{
  // The register held another precise value; LWR's result is still in its load delay when LWL reads it back.
  StorePreciseVertex(0x3000, 1.5f, 2.5f, 10.0f);
  CPU::PGXP::CPU_LW(MakeInstruction(CPU::InstructionOp::lw, RT), 0x3000, Pack(1, 2));

  const u32 delayed = LoadLeftRight(CPU::InstructionOp::lwr, 0x1002, A, Pack(1, 2), RT);
  const u32 reg = LoadLeftRight(CPU::InstructionOp::lwl, 0x1005, B, delayed, RT);

  StoreRegister(RT, 0x2000, reg);
  const Found f = Lookup(0x2000, reg);
  EXPECT_FLOAT_EQ(f.x, 20.5f);
  EXPECT_FLOAT_EQ(f.y, 30.75f);
}

TEST_F(PGXPUnalignedTest, WholeWordMovesKeepZ)
{
  const u32 r1 = LoadLeftRight(CPU::InstructionOp::lwr, 0x1000, A, 0, RT);
  ASSERT_EQ(r1, A);
  StoreRegister(RT, 0x2000, r1);
  Found f = Lookup(0x2000, A);
  EXPECT_FLOAT_EQ(f.x, 10.25f);
  EXPECT_FLOAT_EQ(f.y, 20.5f);
  EXPECT_FLOAT_EQ(f.w, 300.0f / 32768.0f);
  EXPECT_TRUE(f.z_valid);

  const u32 r2 = LoadLeftRight(CPU::InstructionOp::lwl, 0x1007, B, 0, RT);
  ASSERT_EQ(r2, B);
  StoreRegister(RT, 0x2004, r2);
  f = Lookup(0x2004, B);
  EXPECT_FLOAT_EQ(f.x, 30.75f);
  EXPECT_FLOAT_EQ(f.y, 40.125f);
  EXPECT_TRUE(f.z_valid);
}

TEST_F(PGXPUnalignedTest, StraddledHalvesAreNative)
{
  const u32 reg = LoadLeftRight(CPU::InstructionOp::lwr, 0x1001, A, 0, RT);
  StoreRegister(RT, 0x2000, reg);
  const Found f = Lookup(0x2000, reg);
  EXPECT_FLOAT_EQ(f.x, static_cast<float>(static_cast<s16>(reg)));
  EXPECT_FLOAT_EQ(f.y, static_cast<float>(static_cast<s16>(reg >> 16)));
  EXPECT_FALSE(f.z_valid);
}

TEST_F(PGXPUnalignedTest, LoadsLeaveMemoryPrecise)
{
  // Only B's lowest byte moves; B itself is untouched in memory and must stay precise.
  LoadLeftRight(CPU::InstructionOp::lwl, 0x1004, B, 0, RT);
  const Found f = Lookup(0x1004, B);
  EXPECT_FLOAT_EQ(f.x, 30.75f);
  EXPECT_FLOAT_EQ(f.y, 40.125f);
  EXPECT_TRUE(f.z_valid);
}

TEST_F(PGXPUnalignedTest, SwrSwlAtOffsetTwoStoreBothHalves)
{
  StorePreciseVertex(0x3000, 12.5f, 34.25f, 50.0f);
  const u32 reg = Pack(12, 34);
  CPU::PGXP::CPU_LW(MakeInstruction(CPU::InstructionOp::lw, RT), 0x3000, reg);

  const u32 new_a = StoreLeftRight(CPU::InstructionOp::swr, 0x1002, A, reg, RT);
  const u32 new_b = StoreLeftRight(CPU::InstructionOp::swl, 0x1005, B, reg, RT);
  ASSERT_EQ(new_a, Pack(10, 12));
  ASSERT_EQ(new_b, Pack(34, 40));

  Found f = Lookup(0x1000, new_a);
  EXPECT_FLOAT_EQ(f.x, 10.25f);
  EXPECT_FLOAT_EQ(f.y, 12.5f);
  EXPECT_FALSE(f.z_valid);

  f = Lookup(0x1004, new_b);
  EXPECT_FLOAT_EQ(f.x, 34.25f);
  EXPECT_FLOAT_EQ(f.y, 40.125f);
  EXPECT_FALSE(f.z_valid);
}

TEST_F(PGXPUnalignedTest, MismatchedSourceIsNative)
{
  // A's high half changed behind PGXP's back (a DMA, say): the moved half no longer matches the shadow.
  const u32 changed_a = Pack(10, 99);
  const u32 reg = LoadLeftRight(CPU::InstructionOp::lwr, 0x1002, changed_a, 0, RT);
  StoreRegister(RT, 0x2000, reg);
  const Found f = Lookup(0x2000, reg);
  EXPECT_FLOAT_EQ(f.x, 99.0f);
}

namespace {
struct LineVertex
{
  u32 addr;
  u32 value;
};

// Looks up a line's vertices as the GPU frontend does, with drawing offset 0.
bool LookupLine(const std::vector<LineVertex>& verts, bool with_addresses, std::vector<float>* out)
{
  std::vector<u32> addrs, values;
  std::vector<s32> native;
  for (const LineVertex& v : verts)
  {
    addrs.push_back(v.addr);
    values.push_back(v.value);
    native.push_back(static_cast<s16>(v.value));
    native.push_back(static_cast<s16>(v.value >> 16));
  }
  out->assign(verts.size() * 2, -1.0f);
  return CPU::PGXP::GetPreciseLineVertices(static_cast<u32>(verts.size()), with_addresses ? addrs.data() : nullptr,
                                           values.data(), native.data(), 0, 0, out->data());
}
} // namespace

TEST_F(PGXPUnalignedTest, LineWithPreciseEndsIsPrecise)
{
  std::vector<float> xy;
  EXPECT_TRUE(LookupLine({{0x1000, A}, {0x1004, B}}, true, &xy));
  EXPECT_EQ(xy, (std::vector<float>{10.25f, 20.5f, 30.75f, 40.125f}));
}

TEST_F(PGXPUnalignedTest, LineWithOneGameBuiltEndIsNative)
{
  // X and Y precise but no Z: put together by the game, not projected by the GTE.
  u32 reg = LoadLeftRight(CPU::InstructionOp::lwr, 0x1002, A, 0, RT);
  reg = LoadLeftRight(CPU::InstructionOp::lwl, 0x1005, B, reg, RT);
  StoreRegister(RT, 0x2000, reg);

  std::vector<float> xy;
  EXPECT_FALSE(LookupLine({{0x1000, A}, {0x2000, reg}}, true, &xy));
  EXPECT_EQ(xy, (std::vector<float>{10.0f, 20.0f, 20.0f, 30.0f}));
}

TEST_F(PGXPUnalignedTest, PolylineIsAllOrNothing)
{
  StorePreciseVertex(0x1008, 50.5f, 60.5f, 100.0f);
  std::vector<float> xy;
  EXPECT_FALSE(LookupLine({{0x1000, A}, {0x1004, B}, {0x1008, Pack(50, 60)}, {0x3000, Pack(70, 80)}}, true, &xy));
  EXPECT_EQ(xy, (std::vector<float>{10.0f, 20.0f, 30.0f, 40.0f, 50.0f, 60.0f, 70.0f, 80.0f}));

  EXPECT_TRUE(LookupLine({{0x1000, A}, {0x1004, B}, {0x1008, Pack(50, 60)}}, true, &xy));
  EXPECT_EQ(xy, (std::vector<float>{10.25f, 20.5f, 30.75f, 40.125f, 50.5f, 60.5f}));
}

TEST_F(PGXPUnalignedTest, PolylineWithMissingAddressesIsNative)
{
  // A state loaded in the middle of a polyline: the words already buffered have no addresses.
  std::vector<float> xy;
  EXPECT_FALSE(LookupLine({{0x1000, A}, {0x1004, B}}, false, &xy));
  EXPECT_EQ(xy, (std::vector<float>{10.0f, 20.0f, 30.0f, 40.0f}));
}

// Rewind and runahead re-run frames from memory states, which carry PGXP's values along.

TEST_F(PGXPUnalignedTest, MemoryStateRestoresShadow)
{
  CPU::g_state.pgxp_gpr[4] = {1.5f, 2.5f, 3.5f, Pack(1, 2), 7};
  std::vector<u8> state(CPU::PGXP::GetMemoryStateSize());
  CPU::PGXP::SaveMemoryState(state);

  StorePreciseVertex(0x1000, 10.75f, 20.25f, 900.0f); // the same native value, different precision
  CPU::g_state.pgxp_gpr[4] = {};

  CPU::PGXP::LoadMemoryState(state);
  const Found f = Lookup(0x1000, A);
  EXPECT_FLOAT_EQ(f.x, 10.25f);
  EXPECT_FLOAT_EQ(f.y, 20.5f);
  EXPECT_FLOAT_EQ(f.w, 300.0f / 32768.0f);
  EXPECT_FLOAT_EQ(CPU::g_state.pgxp_gpr[4].x, 1.5f);
  EXPECT_EQ(CPU::g_state.pgxp_gpr[4].flags, 7u);
}

TEST_F(PGXPUnalignedTest, StateWithoutShadowResets)
{
  // Saved while PGXP was off: nothing to restore, so nothing stale is left either.
  CPU::PGXP::LoadMemoryState({});
  const Found f = Lookup(0x1000, A);
  EXPECT_FLOAT_EQ(f.x, 10.0f);
  EXPECT_FLOAT_EQ(f.y, 20.0f);
}

TEST_F(PGXPUnalignedTest, ShadowCopyFollowsRamSize)
{
  const size_t small = CPU::PGXP::GetMemoryStateSize();
  Bus::g_ram_mask = Bus::RAM_8MB_MASK;
  EXPECT_EQ(CPU::PGXP::GetMemoryStateSize() - small, (Bus::RAM_8MB_SIZE - Bus::RAM_2MB_SIZE) / 4 * sizeof(CPU::PGXPValue));

  StorePreciseVertex(0x700000, 5.5f, 6.5f, 40.0f);
  std::vector<u8> state(CPU::PGXP::GetMemoryStateSize());
  CPU::PGXP::SaveMemoryState(state);
  StorePreciseVertex(0x700000, 5.25f, 6.25f, 40.0f);
  CPU::PGXP::LoadMemoryState(state);

  const Found f = Lookup(0x700000, Pack(5, 6));
  EXPECT_FLOAT_EQ(f.x, 5.5f);
  EXPECT_FLOAT_EQ(f.y, 6.5f);
}

TEST_F(PGXPUnalignedTest, ImpreciseHalfKeepsItsNativeValue)
{
  // A precise register (10.25, 20.5); LWR at offset 2 from an untracked word brings its high half (30) into the low
  // half, imprecise. CPU mode's SLL by 16 then moves that half up as Y, valid: it must be 30, not a stray 0.
  CPU::PGXP::CPU_LW(MakeInstruction(CPU::InstructionOp::lw, RT), 0x1000, A);
  const u32 untracked = Pack(5, 30);
  const u32 reg = LoadLeftRight(CPU::InstructionOp::lwr, 0x3002, untracked, A, RT);
  ASSERT_EQ(reg, Pack(30, 20));

  CPU::Instruction sll;
  sll.bits = (static_cast<u32>(RT) << 16) | (6u << 11) | (16u << 6); // sll $6, $5, 16
  CPU::PGXP::CPU_SLL(sll, reg);
  const u32 shifted = reg << 16;
  StoreRegister(6, 0x2000, shifted);

  const Found f = Lookup(0x2000, shifted);
  EXPECT_FLOAT_EQ(f.y, 30.0f);
}
