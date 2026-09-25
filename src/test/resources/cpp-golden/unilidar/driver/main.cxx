// unilidar: the L2's wire protocol against bytes worked out somewhere else. The
// golden packets are bibo's test_unilidar's, which came from a Python
// reimplementation run beside the SDK's own crc32 loop.
#include "../expected/src/pilot/unilidar.kira.hxx"

#include <cstdio>

namespace
{
  int checks = 0;
  int failures = 0;

  void check(bool ok, const char* what)
  {
      ++checks;
      if(ok)
      {
          std::printf("  ok    %s\n", what);
      }
      else
      {
          std::printf("  FAIL  %s\n", what);
          ++failures;
      }
  }

  // 55 AA 05 0A | 64 00 00 00 | 20 00 00 00 | 02 00 00 00 | 01 00 00 00 | crc | 0 | 0 0 | 00 FF
  constexpr std::array<std::uint8_t, 32> GOLDEN_STANDBY = {
      0x55, 0xAA, 0x05, 0x0A, 0x64, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00,
      0x01, 0x00, 0x00, 0x00, 0x71, 0xBF, 0xBB, 0x9F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF,
  };
  constexpr std::array<std::uint8_t, 32> GOLDEN_START = {
      0x55, 0xAA, 0x05, 0x0A, 0x64, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00, 0x14, 0xD8, 0x07, 0x27, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF,
  };
  // The OTHER family: type 2000 and cmd 4, as the SDK's sendRequestOfLidarVersion builds it.
  constexpr std::array<std::uint8_t, 32> GOLDEN_VERSION = {
      0x55, 0xAA, 0x05, 0x0A, 0xD0, 0x07, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00, 0x93, 0xD1, 0x68, 0xE1, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF,
  };

  // THE BUILD REFUSES A WRONG PACKET: every function is constexpr, so the bytes
  // are checked before anything runs.
  static_assert(unilidar::standby() == GOLDEN_STANDBY, "standby is not the packet the SDK sends");
  static_assert(unilidar::start() == GOLDEN_START, "start is not the packet the SDK sends");
  static_assert(unilidar::versionRequest() == GOLDEN_VERSION, "the version request is not the SDK's");

  // The standard CRC-32 check value, from the definition of the algorithm:
  // "123456789" is 0xCBF43926.
  constexpr std::array<std::uint8_t, 9> CHECK_TEXT = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
  static_assert(unilidar::crc32(CHECK_TEXT) == 0xCBF43926u);

  // One datagram: standby, then the version request, then three stray bytes.
  constexpr std::array<std::uint8_t, 67> datagram()
  {
      std::array<std::uint8_t, 67> d{};
      const unilidar::Frame a = unilidar::standby();
      const unilidar::Frame b = unilidar::versionRequest();
      for(kira::Size i = 0; i < 32; ++i)
      {
          d[i] = a[i];
          d[32 + i] = b[i];
      }
      d[64] = 0x55;
      d[65] = 0xAA;
      d[66] = 0x05;
      return d;
  }
  constexpr std::array<std::uint8_t, 67> DATAGRAM = datagram();

  // The walk, done by the compiler: a reader that took the datagram as one
  // packet does not build. The lambda is a template argument, not a std::function.
  static_assert(unilidar::eachPacket(DATAGRAM, [](kira::View<std::uint8_t>) {}).packets == 2,
                "the datagram is two packets");
  static_assert(unilidar::eachPacket(DATAGRAM, [](kira::View<std::uint8_t>) {}).leftover == 3,
                "and three bytes that are not one");
  static_assert([] {
      unilidar::Header h;
      return unilidar::readHeader(GOLDEN_STANDBY, h) && h.type == unilidar::TYPE_USER_CMD && h.size == 32u;
  }(), "readHeader reads the standby frame");
  static_assert(kira::unwrap(unilidar::autoStandbyFrame(1))[16] == 1 && !unilidar::autoStandbyFrame(2).has_value());
}

int main()
{
    std::printf("\nunilidar - the L2's wire protocol\n\n");
    const unilidar::Frame s = unilidar::standby();
    check(s == GOLDEN_STANDBY && unilidar::start() == GOLDEN_START, "standby and start are byte for byte the SDK's");
    check(s.size() == unilidar::USER_CMD_BYTES, "32 bytes, the size the header states");
    check(unilidar::readU32(kira::view(s).from(20)) == unilidar::crc32(kira::view(s).slice(12, 8)),
          "the CRC is over the 8 data bytes alone");
    check(unilidar::readU32(kira::view(s).from(20)) != unilidar::crc32(kira::view(s).slice(0, 20)),
          "and not over the header and the data");
    {
        std::array<std::uint8_t, 4> w{};
        unilidar::writeU32(kira::mutView(w), 0xA1B2C3D4u);
        check(w[0] == 0xD4 && w[3] == 0xA1 && unilidar::readU32(w) == 0xA1B2C3D4u, "writeU32 and readU32 are little-endian");
    }
    {
        unilidar::Header h;
        check(unilidar::readHeader(s, h) && h.type == unilidar::TYPE_USER_CMD && h.size == 32u, "readHeader of one frame");
        check(!unilidar::readHeader(DATAGRAM, h), "but not of a datagram holding two");
        check(!unilidar::readHeader(kira::view(s).slice(0, 20), h), "nor of a short buffer");
    }
    check(unilidar::tailClosed(s) && !unilidar::tailClosed(kira::view(s).slice(0, 31)), "tailClosed");
    check(unilidar::packetAt(DATAGRAM) == 32 && unilidar::packetAt(kira::view(DATAGRAM).from(64)) == 0,
          "packetAt: a whole packet, and none in three stray bytes");
    {
        kira::List<kira::Size> sizes;
        const unilidar::Walked w = unilidar::eachPacket(DATAGRAM, [&sizes](kira::View<std::uint8_t> packet) {
            sizes.push_back(packet.size());
        });
        check(w.packets == 2 && w.leftover == 3, "eachPacket: two packets, three bytes left over");
        check(sizes.size() == 2 && sizes[0] == 32 && sizes[1] == 32, "and a capturing lambda saw each");
    }
    check(unilidar::autoStandbyFrame(0).has_value() && !unilidar::autoStandbyFrame(7).has_value(),
          "autoStandbyFrame takes 0 or 1 and nothing else");
    check(kira::str::startsWith(unilidar::LIDAR_IP, "192.168.1.") && unilidar::LIDAR_PORT == 6101,
          "a Str constant is a const char* every Str helper takes");
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
