// A slice of Dear ImGui's imgui.h for the imgui-shape golden: the shapes Kira's
// extern declarations must survive, under ImGui's own names. A value struct with
// constructors, an opaque draw list, a default argument, an overloaded name,
// varargs (which Kira never declares) and an unscoped enum of flags. main.cxx
// defines them, recording every call.
#pragma once

#include <cstddef>
#include <cstdint>

struct ImVec2
{
    float x = 0.0f;
    float y = 0.0f;

    constexpr ImVec2() = default;
    constexpr ImVec2(float x_, float y_) : x(x_), y(y_)
    {
    }
};

typedef unsigned int ImU32;
typedef int ImGuiWindowFlags;

enum ImGuiWindowFlags_
{
    ImGuiWindowFlags_None = 0,
    ImGuiWindowFlags_NoTitleBar = 1 << 0,
    ImGuiWindowFlags_NoResize = 1 << 1,
};

struct ImDrawList
{
    void AddLine(const ImVec2& p1, const ImVec2& p2, ImU32 col, float thickness = 1.0f);
    void AddRectFilled(const ImVec2& p_min, const ImVec2& p_max, ImU32 col, float rounding = 0.0f);
    [[nodiscard]] int VtxCount() const;

    int lines = 0;
    int rects = 0;
    float lastThickness = -1.0f;
    ImVec2 lastFrom;
    ImVec2 lastTo;
    ImU32 lastColor = 0;
};

namespace ImGui
{
  bool Button(const char* label, const ImVec2& size = ImVec2());
  bool SliderFloat(const char* label, float* v, float v_min, float v_max, const char* format = "%.3f", int flags = 0);
  bool Checkbox(const char* label, bool* v);
  bool Checkbox(const char* label, int* flags, int flags_value);
  bool InputText(const char* label, char* buf, std::size_t buf_size, int flags = 0);
  void Text(const char* fmt, ...);
  ImDrawList* GetWindowDrawList();
}
