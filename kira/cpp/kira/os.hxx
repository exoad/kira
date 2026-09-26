// kira/os.hxx - the hosted runtime of kira:os: sockets, serial ports, files,
// processes, signals, the environment. The bodies are in os.cxx, which has a
// Win32 half and a POSIX half; this header names no OS type, so a module
// that includes it pulls in neither <windows.h> nor <sys/socket.h>.
//
// Errors are values: none, false, or -1, with the reason in lastError()
// (per thread). Nothing here throws.
//
// Linking: a program that includes this header compiles kira/os.cxx too,
// and on Windows links Winsock: MSVC takes ws2_32.lib from a pragma in
// os.cxx, while MinGW and zig -target x86_64-windows-gnu need -lws2_32 on
// the command line. Linux glibc needs -pthread.
#pragma once

#include "kira/rt.hxx"
#include "kira/sync.hxx"

#include <cstdint>

namespace kira::os
{
  // Why the last call on this thread failed, or "" when it did not: every
  // call that can fail clears it first. close() and the noexcept getters
  // leave it alone, so a dropped object does not erase the reason.
  [[nodiscard]] Str lastError();

  // Bits for Poller::add and Ready::events.
  inline constexpr std::int32_t POLL_READ = 1;
  inline constexpr std::int32_t POLL_WRITE = 2;
  inline constexpr std::int32_t POLL_ERROR = 4;

  struct Datagram
  {
      Size size = 0;
      Str host = "";
      std::int32_t port = 0;
  };

  struct Ready
  {
      std::int64_t handle = -1;
      std::int32_t events = 0;
  };

  class UdpSocket final
  {
  public:
      UdpSocket() = default;
      UdpSocket(const UdpSocket&) = delete;
      UdpSocket& operator=(const UdpSocket&) = delete;
      ~UdpSocket();

      [[nodiscard]] bool bind(const Str& host, std::int32_t port);
      [[nodiscard]] std::int32_t localPort() const;
      [[nodiscard]] std::int64_t sendTo(View<std::uint8_t> data, const Str& host, std::int32_t port) const;
      [[nodiscard]] Maybe<Datagram> recvFrom(MutView<std::uint8_t> into) const;
      [[nodiscard]] std::int64_t handle() const noexcept { return handle_; }
      void close();

  private:
      std::int64_t handle_ = -1;
  };

  class TcpStream;

  class TcpListener final
  {
  public:
      TcpListener() = default;
      TcpListener(const TcpListener&) = delete;
      TcpListener& operator=(const TcpListener&) = delete;
      ~TcpListener();

      [[nodiscard]] bool listen(std::int32_t port);
      [[nodiscard]] std::int32_t localPort() const;
      [[nodiscard]] Rc<TcpStream> accept() const;
      [[nodiscard]] std::int64_t handle() const noexcept { return handle_; }
      void close();

  private:
      std::int64_t handle_ = -1;
  };

  class TcpStream final
  {
  public:
      TcpStream() = default;
      explicit TcpStream(std::int64_t h) noexcept
          : handle_(h)
      {
      }
      TcpStream(const TcpStream&) = delete;
      TcpStream& operator=(const TcpStream&) = delete;
      ~TcpStream();

      [[nodiscard]] bool connect(const Str& host, std::int32_t port);
      [[nodiscard]] std::int64_t read(MutView<std::uint8_t> into) const;
      [[nodiscard]] std::int64_t write(View<std::uint8_t> data) const;
      [[nodiscard]] bool isOpen() const noexcept { return handle_ >= 0; }
      [[nodiscard]] std::int64_t handle() const noexcept { return handle_; }
      void close();

  private:
      std::int64_t handle_ = -1;
  };

  class Poller final
  {
  public:
      Poller() = default;
      Poller(const Poller&) = delete;
      Poller& operator=(const Poller&) = delete;

      void add(std::int64_t handle, std::int32_t events);
      void remove(std::int64_t handle);
      [[nodiscard]] List<Ready> wait(std::int64_t timeoutMs) const;

  private:
      struct Watch
      {
          std::int64_t handle;
          std::int32_t events;
      };
      List<Watch> watches_;
  };

  class Serial final
  {
  public:
      Serial() = default;
      Serial(const Serial&) = delete;
      Serial& operator=(const Serial&) = delete;
      ~Serial();

      [[nodiscard]] bool open(const Str& path, std::int32_t baud);
      [[nodiscard]] std::int64_t read(MutView<std::uint8_t> into, std::int64_t timeoutMs) const;
      [[nodiscard]] std::int64_t write(View<std::uint8_t> data) const;
      [[nodiscard]] bool isOpen() const noexcept { return open_; }
      // The descriptor on POSIX (pollable); -1 on Windows.
      [[nodiscard]] std::int64_t handle() const noexcept;
      void close();

  private:
#if defined(_WIN32)
      void* win_ = nullptr;                    // a HANDLE, never INVALID_HANDLE_VALUE here
      mutable std::int64_t winTimeoutMs_ = -2; // the COMMTIMEOUTS last set; -2 is none yet
#else
      std::int64_t fd_ = -1;
#endif
      bool open_ = false;
  };

  [[nodiscard]] Maybe<List<std::uint8_t>> readFile(const Str& path);
  [[nodiscard]] Maybe<Str> readText(const Str& path);
  [[nodiscard]] bool writeFileAtomic(const Str& path, View<std::uint8_t> bytes);
  [[nodiscard]] List<Str> listDir(const Str& path);
  [[nodiscard]] bool exists(const Str& path);
  [[nodiscard]] bool makeDirs(const Str& path);
  [[nodiscard]] std::int64_t freeBytes(const Str& path);

  class Process final
  {
  public:
      Process() = default;
      Process(const Process&) = delete;
      Process& operator=(const Process&) = delete;
      ~Process();

      [[nodiscard]] std::int64_t readStdout(MutView<std::uint8_t> into) const;
      void kill() const;
      [[nodiscard]] std::int32_t wait();
      [[nodiscard]] bool isRunning() const;
      // The read end of the stdout pipe on POSIX (pollable); -1 on Windows.
      [[nodiscard]] std::int64_t stdoutHandle() const noexcept;

  private:
      friend Rc<Process> spawnProcess(const List<Str>& argv);
#if defined(_WIN32)
      void* winProcess_ = nullptr; // the process HANDLE
      void* winOut_ = nullptr;     // the pipe's read end
#else
      std::int64_t pid_ = -1;
      std::int64_t out_ = -1;      // the pipe's read end
#endif
      bool exited_ = false;
      std::int32_t code_ = -1;
  };

  [[nodiscard]] Rc<Process> spawnProcess(const List<Str>& argv);

  inline constexpr std::int32_t SIGNAL_INT = 2;
  inline constexpr std::int32_t SIGNAL_TERM = 15;

  [[nodiscard]] bool onSignal(std::int32_t sig, const Rc<sync::Atomic<bool>>& flag);
  [[nodiscard]] Maybe<Str> env(const Str& name);
  [[noreturn]] void exit(std::int32_t code);
}
