// kira/os.cxx - the bodies of kira/os.hxx: a Win32 half (Winsock 2, COM
// ports, CreateProcess) and a POSIX half (BSD sockets, termios, fork/execvp).
// Under KIRA_PROFILE_FREESTANDING this file is empty: the Pico has no OS.
//
// Every failure sets the thread's lastError and returns a value (none, false
// or -1); nothing throws. Hosts are numeric IPv4 only, so a static musl
// binary needs no resolver.
#if !defined(KIRA_PROFILE_FREESTANDING)

#if defined(_MSC_VER)
#define _CRT_SECURE_NO_WARNINGS 1
#endif

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN 1
#endif
#if !defined(NOMINMAX)
#define NOMINMAX 1
#endif
// Windows 7: PROC_THREAD_ATTRIBUTE_HANDLE_LIST and WSAPoll need Vista or later.
#if !defined(_WIN32_WINNT) || _WIN32_WINNT < 0x0601
#undef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <io.h>
#if defined(_MSC_VER)
#pragma comment(lib, "ws2_32.lib")
#endif
// mstcpip.h's name for the ioctl that stops a UDP socket reporting ICMP
// port-unreachable as a reset; defined here so the header is not needed.
#if !defined(SIO_UDP_CONNRESET)
#define SIO_UDP_CONNRESET _WSAIOW(IOC_VENDOR, 12)
#endif
#else
#include <arpa/inet.h>
#include <dirent.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#if defined(__linux__)
#include <sys/syscall.h>
#endif
#endif

#include "kira/os.hxx"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <climits>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <utility>

namespace kira::os
{
  namespace
  {
#if defined(_WIN32) && defined(__GNUC__) && !defined(__clang__)
    // MinGW GCC keeps a thread_local in emutls and runs its destructor through
    // mingw-w64's __cxa_thread_atexit, and two threads that touched such an
    // object and exit at once corrupt the heap (STATUS_HEAP_CORRUPTION: MSYS2
    // UCRT64 g++ 13.2.0 died 5 runs of 5 on 1000 pairs of threads, each with
    // a lastError; zig's clang and MSVC never did). So on MinGW GCC the
    // thread's lastError lives in a fiber-local slot, and the slot's callback
    // frees it when the thread exits. A trivially destructible thread_local
    // is safe there.
    void WINAPI freeLastError(void* held) noexcept
    {
        delete static_cast<Str*>(held);
    }

    // The calling thread's lastError: null until the thread first keeps one,
    // unless `make`. Without a slot (FlsAlloc or FlsSetValue failed) the string
    // is kept through a thread_local pointer and never freed.
    Str* lastErrorOf(bool make)
    {
        static const DWORD slot = FlsAlloc(freeLastError);
        thread_local Str* unfreed = nullptr;
        Str* held = unfreed;
        if(held == nullptr && slot != FLS_OUT_OF_INDEXES)
        {
            held = static_cast<Str*>(FlsGetValue(slot));
        }
        if(held == nullptr && make)
        {
            held = new Str();
            if(slot == FLS_OUT_OF_INDEXES || !FlsSetValue(slot, held))
            {
                unfreed = held;
            }
        }
        return held;
    }
#else
    thread_local Str lastError_;

    Str* lastErrorOf(bool)
    {
        return &lastError_;
    }
#endif

    // The message is built before the store, so GetLastError and errno are
    // read before anything here can change them.
    void keepError(Str message)
    {
        *lastErrorOf(true) = std::move(message);
    }

    // Every public call that can fail starts here, so after a call that did
    // not fail lastError() is "": a quiet socket (none, "") and a failed one
    // (none, "recvfrom: ...") stay apart.
    void begin() noexcept
    {
        if(Str* held = lastErrorOf(false))
        {
            held->clear();
        }
    }

    // errno, as text: "<what>: <strerror> (errno N)".
    void failErrno(const char* what)
    {
        const int e = errno;
        keepError(cat(what, ": ", std::strerror(e), " (errno ", text(e), ")"));
    }

    void failWith(const char* what, const char* why)
    {
        keepError(cat(what, ": ", why));
    }

#if defined(_WIN32)
    void failWsa(const char* what)
    {
        keepError(cat(what, ": Winsock error ", text(WSAGetLastError())));
    }

    void failWin(const char* what)
    {
        keepError(cat(what, ": Win32 error ", text(static_cast<std::uint32_t>(GetLastError()))));
    }

    // WSAStartup once, WSACleanup at exit.
    struct Winsock
    {
        Winsock()
        {
            WSADATA data;
            (void)WSAStartup(MAKEWORD(2, 2), &data);
        }
        ~Winsock()
        {
            (void)WSACleanup();
        }
    };

    void ensureWinsock()
    {
        static Winsock once;
        (void)once;
    }

    using Sock = SOCKET;
    using SockLen = int;
    using IoLen = int;
    constexpr Sock NO_SOCK = INVALID_SOCKET;

    void failSock(const char* what)
    {
        failWsa(what);
    }

    bool wouldBlock() noexcept
    {
        return WSAGetLastError() == WSAEWOULDBLOCK;
    }

    void closeSock(Sock s) noexcept
    {
        (void)closesocket(s);
    }

    bool setBlocking(Sock s, bool blocking)
    {
        u_long nonBlocking = blocking ? 0 : 1;
        // FIONBIO is an unsigned long macro; ioctlsocket takes a long.
        return ioctlsocket(s, static_cast<long>(FIONBIO), &nonBlocking) == 0;
    }

    // A socket no child inherits: spawnProcess also lists the handles a child
    // gets, so this is the second guard, for a child started some other way.
    // The flag goes on at creation (WSA_FLAG_NO_HANDLE_INHERIT, below), so a
    // CreateProcess(bInheritHandles=TRUE) elsewhere in the process between
    // the two calls cannot see the socket without it; this call is the
    // fallback for a Winsock too old for the flag.
    void noInherit(Sock s) noexcept
    {
        (void)SetHandleInformation(reinterpret_cast<HANDLE>(s), HANDLE_FLAG_INHERIT, 0);
    }

#if !defined(WSA_FLAG_NO_HANDLE_INHERIT)
#define WSA_FLAG_NO_HANDLE_INHERIT 0x80
#endif

    [[nodiscard]] Sock rawSocket(int type) noexcept
    {
        // socket() is WSASocketW with WSA_FLAG_OVERLAPPED; NO_HANDLE_INHERIT
        // makes the handle non-inheritable from the start. A Winsock before
        // Windows 7 SP1 refuses the flag (WSAEINVAL): then socket(), and
        // noInherit's SetHandleInformation afterwards.
        const Sock s = WSASocketW(AF_INET, type, 0, nullptr, 0, WSA_FLAG_OVERLAPPED | WSA_FLAG_NO_HANDLE_INHERIT);
        if(s != INVALID_SOCKET || WSAGetLastError() != WSAEINVAL)
        {
            return s;
        }
        return ::socket(AF_INET, type, 0);
    }

    [[nodiscard]] Sock rawAccept(Sock listener) noexcept
    {
        // Measured (Windows 11, MinGW and clang): an accept from a listener
        // made with NO_HANDLE_INHERIT is non-inheritable itself; from a plain
        // listener it is inheritable. The caller's noInherit covers the
        // fallback listener.
        return ::accept(listener, nullptr, nullptr);
    }
#else
    void ensureWinsock() noexcept
    {
    }

    using Sock = int;
    using SockLen = socklen_t;
    using IoLen = std::size_t;
    constexpr Sock NO_SOCK = -1;

    void failSock(const char* what)
    {
        failErrno(what);
    }

    bool wouldBlock() noexcept
    {
        return errno == EAGAIN || errno == EWOULDBLOCK;
    }

    void closeSock(Sock s) noexcept
    {
        (void)::close(s);
    }

    bool setBlocking(Sock s, bool blocking)
    {
        const int flags = fcntl(s, F_GETFL, 0);
        if(flags < 0)
        {
            return false;
        }
        const int wanted = blocking ? (flags & ~O_NONBLOCK) : (flags | O_NONBLOCK);
        return fcntl(s, F_SETFL, wanted) == 0;
    }

    // Close-on-exec: a forked child (spawnProcess, or anyone else's fork)
    // must not keep a socket, or it holds the port after this process
    // exits. The flag goes on at creation where the libc allows it, so no
    // fork between the two calls can see the descriptor without it.
    void noInherit(Sock s) noexcept
    {
        (void)fcntl(s, F_SETFD, FD_CLOEXEC);
    }

    [[nodiscard]] Sock rawSocket(int type) noexcept
    {
#if defined(SOCK_CLOEXEC)
        return ::socket(AF_INET, type | SOCK_CLOEXEC, 0);
#else
        return ::socket(AF_INET, type, 0);
#endif
    }

    [[nodiscard]] Sock rawAccept(Sock listener) noexcept
    {
#if defined(SOCK_CLOEXEC) && defined(__linux__)
        return ::accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
#else
        return ::accept(listener, nullptr, nullptr);
#endif
    }
#endif

    [[nodiscard]] Sock sockOf(std::int64_t handle) noexcept
    {
        return static_cast<Sock>(handle);
    }

    // A count the socket calls accept: int on Windows, size_t on POSIX.
    [[nodiscard]] IoLen ioLen(Size n) noexcept
    {
        constexpr Size most = static_cast<Size>(INT_MAX);
        return static_cast<IoLen>(n < most ? n : most);
    }

    // "127.0.0.1" -> a sockaddr_in; "" is every interface.
    [[nodiscard]] bool toAddr(const Str& host, std::int32_t port, sockaddr_in& out)
    {
        std::memset(&out, 0, sizeof out);
        out.sin_family = AF_INET;
        if(port < 0 || port > 65535)
        {
            failWith("address", "port out of range");
            return false;
        }
        out.sin_port = htons(static_cast<std::uint16_t>(port));
        if(host.empty())
        {
            out.sin_addr.s_addr = htonl(INADDR_ANY);
            return true;
        }
        if(inet_pton(AF_INET, host.c_str(), &out.sin_addr) != 1)
        {
            failWith("address", "not a numeric IPv4 host");
            return false;
        }
        return true;
    }

    [[nodiscard]] Str hostOf(const sockaddr_in& addr)
    {
        char buf[INET_ADDRSTRLEN] = {0};
        if(inet_ntop(AF_INET, const_cast<in_addr*>(&addr.sin_addr), buf, sizeof buf) == nullptr)
        {
            return "";
        }
        return buf;
    }

    [[nodiscard]] std::int32_t portOfSock(Sock s)
    {
        sockaddr_in addr;
        SockLen len = sizeof addr;
        if(getsockname(s, reinterpret_cast<sockaddr*>(&addr), &len) != 0)
        {
            failSock("getsockname");
            return -1;
        }
        return static_cast<std::int32_t>(ntohs(addr.sin_port));
    }

    [[nodiscard]] Sock openSock(int type)
    {
        ensureWinsock();
        const Sock s = rawSocket(type);
        if(s == NO_SOCK)
        {
            failSock("socket");
            return s;
        }
        noInherit(s);
        return s;
    }

    // A poll timeout: negative is forever, and nothing exceeds INT_MAX.
    [[nodiscard]] int pollTimeout(std::int64_t timeoutMs) noexcept
    {
        if(timeoutMs < 0)
        {
            return -1;
        }
        return timeoutMs > INT_MAX ? INT_MAX : static_cast<int>(timeoutMs);
    }
  }

  Str lastError()
  {
      const Str* held = lastErrorOf(false);
      return held != nullptr ? *held : Str();
  }

  // ---- UdpSocket -------------------------------------------------------------------

  UdpSocket::~UdpSocket()
  {
      close();
  }

  bool UdpSocket::bind(const Str& host, std::int32_t port)
  {
      begin();
      close();
      sockaddr_in addr;
      if(!toAddr(host, port, addr))
      {
          return false;
      }
      const Sock s = openSock(SOCK_DGRAM);
      if(s == NO_SOCK)
      {
          return false;
      }
#if defined(_WIN32)
      // Off, or a datagram to a closed port fails the NEXT recvfrom with
      // WSAECONNRESET: UDP has no connection to reset (bibo's l2feed does the same).
      BOOL off = FALSE;
      DWORD got = 0;
      (void)WSAIoctl(s, SIO_UDP_CONNRESET, &off, static_cast<DWORD>(sizeof off), nullptr, 0, &got, nullptr, nullptr);
#endif
      if(::bind(s, reinterpret_cast<const sockaddr*>(&addr), sizeof addr) != 0)
      {
          failSock("bind");
          closeSock(s);
          return false;
      }
      if(!setBlocking(s, false))
      {
          failSock("non-blocking");
          closeSock(s);
          return false;
      }
      handle_ = static_cast<std::int64_t>(s);
      return true;
  }

  std::int32_t UdpSocket::localPort() const
  {
      begin();
      if(handle_ < 0)
      {
          failWith("localPort", "socket is not bound");
          return -1;
      }
      return portOfSock(sockOf(handle_));
  }

  std::int64_t UdpSocket::sendTo(View<std::uint8_t> data, const Str& host, std::int32_t port) const
  {
      begin();
      if(handle_ < 0)
      {
          failWith("sendTo", "socket is not bound");
          return -1;
      }
      sockaddr_in addr;
      if(!toAddr(host, port, addr))
      {
          return -1;
      }
      const auto sent = ::sendto(sockOf(handle_), reinterpret_cast<const char*>(data.data()), ioLen(data.size()), 0,
                                 reinterpret_cast<const sockaddr*>(&addr), sizeof addr);
      if(sent < 0)
      {
          failSock("sendto");
          return -1;
      }
      return static_cast<std::int64_t>(sent);
  }

  Maybe<Datagram> UdpSocket::recvFrom(MutView<std::uint8_t> into) const
  {
      begin();
      if(handle_ < 0)
      {
          failWith("recvFrom", "socket is not bound");
          return none;
      }
      sockaddr_in from;
      std::memset(&from, 0, sizeof from);
      SockLen fromLen = sizeof from;
      auto got = ::recvfrom(sockOf(handle_), reinterpret_cast<char*>(into.data()), ioLen(into.size()), 0,
                            reinterpret_cast<sockaddr*>(&from), &fromLen);
#if defined(_WIN32)
      // POSIX truncates a long datagram to the buffer and says nothing;
      // Winsock fills the buffer, sets `from`, and calls it WSAEMSGSIZE. The
      // promise is the POSIX one.
      if(got < 0 && WSAGetLastError() == WSAEMSGSIZE)
      {
          got = ioLen(into.size());
      }
#endif
      if(got < 0)
      {
          if(!wouldBlock())
          {
              failSock("recvfrom");
          }
          return none;
      }
      Datagram d;
      d.size = static_cast<Size>(got);
      d.host = hostOf(from);
      d.port = static_cast<std::int32_t>(ntohs(from.sin_port));
      return d;
  }

  void UdpSocket::close()
  {
      if(handle_ >= 0)
      {
          closeSock(sockOf(handle_));
          handle_ = -1;
      }
  }

  // ---- TcpListener -----------------------------------------------------------------

  TcpListener::~TcpListener()
  {
      close();
  }

  bool TcpListener::listen(std::int32_t port)
  {
      begin();
      close();
      sockaddr_in addr;
      if(!toAddr("", port, addr))
      {
          return false;
      }
      const Sock s = openSock(SOCK_STREAM);
      if(s == NO_SOCK)
      {
          return false;
      }
#if !defined(_WIN32)
      // A restarted server binds its port while the old connections linger
      // in TIME_WAIT. On Windows the same option lets a second socket steal
      // the port, so it stays off there.
      const int one = 1;
      (void)setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
#endif
      if(::bind(s, reinterpret_cast<const sockaddr*>(&addr), sizeof addr) != 0)
      {
          failSock("bind");
          closeSock(s);
          return false;
      }
      if(::listen(s, 16) != 0)
      {
          failSock("listen");
          closeSock(s);
          return false;
      }
      if(!setBlocking(s, false))
      {
          failSock("non-blocking");
          closeSock(s);
          return false;
      }
      handle_ = static_cast<std::int64_t>(s);
      return true;
  }

  std::int32_t TcpListener::localPort() const
  {
      begin();
      if(handle_ < 0)
      {
          failWith("localPort", "socket is not listening");
          return -1;
      }
      return portOfSock(sockOf(handle_));
  }

  Rc<TcpStream> TcpListener::accept() const
  {
      begin();
      if(handle_ < 0)
      {
          failWith("accept", "socket is not listening");
          return nullptr;
      }
      const Sock s = rawAccept(sockOf(handle_));
      if(s == NO_SOCK)
      {
          if(!wouldBlock())
          {
              failSock("accept");
          }
          return nullptr;
      }
      noInherit(s);
      // Linux hands out a blocking socket, Windows one that inherits the
      // listener's mode: make both blocking, as TcpStream promises.
      if(!setBlocking(s, true))
      {
          failSock("blocking");
          closeSock(s);
          return nullptr;
      }
      return std::make_shared<TcpStream>(static_cast<std::int64_t>(s));
  }

  void TcpListener::close()
  {
      if(handle_ >= 0)
      {
          closeSock(sockOf(handle_));
          handle_ = -1;
      }
  }

  // ---- TcpStream -------------------------------------------------------------------

  TcpStream::~TcpStream()
  {
      close();
  }

  bool TcpStream::connect(const Str& host, std::int32_t port)
  {
      begin();
      close();
      sockaddr_in addr;
      if(!toAddr(host, port, addr))
      {
          return false;
      }
      const Sock s = openSock(SOCK_STREAM);
      if(s == NO_SOCK)
      {
          return false;
      }
      if(::connect(s, reinterpret_cast<const sockaddr*>(&addr), sizeof addr) != 0)
      {
          failSock("connect");
          closeSock(s);
          return false;
      }
      handle_ = static_cast<std::int64_t>(s);
      return true;
  }

  std::int64_t TcpStream::read(MutView<std::uint8_t> into) const
  {
      begin();
      if(handle_ < 0)
      {
          failWith("read", "stream is closed");
          return -1;
      }
      const auto got = ::recv(sockOf(handle_), reinterpret_cast<char*>(into.data()), ioLen(into.size()), 0);
      if(got < 0)
      {
          failSock("recv");
          return -1;
      }
      return static_cast<std::int64_t>(got);
  }

  std::int64_t TcpStream::write(View<std::uint8_t> data) const
  {
      begin();
      if(handle_ < 0)
      {
          failWith("write", "stream is closed");
          return -1;
      }
#if defined(MSG_NOSIGNAL)
      constexpr int flags = MSG_NOSIGNAL; // a closed peer is an error here, never a SIGPIPE
#else
      constexpr int flags = 0;
#endif
      Size done = 0;
      while(done < data.size())
      {
          const auto sent = ::send(sockOf(handle_), reinterpret_cast<const char*>(data.data() + done),
                                   ioLen(data.size() - done), flags);
          if(sent < 0)
          {
              failSock("send");
              return -1;
          }
          done += static_cast<Size>(sent);
      }
      return static_cast<std::int64_t>(done);
  }

  void TcpStream::close()
  {
      if(handle_ >= 0)
      {
          closeSock(sockOf(handle_));
          handle_ = -1;
      }
  }

  // ---- Poller ----------------------------------------------------------------------

  void Poller::add(std::int64_t handle, std::int32_t events)
  {
      for(Watch& w : watches_)
      {
          if(w.handle == handle)
          {
              w.events = events;
              return;
          }
      }
      watches_.push_back(Watch{handle, events});
  }

  void Poller::remove(std::int64_t handle)
  {
      watches_.erase(std::remove_if(watches_.begin(), watches_.end(), [handle](const Watch& w) { return w.handle == handle; }),
                     watches_.end());
  }

  List<Ready> Poller::wait(std::int64_t timeoutMs) const
  {
      begin();
      List<Ready> ready;
      if(watches_.empty())
      {
          return ready;
      }
#if defined(_WIN32)
      using PollFd = WSAPOLLFD;
#else
      using PollFd = pollfd;
#endif
      List<PollFd> fds;
      fds.reserve(watches_.size());
      for(const Watch& w : watches_)
      {
          PollFd p;
          std::memset(&p, 0, sizeof p);
          p.fd = sockOf(w.handle);
          short events = 0;
          if((w.events & POLL_READ) != 0)
          {
              events = static_cast<short>(events | POLLIN);
          }
          if((w.events & POLL_WRITE) != 0)
          {
              events = static_cast<short>(events | POLLOUT);
          }
          p.events = events;
          fds.push_back(p);
      }
#if defined(_WIN32)
      const int n = WSAPoll(fds.data(), static_cast<ULONG>(fds.size()), pollTimeout(timeoutMs));
#else
      const int n = ::poll(fds.data(), static_cast<nfds_t>(fds.size()), pollTimeout(timeoutMs));
#endif
      if(n < 0)
      {
          failSock("poll");
          return ready;
      }
      for(Size i = 0; i < fds.size(); ++i)
      {
          const short re = fds[i].revents;
          if(re == 0)
          {
              continue;
          }
          std::int32_t events = 0;
          if((re & (POLLIN | POLLHUP)) != 0)
          {
              events |= POLL_READ;
          }
          if((re & POLLOUT) != 0)
          {
              events |= POLL_WRITE;
          }
          if((re & (POLLERR | POLLHUP | POLLNVAL)) != 0)
          {
              events |= POLL_ERROR;
          }
          ready.push_back(Ready{watches_[i].handle, events});
      }
      return ready;
  }

  // ---- Serial ----------------------------------------------------------------------

  Serial::~Serial()
  {
      close();
  }

#if defined(_WIN32)
  bool Serial::open(const Str& path, std::int32_t baud)
  {
      begin();
      close();
      // COM10 and above need the device namespace; COM1..9 accept it too.
      const Str device = path.rfind("\\\\", 0) == 0 ? path : cat("\\\\.\\", path);
      HANDLE h = CreateFileA(device.c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr, OPEN_EXISTING, 0, nullptr);
      if(h == INVALID_HANDLE_VALUE)
      {
          failWin("CreateFile");
          return false;
      }
      DCB dcb;
      std::memset(&dcb, 0, sizeof dcb);
      dcb.DCBlength = sizeof dcb;
      if(!GetCommState(h, &dcb))
      {
          failWin("GetCommState");
          CloseHandle(h);
          return false;
      }
      dcb.BaudRate = static_cast<DWORD>(baud);
      dcb.ByteSize = 8;
      dcb.Parity = NOPARITY;
      dcb.StopBits = ONESTOPBIT;
      dcb.fBinary = TRUE;
      dcb.fParity = FALSE;
      dcb.fOutxCtsFlow = FALSE;
      dcb.fOutxDsrFlow = FALSE;
      dcb.fDtrControl = DTR_CONTROL_ENABLE; // a USB CDC board sends nothing until DTR is up
      dcb.fDsrSensitivity = FALSE;
      dcb.fTXContinueOnXoff = TRUE;
      dcb.fOutX = FALSE;
      dcb.fInX = FALSE;
      dcb.fErrorChar = FALSE;
      dcb.fNull = FALSE;
      dcb.fRtsControl = RTS_CONTROL_ENABLE;
      dcb.fAbortOnError = FALSE;
      if(!SetCommState(h, &dcb))
      {
          failWin("SetCommState");
          CloseHandle(h);
          return false;
      }
      (void)PurgeComm(h, PURGE_RXCLEAR | PURGE_TXCLEAR);
      win_ = h;
      winTimeoutMs_ = -2;
      open_ = true;
      return true;
  }

  std::int64_t Serial::read(MutView<std::uint8_t> into, std::int64_t timeoutMs) const
  {
      begin();
      if(!open_)
      {
          failWith("read", "port is not open");
          return -1;
      }
      HANDLE h = static_cast<HANDLE>(win_);
      if(timeoutMs != winTimeoutMs_)
      {
          // Return as soon as any byte is in, else after the timeout; the
          // documented MAXDWORD/MAXDWORD/constant combination.
          COMMTIMEOUTS t;
          std::memset(&t, 0, sizeof t);
          t.ReadIntervalTimeout = MAXDWORD;
          if(timeoutMs == 0)
          {
              t.ReadTotalTimeoutMultiplier = 0;
              t.ReadTotalTimeoutConstant = 0;
          }
          else
          {
              t.ReadTotalTimeoutMultiplier = MAXDWORD;
              constexpr std::int64_t most = 0x7FFFFFFE;
              t.ReadTotalTimeoutConstant = static_cast<DWORD>(timeoutMs < 0 || timeoutMs > most ? most : timeoutMs);
          }
          if(!SetCommTimeouts(h, &t))
          {
              failWin("SetCommTimeouts");
              return -1;
          }
          winTimeoutMs_ = timeoutMs;
      }
      DWORD got = 0;
      const DWORD want = static_cast<DWORD>(ioLen(into.size()));
      if(!ReadFile(h, into.data(), want, &got, nullptr))
      {
          failWin("ReadFile");
          return -1;
      }
      return static_cast<std::int64_t>(got);
  }

  std::int64_t Serial::write(View<std::uint8_t> data) const
  {
      begin();
      if(!open_)
      {
          failWith("write", "port is not open");
          return -1;
      }
      HANDLE h = static_cast<HANDLE>(win_);
      Size done = 0;
      while(done < data.size())
      {
          DWORD wrote = 0;
          const DWORD want = static_cast<DWORD>(ioLen(data.size() - done));
          if(!WriteFile(h, data.data() + done, want, &wrote, nullptr))
          {
              failWin("WriteFile");
              return -1;
          }
          if(wrote == 0)
          {
              failWith("WriteFile", "wrote nothing");
              return -1;
          }
          done += static_cast<Size>(wrote);
      }
      return static_cast<std::int64_t>(done);
  }

  std::int64_t Serial::handle() const noexcept
  {
      return -1;
  }

  void Serial::close()
  {
      if(open_)
      {
          CloseHandle(static_cast<HANDLE>(win_));
          win_ = nullptr;
          open_ = false;
      }
  }
#else
  namespace
  {
    // The termios constant for a standard rate, or 0 when this libc lacks it.
    // The slow rates matter too: a Pico's bootloader touch is 1200 baud.
    [[nodiscard]] speed_t speedOf(std::int32_t baud) noexcept
    {
        switch(baud)
        {
            case 50: return B50;
            case 75: return B75;
            case 110: return B110;
            case 134: return B134;
            case 150: return B150;
            case 200: return B200;
            case 300: return B300;
            case 600: return B600;
            case 1200: return B1200;
            case 1800: return B1800;
            case 2400: return B2400;
            case 4800: return B4800;
            case 9600: return B9600;
            case 19200: return B19200;
            case 38400: return B38400;
            case 57600: return B57600;
            case 115200: return B115200;
            case 230400: return B230400;
#if defined(B460800)
            case 460800: return B460800;
#endif
#if defined(B500000)
            case 500000: return B500000;
#endif
#if defined(B576000)
            case 576000: return B576000;
#endif
#if defined(B921600)
            case 921600: return B921600;
#endif
#if defined(B1000000)
            case 1000000: return B1000000;
#endif
#if defined(B1152000)
            case 1152000: return B1152000;
#endif
#if defined(B1500000)
            case 1500000: return B1500000;
#endif
#if defined(B2000000)
            case 2000000: return B2000000;
#endif
#if defined(B2500000)
            case 2500000: return B2500000;
#endif
#if defined(B3000000)
            case 3000000: return B3000000;
#endif
#if defined(B3500000)
            case 3500000: return B3500000;
#endif
#if defined(B4000000)
            case 4000000: return B4000000;
#endif
            default: return 0;
        }
    }
  }

  bool Serial::open(const Str& path, std::int32_t baud)
  {
      begin();
      close();
      const speed_t speed = speedOf(baud);
      if(speed == 0)
      {
          failWith("open", "not a standard baud rate");
          return false;
      }
      const int fd = ::open(path.c_str(), O_RDWR | O_NOCTTY | O_NONBLOCK | O_CLOEXEC);
      if(fd < 0)
      {
          failErrno("open");
          return false;
      }
      termios tio;
      std::memset(&tio, 0, sizeof tio);
      if(tcgetattr(fd, &tio) != 0)
      {
          failErrno("tcgetattr");
          ::close(fd);
          return false;
      }
      cfmakeraw(&tio);
      tio.c_cflag |= static_cast<tcflag_t>(CLOCAL | CREAD);
      tio.c_cflag &= static_cast<tcflag_t>(~static_cast<tcflag_t>(CRTSCTS));
      tio.c_cc[VMIN] = 0;
      tio.c_cc[VTIME] = 0;
      if(cfsetispeed(&tio, speed) != 0 || cfsetospeed(&tio, speed) != 0)
      {
          failErrno("cfsetspeed");
          ::close(fd);
          return false;
      }
      if(tcsetattr(fd, TCSANOW, &tio) != 0)
      {
          failErrno("tcsetattr");
          ::close(fd);
          return false;
      }
      (void)tcflush(fd, TCIOFLUSH);
      fd_ = fd;
      open_ = true;
      return true;
  }

  std::int64_t Serial::read(MutView<std::uint8_t> into, std::int64_t timeoutMs) const
  {
      begin();
      if(!open_)
      {
          failWith("read", "port is not open");
          return -1;
      }
      const int fd = static_cast<int>(fd_);
      pollfd p;
      std::memset(&p, 0, sizeof p);
      p.fd = fd;
      p.events = POLLIN;
      const int n = ::poll(&p, 1, pollTimeout(timeoutMs));
      if(n < 0)
      {
          failErrno("poll");
          return -1;
      }
      if(n == 0)
      {
          return 0;
      }
      const ssize_t got = ::read(fd, into.data(), into.size());
      if(got < 0)
      {
          if(errno == EAGAIN || errno == EWOULDBLOCK)
          {
              return 0;
          }
          failErrno("read");
          return -1;
      }
      return static_cast<std::int64_t>(got);
  }

  std::int64_t Serial::write(View<std::uint8_t> data) const
  {
      begin();
      if(!open_)
      {
          failWith("write", "port is not open");
          return -1;
      }
      const int fd = static_cast<int>(fd_);
      Size done = 0;
      while(done < data.size())
      {
          const ssize_t wrote = ::write(fd, data.data() + done, data.size() - done);
          if(wrote < 0)
          {
              if(errno == EAGAIN || errno == EWOULDBLOCK)
              {
                  pollfd p;
                  std::memset(&p, 0, sizeof p);
                  p.fd = fd;
                  p.events = POLLOUT;
                  if(::poll(&p, 1, 1000) <= 0)
                  {
                      failWith("write", "the port took nothing for a second");
                      return -1;
                  }
                  continue;
              }
              failErrno("write");
              return -1;
          }
          done += static_cast<Size>(wrote);
      }
      return static_cast<std::int64_t>(done);
  }

  std::int64_t Serial::handle() const noexcept
  {
      return open_ ? fd_ : -1;
  }

  void Serial::close()
  {
      if(open_)
      {
          ::close(static_cast<int>(fd_));
          fd_ = -1;
          open_ = false;
      }
  }
#endif

  // ---- files -----------------------------------------------------------------------

  namespace
  {
    // fopen in binary, with the file marked not-inherited at creation: "e"
    // (O_CLOEXEC; glibc, musl, the BSDs) or "N" (_O_NOINHERIT; UCRT and
    // msvcrt). A child forked or created on another thread while this call
    // holds the file then never sees it. `mode` is "r" or "w".
    [[nodiscard]] std::FILE* openBinary(const Str& path, const char* mode)
    {
#if defined(_WIN32)
        const Str full = cat(mode, "bN");
#else
        const Str full = cat(mode, "be");
#endif
        return std::fopen(path.c_str(), full.c_str());
    }

    // Reads the whole file through stdio, in binary, into `out` by chunks.
    template<class Container>
    [[nodiscard]] bool slurp(const Str& path, Container& out)
    {
        std::FILE* f = openBinary(path, "r");
        if(f == nullptr)
        {
            failErrno("open");
            return false;
        }
        char chunk[65536];
        for(;;)
        {
            const Size got = std::fread(chunk, 1, sizeof chunk, f);
            if(got > 0)
            {
                out.insert(out.end(), chunk, chunk + got);
            }
            if(got < sizeof chunk)
            {
                break;
            }
        }
        const bool failed = std::ferror(f) != 0;
        if(failed)
        {
            failErrno("read");
        }
        std::fclose(f);
        return !failed;
    }

    [[nodiscard]] bool isDirectory(const Str& path)
    {
#if defined(_WIN32)
        const DWORD attrs = GetFileAttributesA(path.c_str());
        return attrs != INVALID_FILE_ATTRIBUTES && (attrs & FILE_ATTRIBUTE_DIRECTORY) != 0;
#else
        struct stat st;
        return ::stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
#endif
    }

    [[nodiscard]] bool makeOneDir(const Str& path)
    {
#if defined(_WIN32)
        if(CreateDirectoryA(path.c_str(), nullptr))
        {
            return true;
        }
        if(GetLastError() == ERROR_ALREADY_EXISTS)
        {
            return true;
        }
        failWin("CreateDirectory");
        return false;
#else
        if(::mkdir(path.c_str(), 0755) == 0 || errno == EEXIST)
        {
            return true;
        }
        failErrno("mkdir");
        return false;
#endif
    }

    [[nodiscard]] bool isSeparator(char c) noexcept
    {
#if defined(_WIN32)
        return c == '/' || c == '\\';
#else
        return c == '/';
#endif
    }

    [[nodiscard]] std::int64_t processId() noexcept
    {
#if defined(_WIN32)
        return static_cast<std::int64_t>(GetCurrentProcessId());
#else
        return static_cast<std::int64_t>(::getpid());
#endif
    }
  }

  Maybe<List<std::uint8_t>> readFile(const Str& path)
  {
      begin();
      List<std::uint8_t> bytes;
      if(!slurp(path, bytes))
      {
          return none;
      }
      return bytes;
  }

  Maybe<Str> readText(const Str& path)
  {
      begin();
      Str text;
      if(!slurp(path, text))
      {
          return none;
      }
      return text;
  }

  bool writeFileAtomic(const Str& path, View<std::uint8_t> bytes)
  {
      begin();
      // A temporary beside the target, on the same filesystem, so the rename
      // is one metadata operation. The pid keeps two processes apart and the
      // counter two threads of this one: each writer has its own temporary,
      // and the last rename wins whole.
      static std::atomic<std::uint64_t> writers{0};
      const std::uint64_t nth = writers.fetch_add(1, std::memory_order_relaxed);
      const Str tmp = cat(path, ".", text(processId()), ".", text(nth), ".tmp");
      std::FILE* f = openBinary(tmp, "w");
      if(f == nullptr)
      {
          failErrno("open temporary");
          return false;
      }
      bool ok = bytes.size() == 0 || std::fwrite(bytes.data(), 1, bytes.size(), f) == bytes.size();
      if(!ok)
      {
          failErrno("write");
      }
      if(ok && std::fflush(f) != 0)
      {
          failErrno("flush");
          ok = false;
      }
      if(ok)
      {
#if defined(_WIN32)
          HANDLE h = reinterpret_cast<HANDLE>(_get_osfhandle(_fileno(f)));
          if(h == INVALID_HANDLE_VALUE || !FlushFileBuffers(h))
          {
              failWin("FlushFileBuffers");
              ok = false;
          }
#else
          if(::fsync(fileno(f)) != 0)
          {
              failErrno("fsync");
              ok = false;
          }
#endif
      }
      if(std::fclose(f) != 0 && ok)
      {
          failErrno("close");
          ok = false;
      }
      if(ok)
      {
#if defined(_WIN32)
          // Windows refuses a rename over a file that another handle is
          // replacing or has open at that instant (ERROR_ACCESS_DENIED,
          // ERROR_SHARING_VIOLATION), a passing state: two writers of one
          // path, or a reader mid-read. Retry for up to about 200 ms.
          for(int attempt = 0;; ++attempt)
          {
              if(MoveFileExA(tmp.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
              {
                  break;
              }
              const DWORD why = GetLastError();
              const bool passing = why == ERROR_ACCESS_DENIED || why == ERROR_SHARING_VIOLATION;
              if(!passing || attempt >= 20)
              {
                  SetLastError(why);
                  failWin("MoveFileEx");
                  ok = false;
                  break;
              }
              Sleep(attempt < 10 ? 1 : 20);
          }
#else
          if(std::rename(tmp.c_str(), path.c_str()) != 0)
          {
              failErrno("rename");
              ok = false;
          }
#endif
      }
      if(!ok)
      {
          (void)std::remove(tmp.c_str());
      }
      return ok;
  }

  List<Str> listDir(const Str& path)
  {
      begin();
      List<Str> names;
#if defined(_WIN32)
      const Str pattern = cat(path, "\\*");
      WIN32_FIND_DATAA found;
      HANDLE h = FindFirstFileA(pattern.c_str(), &found);
      if(h == INVALID_HANDLE_VALUE)
      {
          failWin("FindFirstFile");
          return names;
      }
      do
      {
          const Str name = found.cFileName;
          if(name != "." && name != "..")
          {
              names.push_back(name);
          }
      } while(FindNextFileA(h, &found));
      if(GetLastError() != ERROR_NO_MORE_FILES)
      {
          // The same rule as readdir below: a listing that failed part-way
          // is reported, not returned as if whole.
          failWin("FindNextFile");
          names.clear();
      }
      FindClose(h);
#else
      DIR* d = ::opendir(path.c_str());
      if(d == nullptr)
      {
          failErrno("opendir");
          return names;
      }
      for(;;)
      {
          // readdir returns null at the end and on an error alike; only
          // errno tells them apart. A read that fails part-way reports the
          // failure and returns nothing, never a partial list as if whole.
          errno = 0;
          const dirent* e = ::readdir(d);
          if(e == nullptr)
          {
              if(errno != 0)
              {
                  failErrno("readdir");
                  names.clear();
              }
              break;
          }
          const Str name = e->d_name;
          if(name != "." && name != "..")
          {
              names.push_back(name);
          }
      }
      ::closedir(d);
#endif
      std::sort(names.begin(), names.end());
      return names;
  }

  bool exists(const Str& path)
  {
      begin();
#if defined(_WIN32)
      return GetFileAttributesA(path.c_str()) != INVALID_FILE_ATTRIBUTES;
#else
      struct stat st;
      return ::stat(path.c_str(), &st) == 0;
#endif
  }

  bool makeDirs(const Str& path)
  {
      begin();
      if(path.empty())
      {
          failWith("makeDirs", "empty path");
          return false;
      }
      for(Size i = 1; i < path.size(); ++i)
      {
          if(!isSeparator(path[i]) || isSeparator(path[i - 1]))
          {
              continue;
          }
          const Str prefix = path.substr(0, i);
#if defined(_WIN32)
          if(prefix.size() == 2 && prefix[1] == ':')
          {
              continue; // a drive, not a directory to make
          }
#endif
          if(isDirectory(prefix))
          {
              continue;
          }
          if(!makeOneDir(prefix))
          {
              return false;
          }
      }
      if(!isDirectory(path) && !makeOneDir(path))
      {
          return false;
      }
      if(!isDirectory(path))
      {
          failWith("makeDirs", "the path exists and is not a directory");
          return false;
      }
      return true;
  }

  std::int64_t freeBytes(const Str& path)
  {
      begin();
#if defined(_WIN32)
      ULARGE_INTEGER avail;
      avail.QuadPart = 0;
      if(!GetDiskFreeSpaceExA(path.c_str(), &avail, nullptr, nullptr))
      {
          failWin("GetDiskFreeSpaceEx");
          return -1;
      }
      return static_cast<std::int64_t>(avail.QuadPart);
#else
      struct statvfs st;
      if(::statvfs(path.c_str(), &st) != 0)
      {
          failErrno("statvfs");
          return -1;
      }
      return static_cast<std::int64_t>(st.f_bavail) * static_cast<std::int64_t>(st.f_frsize);
#endif
  }

  // ---- Process ---------------------------------------------------------------------

#if defined(_WIN32)
  namespace
  {
    // One argument as CreateProcess's command line rules read it back.
    [[nodiscard]] Str quoteArg(const Str& a)
    {
        if(!a.empty() && a.find_first_of(" \t\n\v\"") == Str::npos)
        {
            return a;
        }
        Str out = "\"";
        Size backslashes = 0;
        for(const char c : a)
        {
            if(c == '\\')
            {
                ++backslashes;
                continue;
            }
            if(c == '"')
            {
                out.append(backslashes * 2 + 1, '\\');
                out += '"';
                backslashes = 0;
                continue;
            }
            out.append(backslashes, '\\');
            backslashes = 0;
            out += c;
        }
        out.append(backslashes * 2, '\\');
        out += '"';
        return out;
    }
  }

  Process::~Process()
  {
      if(winProcess_ != nullptr)
      {
          if(!exited_ && WaitForSingleObject(static_cast<HANDLE>(winProcess_), 0) == WAIT_TIMEOUT)
          {
              (void)TerminateProcess(static_cast<HANDLE>(winProcess_), 137);
              (void)WaitForSingleObject(static_cast<HANDLE>(winProcess_), INFINITE);
          }
          CloseHandle(static_cast<HANDLE>(winProcess_));
      }
      if(winOut_ != nullptr)
      {
          CloseHandle(static_cast<HANDLE>(winOut_));
      }
  }

  std::int64_t Process::readStdout(MutView<std::uint8_t> into) const
  {
      begin();
      if(winOut_ == nullptr)
      {
          failWith("readStdout", "no pipe");
          return -1;
      }
      DWORD got = 0;
      if(!ReadFile(static_cast<HANDLE>(winOut_), into.data(), static_cast<DWORD>(ioLen(into.size())), &got, nullptr))
      {
          if(GetLastError() == ERROR_BROKEN_PIPE)
          {
              return 0;
          }
          failWin("ReadFile");
          return -1;
      }
      return static_cast<std::int64_t>(got);
  }

  void Process::kill() const
  {
      if(winProcess_ != nullptr && !exited_)
      {
          (void)TerminateProcess(static_cast<HANDLE>(winProcess_), 137);
      }
  }

  std::int32_t Process::wait()
  {
      begin();
      if(exited_)
      {
          return code_;
      }
      if(winProcess_ == nullptr)
      {
          failWith("wait", "no process");
          return -1;
      }
      (void)WaitForSingleObject(static_cast<HANDLE>(winProcess_), INFINITE);
      DWORD code = 0;
      if(!GetExitCodeProcess(static_cast<HANDLE>(winProcess_), &code))
      {
          failWin("GetExitCodeProcess");
          return -1;
      }
      exited_ = true;
      code_ = static_cast<std::int32_t>(code);
      return code_;
  }

  bool Process::isRunning() const
  {
      begin();
      if(exited_ || winProcess_ == nullptr)
      {
          return false;
      }
      return WaitForSingleObject(static_cast<HANDLE>(winProcess_), 0) == WAIT_TIMEOUT;
  }

  std::int64_t Process::stdoutHandle() const noexcept
  {
      return -1;
  }

  namespace
  {
    // An inheritable duplicate of one of this process's standard handles, or
    // nullptr when there is none (a process without a console): only a
    // handle in the child's list may be inherited, and it must be marked so.
    [[nodiscard]] HANDLE inheritableCopy(HANDLE h) noexcept
    {
        if(h == nullptr || h == INVALID_HANDLE_VALUE)
        {
            return nullptr;
        }
        HANDLE copy = nullptr;
        if(!DuplicateHandle(GetCurrentProcess(), h, GetCurrentProcess(), &copy, 0, TRUE, DUPLICATE_SAME_ACCESS))
        {
            return nullptr;
        }
        return copy;
    }

    void closeIf(HANDLE h) noexcept
    {
        if(h != nullptr)
        {
            CloseHandle(h);
        }
    }
  }

  Rc<Process> spawnProcess(const List<Str>& argv)
  {
      begin();
      if(argv.empty())
      {
          failWith("spawnProcess", "empty argv");
          return nullptr;
      }
      SECURITY_ATTRIBUTES sa;
      std::memset(&sa, 0, sizeof sa);
      sa.nLength = sizeof sa;
      sa.bInheritHandle = TRUE;
      HANDLE readEnd = nullptr;
      HANDLE writeEnd = nullptr;
      if(!CreatePipe(&readEnd, &writeEnd, &sa, 0))
      {
          failWin("CreatePipe");
          return nullptr;
      }
      // The child must not inherit the read end, or its EOF never comes.
      (void)SetHandleInformation(readEnd, HANDLE_FLAG_INHERIT, 0);

      Str line;
      for(Size i = 0; i < argv.size(); ++i)
      {
          if(i > 0)
          {
              line += ' ';
          }
          line += quoteArg(argv[i]);
      }
      List<char> buffer(line.begin(), line.end());
      buffer.push_back('\0');

      // The child inherits exactly three handles: the pipe's write end and
      // copies of this process's stdin and stderr. bInheritHandles=TRUE alone
      // would hand it every inheritable handle in the process, and a child
      // that outlives this process would then hold its files and ports.
      HANDLE in = inheritableCopy(GetStdHandle(STD_INPUT_HANDLE));
      HANDLE err = inheritableCopy(GetStdHandle(STD_ERROR_HANDLE));
      HANDLE inherit[3];
      DWORD inherited = 0;
      inherit[inherited++] = writeEnd;
      if(in != nullptr)
      {
          inherit[inherited++] = in;
      }
      if(err != nullptr)
      {
          inherit[inherited++] = err;
      }
      SIZE_T attrSize = 0;
      (void)InitializeProcThreadAttributeList(nullptr, 1, 0, &attrSize);
      List<void*> attrStore((attrSize + sizeof(void*) - 1) / sizeof(void*) + 1);
      auto* attrs = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attrStore.data());
      bool listed = InitializeProcThreadAttributeList(attrs, 1, 0, &attrSize) != 0;
      if(listed && !UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherit,
                                              inherited * sizeof(HANDLE), nullptr, nullptr))
      {
          DeleteProcThreadAttributeList(attrs);
          listed = false;
      }
      if(!listed)
      {
          failWin("ProcThreadAttributeList");
          CloseHandle(writeEnd);
          CloseHandle(readEnd);
          closeIf(in);
          closeIf(err);
          return nullptr;
      }

      STARTUPINFOEXA si;
      std::memset(&si, 0, sizeof si);
      si.StartupInfo.cb = sizeof si;
      si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
      si.StartupInfo.hStdInput = in;
      si.StartupInfo.hStdOutput = writeEnd;
      si.StartupInfo.hStdError = err;
      si.lpAttributeList = attrs;
      PROCESS_INFORMATION pi;
      std::memset(&pi, 0, sizeof pi);
      const BOOL started = CreateProcessA(nullptr, buffer.data(), nullptr, nullptr, TRUE, EXTENDED_STARTUPINFO_PRESENT,
                                          nullptr, nullptr, &si.StartupInfo, &pi);
      const DWORD why = started ? 0 : GetLastError();
      DeleteProcThreadAttributeList(attrs);
      CloseHandle(writeEnd);
      closeIf(in);
      closeIf(err);
      if(!started)
      {
          SetLastError(why);
          failWin("CreateProcess");
          CloseHandle(readEnd);
          return nullptr;
      }
      CloseHandle(pi.hThread);
      Rc<Process> p = std::make_shared<Process>();
      p->winProcess_ = pi.hProcess;
      p->winOut_ = readEnd;
      return p;
  }
#else
  namespace
  {
    // waitpid's status as the exit code kira:os reports.
    [[nodiscard]] std::int32_t codeOf(int status) noexcept
    {
        if(WIFEXITED(status))
        {
            return static_cast<std::int32_t>(WEXITSTATUS(status));
        }
        if(WIFSIGNALED(status))
        {
            return 128 + static_cast<std::int32_t>(WTERMSIG(status));
        }
        return -1;
    }

    // waitpid without EINTR: the status, or -1 when there is no such child.
    [[nodiscard]] int reap(pid_t pid) noexcept
    {
        int status = 0;
        for(;;)
        {
            const pid_t r = ::waitpid(pid, &status, 0);
            if(r < 0 && errno == EINTR)
            {
                continue;
            }
            return r < 0 ? -1 : status;
        }
    }

    // A pipe whose two ends are close-on-exec from the moment they exist:
    // pipe2(O_CLOEXEC) where the libc has it. pipe() and then fcntl leaves
    // a window in which a fork on another thread hands its child both ends
    // past its exec; a long-lived sibling then holds this pipe open and the
    // reader never sees EOF. The same rule as the sockets' SOCK_CLOEXEC.
    [[nodiscard]] bool pipeCloexec(int fds[2]) noexcept
    {
#if defined(__linux__) || defined(__FreeBSD__) || defined(__NetBSD__) || defined(__OpenBSD__)
        return ::pipe2(fds, O_CLOEXEC) == 0;
#else
        if(::pipe(fds) != 0)
        {
            return false;
        }
        (void)fcntl(fds[0], F_SETFD, FD_CLOEXEC);
        (void)fcntl(fds[1], F_SETFD, FD_CLOEXEC);
        return true;
#endif
    }

    // The number of descriptors this process may hold: the bound for the
    // child's close loop below. Taken before the fork; getrlimit and sysconf
    // are not on the async-signal-safe list.
    [[nodiscard]] long descriptorLimit() noexcept
    {
        struct rlimit rl;
        if(::getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY && rl.rlim_cur <= static_cast<rlim_t>(LONG_MAX))
        {
            return static_cast<long>(rl.rlim_cur);
        }
        const long open = ::sysconf(_SC_OPEN_MAX);
        return open > 0 ? open : 65536;
    }

    // In the child, between fork and exec: closes every descriptor from 3
    // up except `keep` (the status pipe's write end, close-on-exec itself).
    // Only async-signal-safe calls. close_range(2) does it in one call on
    // Linux 5.9 and later; an older kernel (ENOSYS) gets a close() per
    // descriptor up to the limit, and close on a descriptor that is not
    // open is a harmless EBADF.
    void closeFrom3Except(int keep, long limit) noexcept
    {
#if defined(__linux__) && defined(SYS_close_range)
        const unsigned int last = ~0u;
        const unsigned int k = static_cast<unsigned int>(keep);
        const long a = k > 3u ? ::syscall(SYS_close_range, 3u, k - 1u, 0u) : 0;
        const long b = ::syscall(SYS_close_range, k + 1u, last, 0u);
        if(a == 0 && b == 0)
        {
            return;
        }
#endif
        for(long fd = 3; fd < limit; ++fd)
        {
            if(fd != keep)
            {
                (void)::close(static_cast<int>(fd));
            }
        }
    }
  }

  Process::~Process()
  {
      if(out_ >= 0)
      {
          ::close(static_cast<int>(out_));
      }
      if(pid_ > 0 && !exited_)
      {
          (void)::kill(static_cast<pid_t>(pid_), SIGKILL);
          (void)reap(static_cast<pid_t>(pid_));
      }
  }

  std::int64_t Process::readStdout(MutView<std::uint8_t> into) const
  {
      begin();
      if(out_ < 0)
      {
          failWith("readStdout", "no pipe");
          return -1;
      }
      const ssize_t got = ::read(static_cast<int>(out_), into.data(), into.size());
      if(got < 0)
      {
          failErrno("read");
          return -1;
      }
      return static_cast<std::int64_t>(got);
  }

  void Process::kill() const
  {
      if(pid_ > 0 && !exited_)
      {
          (void)::kill(static_cast<pid_t>(pid_), SIGKILL);
      }
  }

  std::int32_t Process::wait()
  {
      begin();
      if(exited_)
      {
          return code_;
      }
      if(pid_ <= 0)
      {
          failWith("wait", "no process");
          return -1;
      }
      const int status = reap(static_cast<pid_t>(pid_));
      if(status < 0)
      {
          failErrno("waitpid");
          return -1;
      }
      exited_ = true;
      code_ = codeOf(status);
      return code_;
  }

  bool Process::isRunning() const
  {
      begin();
      if(exited_ || pid_ <= 0)
      {
          return false;
      }
      int status = 0;
      const pid_t r = ::waitpid(static_cast<pid_t>(pid_), &status, WNOHANG);
      if(r == 0)
      {
          return true;
      }
      if(r > 0)
      {
          // Reaped here; wait() would report -1 after this, so keep the code.
          const_cast<Process*>(this)->exited_ = true;
          const_cast<Process*>(this)->code_ = codeOf(status);
      }
      return false;
  }

  std::int64_t Process::stdoutHandle() const noexcept
  {
      return out_;
  }

  Rc<Process> spawnProcess(const List<Str>& argv)
  {
      begin();
      if(argv.empty())
      {
          failWith("spawnProcess", "empty argv");
          return nullptr;
      }
      // Only the child's stdout gets the write end (dup2 clears the flag on
      // the copy); a later child must not inherit this read end, or the
      // first one's EOF never comes.
      int fds[2] = {-1, -1};
      if(!pipeCloexec(fds))
      {
          failErrno("pipe");
          return nullptr;
      }
      // The status pipe: close-on-exec, so a successful exec closes it and
      // the parent reads nothing; a failed exec writes errno into it first.
      // That is how "none when it could not start" holds on this half too.
      int status[2] = {-1, -1};
      if(!pipeCloexec(status))
      {
          failErrno("pipe");
          ::close(fds[0]);
          ::close(fds[1]);
          return nullptr;
      }
      const long limit = descriptorLimit();

      List<Str> copies(argv.begin(), argv.end());
      List<char*> args;
      args.reserve(copies.size() + 1);
      for(Str& s : copies)
      {
          args.push_back(s.data());
      }
      args.push_back(nullptr);

      std::fflush(nullptr); // a buffered line must not be written twice
      const pid_t pid = ::fork();
      if(pid < 0)
      {
          failErrno("fork");
          ::close(fds[0]);
          ::close(fds[1]);
          ::close(status[0]);
          ::close(status[1]);
          return nullptr;
      }
      if(pid == 0)
      {
          // Between fork and exec only async-signal-safe calls: a lock another
          // thread held at the fork is held forever in this child.
          //
          // The child gets stdin, stderr and its stdout pipe and nothing
          // else. Close-on-exec covers what kira:os opened, and nothing
          // else: a file or device that C++ code or an FFI call opened
          // without O_CLOEXEC would reach the child and outlive this
          // process with it. So every descriptor from 3 up is closed here,
          // before the exec, except the status pipe's write end, which the
          // exec itself closes. That end may sit below 3 in a process that
          // closed a standard stream; it is moved up first so the loop
          // spares it and the dup2 below cannot land on it.
          int tell = status[1];
          if(tell < 3)
          {
              tell = fcntl(tell, F_DUPFD_CLOEXEC, 3);
          }
          bool ready = tell >= 3;
          if(ready)
          {
              if(fds[1] == STDOUT_FILENO)
              {
                  // Already in place: only its close-on-exec flag must go.
                  ready = fcntl(STDOUT_FILENO, F_SETFD, 0) == 0;
              }
              else
              {
                  ready = ::dup2(fds[1], STDOUT_FILENO) >= 0;
              }
          }
          if(ready)
          {
              closeFrom3Except(tell, limit);
              ::execvp(args[0], args.data());
          }
          const int failed = errno;
          (void)!::write(tell, &failed, sizeof failed);
          ::_exit(127);
      }
      ::close(fds[1]);
      ::close(status[1]);
      int failed = 0;
      ssize_t told = 0;
      for(;;)
      {
          told = ::read(status[0], &failed, sizeof failed);
          if(told < 0 && errno == EINTR)
          {
              continue;
          }
          break;
      }
      ::close(status[0]);
      if(told == static_cast<ssize_t>(sizeof failed))
      {
          // The child could not exec: it has exited 127 by now, so reap it and
          // report why, as the Win32 half does from CreateProcess.
          (void)reap(pid);
          ::close(fds[0]);
          errno = failed;
          failErrno(cat("execvp ", argv[0]).c_str());
          return nullptr;
      }
      Rc<Process> p = std::make_shared<Process>();
      p->pid_ = static_cast<std::int64_t>(pid);
      p->out_ = static_cast<std::int64_t>(fds[0]);
      return p;
  }
#endif

  // ---- signals, environment, exit --------------------------------------------------

  namespace
  {
    constexpr int SIGNAL_SLOTS = 65;
    // The handler reads only this lock-free pointer and stores one bool:
    // both async-signal-safe. keep_ holds the Rc so the object stays alive,
    // and a flag that onSignal replaces moves to retired_ rather than being
    // freed: a handler on another thread may have loaded its pointer a
    // moment before the swap and still be storing into it.
    std::atomic<sync::Atomic<bool>*> flags_[SIGNAL_SLOTS];
    // keep_ and retired_ change only under installLock_: two threads
    // installing handlers at once (a setup that runs in parallel) would
    // otherwise race on the Rc and the List. The handler never takes it.
    std::mutex installLock_;
    Rc<sync::Atomic<bool>> keep_[SIGNAL_SLOTS];
    List<Rc<sync::Atomic<bool>>> retired_;

    extern "C" void onSignalHandler(int sig)
    {
        if(sig >= 0 && sig < SIGNAL_SLOTS)
        {
            sync::Atomic<bool>* flag = flags_[sig].load(std::memory_order_acquire);
            if(flag != nullptr)
            {
                flag->store(true);
            }
        }
#if defined(_WIN32)
        // The C runtime resets a handler to SIG_DFL on delivery.
        (void)std::signal(sig, onSignalHandler);
#endif
    }
  }

  bool onSignal(std::int32_t sig, const Rc<sync::Atomic<bool>>& flag)
  {
      begin();
      if(sig <= 0 || sig >= SIGNAL_SLOTS || flag == nullptr)
      {
          failWith("onSignal", "no such signal, or no flag");
          return false;
      }
      // The lock covers the sigaction too, so two installs for one signal
      // publish the flag and the handler in the same order.
      const std::lock_guard<std::mutex> held(installLock_);
      if(keep_[sig] != flag)
      {
          // The new pointer is published before the old Rc moves out of the
          // slot, and the old object is kept: see retired_.
          flags_[sig].store(flag.get(), std::memory_order_release);
          if(keep_[sig] != nullptr)
          {
              retired_.push_back(std::move(keep_[sig]));
          }
          keep_[sig] = flag;
      }
#if defined(_WIN32)
      if(std::signal(sig, onSignalHandler) == SIG_ERR)
      {
          failErrno("signal");
          return false;
      }
      return true;
#else
      struct sigaction action;
      std::memset(&action, 0, sizeof action);
      action.sa_handler = onSignalHandler;
      sigemptyset(&action.sa_mask);
      action.sa_flags = 0; // no SA_RESTART: a blocked read returns, and the loop sees the flag
      if(::sigaction(sig, &action, nullptr) != 0)
      {
          failErrno("sigaction");
          return false;
      }
      return true;
#endif
  }

  Maybe<Str> env(const Str& name)
  {
      begin();
      const char* value = std::getenv(name.c_str());
      if(value == nullptr)
      {
          return none;
      }
      return Str(value);
  }

  void exit(std::int32_t code)
  {
      std::fflush(nullptr);
      std::_Exit(code);
  }
}

#endif // !KIRA_PROFILE_FREESTANDING
