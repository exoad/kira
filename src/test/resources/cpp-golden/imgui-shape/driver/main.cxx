// imgui-shape: an extern module over an ImGui-like header. The generated
// src/ui/imgui.kira.hxx is checks, not declarations: including it proves every
// Kira signature still matches imgui.h (a default argument, an overloaded name,
// an opaque handle's methods under their C++ names, a value struct's size, the
// flag constants). Then this driver makes the calls the way the emitter spells
// them (design 7.2: kira::ffi::in around a Str, kira::ffi::out around a mut) and
// checks that the fakes saw what ImGui would.
#include "../expected/src/ui/imgui.kira.hxx"

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>

namespace
{
  int checks = 0;
  int failures = 0;

  void check(bool ok, const char* what)
  {
      ++checks;
      std::printf("  %s  %s\n", ok ? "ok  " : "FAIL", what);
      if(!ok)
      {
          ++failures;
      }
  }

  // What the fakes recorded.
  std::string lastLabel;
  ImVec2 lastSize(-1.0f, -1.0f);
  std::string lastFormat;
  int lastFlags = -1;
  int checkboxOverload = 0;
  ImDrawList drawList;
}

namespace ImGui
{
  bool Button(const char* label, const ImVec2& size)
  {
      lastLabel = label;
      lastSize = size;
      return true;
  }

  bool SliderFloat(const char* label, float* v, float v_min, float v_max, const char* format, int flags)
  {
      lastLabel = label;
      lastFormat = format;
      lastFlags = flags;
      *v = (v_min + v_max) / 2.0f;
      return true;
  }

  bool Checkbox(const char* label, bool* v)
  {
      lastLabel = label;
      checkboxOverload = 1;
      *v = !*v;
      return true;
  }

  bool Checkbox(const char* label, int* flags, int flags_value)
  {
      lastLabel = label;
      checkboxOverload = 2;
      *flags |= flags_value;
      return true;
  }

  void Text(const char* fmt, ...)
  {
      va_list args;
      va_start(args, fmt);
      std::vprintf(fmt, args);
      va_end(args);
  }

  ImDrawList* GetWindowDrawList()
  {
      return &drawList;
  }
}

void ImDrawList::AddLine(const ImVec2& p1, const ImVec2& p2, ImU32 col, float thickness)
{
    ++lines;
    lastFrom = p1;
    lastTo = p2;
    lastColor = col;
    lastThickness = thickness;
}

void ImDrawList::AddRectFilled(const ImVec2& p_min, const ImVec2& p_max, ImU32 col, float)
{
    ++rects;
    lastFrom = p_min;
    lastTo = p_max;
    lastColor = col;
}

int ImDrawList::VtxCount() const
{
    return lines * 2 + rects * 4;
}

int main()
{
    std::printf("\nimgui-shape - Kira's extern view of an ImGui-like header\n\n");

    const kira::Str label = "throttle";
    check(ImGui::Button(kira::ffi::in(label)) && lastLabel == "throttle" && lastSize.x == 0.0f && lastSize.y == 0.0f,
          "Button(in(label)) took ImGui's default size");
    const ImVec2 size(40.0f, 20.0f);
    check(ImGui::Button(kira::ffi::in(label), size) && lastSize.x == 40.0f && lastSize.y == 20.0f,
          "Button(in(label), size) passed the Vec2");

    float v = 0.0f;
    check(ImGui::SliderFloat(kira::ffi::in(label), kira::ffi::out(v), 0.0f, 1.0f) && v == 0.5f,
          "SliderFloat(in(label), out(v), lo, hi) wrote v through float*");
    check(lastFormat == "%.3f" && lastFlags == 0, "and ImGui's default format and flags filled the rest");
    check(ImGui::SliderFloat(kira::ffi::in(label), kira::ffi::out(v), 0.0f, 2.0f, "%.1f", 4) && lastFormat == "%.1f" && lastFlags == 4,
          "SliderFloat with a CStr format and flags passed them");

    bool on = false;
    check(ImGui::Checkbox(kira::ffi::in(label), kira::ffi::out(on)) && on && checkboxOverload == 1,
          "Checkbox(in(label), out(bool)) picked the bool* overload");
    int flags = ImGuiWindowFlags_None;
    check(ImGui::Checkbox(kira::ffi::in(label), kira::ffi::out(flags), ImGuiWindowFlags_NoResize) && flags == 2 && checkboxOverload == 2,
          "Checkbox(in(label), out(int), value) picked the int* overload");

    ImDrawList* dl = ImGui::GetWindowDrawList();
    const ImVec2 a(1.0f, 2.0f);
    const ImVec2 b(3.0f, 4.0f);
    dl->AddLine(a, b, 0xFF00FF00u);
    check(dl->lines == 1 && dl->lastFrom.x == 1.0f && dl->lastTo.y == 4.0f && dl->lastColor == 0xFF00FF00u && dl->lastThickness == 1.0f,
          "dl->AddLine(a, b, color) reached ImDrawList::AddLine with its default thickness");
    dl->AddRectFilled(a, b, 7u);
    check(dl->VtxCount() == 6, "dl->VtxCount() is const and counts both");

    check(sizeof(imgui::ffi_::Vec2) == sizeof(ImVec2), "Vec2's layout twin has ImVec2's size");
    check(::ImGuiWindowFlags_NoTitleBar == 1 && ::ImGuiWindowFlags_None == 0, "the flag constants read by their C++ names");

    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures;
}
