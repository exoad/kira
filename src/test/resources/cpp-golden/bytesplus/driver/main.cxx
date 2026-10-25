// bytesplus: kira:bytes' findBytes, crc16Xmodem and fromHex in a program with its own main (bytesplus.kira.cxx,
// through kira::rt::runMain), its header included here on its own; the first two are constexpr, freestanding too.
#include "../expected/src/app/bytesplus.kira.hxx"

#include <array>

constexpr std::array<std::uint8_t, 9> digits{'1', '2', '3', '4', '5', '6', '7', '8', '9'};
constexpr std::array<std::uint8_t, 2> fiveSix{'5', '6'};
static_assert(kira::crc16Xmodem(digits, 0) == 0x31C3, "crc16Xmodem is constexpr");
static_assert(kira::findBytes(digits, fiveSix, 0) == 4 && kira::findBytes(digits, fiveSix, 5) == -1, "findBytes is constexpr");
