// sys_test - the hosted system runtime (kira/time.hxx, sync.hxx, os.hxx,
// test.hxx) against what kira:time, kira:sync, kira:os and kira:test promise.
// bash kira/cpp/tests/sys.sh builds and runs it on every toolchain found;
// kira\cpp\tests\sys_msvc.bat does the same with MSVC.
//
// Modes:
//   sys_test                 the suite, printed through kira::test::Suite
//   sys_test testfmt-ref     the check format as bibo's test_proto.cxx prints it
//   sys_test testfmt-suite   the same script through kira::test::Suite; the
//                            two outputs must be identical, and both exit 1
//   sys_test exit N          kira::os::exit(N)
//   sys_test child ...       the process the Process checks spawn (itself)
//
// The pty pair (openpty) exists on Linux only; elsewhere the Serial checks
// cover the failure path. Windows and POSIX print the same lines for every
// check both have, so gcc and clang on one host must agree byte for byte.
#include "kira/time.hxx"
#include "kira/sync.hxx"
#include "kira/os.hxx"
#include "kira/test.hxx"

#include <algorithm>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <memory>

#if defined(__linux__)
#include <poll.h>
#include <pthread.h>
#include <pty.h>
#include <unistd.h>
#endif

namespace
{
  using kira::Str;

  // ---- the check format, as bibo prints it (test_proto.cxx lines 15-45, 191) ----
  int refFailures = 0;
  int refChecks = 0;

  void refCheck(bool ok, const char* what)
  {
      ++refChecks;
      if(ok)
      {
          std::printf("  ok    %s\n", what);
      }
      else
      {
          std::printf("  FAIL  %s\n", what);
          ++refFailures;
      }
  }

  void refCheckStr(const Str& got, const char* want, const char* what)
  {
      const bool ok = (got == want);
      refCheck(ok, what);
      if(!ok)
      {
          std::printf("        got  \"%s\"\n        want \"%s\"\n", got.c_str(), want);
      }
  }

  int testfmtRef()
  {
      std::printf("\nsys - the check format\n\n");
      refCheck(true, "a passing check");
      refCheck(false, "a failing check");
      refCheckStr(Str("abc"), "abc", "equal strings");
      refCheckStr(Str("got this"), "want that", "unequal strings");
      refCheckStr(Str(""), "", "empty strings");
      std::printf("\n%d checks, %d failed\n\n", refChecks, refFailures);
      return refFailures == 0 ? 0 : 1;
  }

  int testfmtSuite()
  {
      const kira::Rc<kira::test::Suite> s = std::make_shared<kira::test::Suite>("sys - the check format");
      s->check(true, "a passing check");
      s->check(false, "a failing check");
      s->checkStr("abc", "abc", "equal strings");
      s->checkStr("got this", "want that", "unequal strings");
      s->checkStr("", "", "empty strings");
      return s->finish();
  }

  // ---- the child the Process checks spawn ----
  int child(int argc, char** argv)
  {
      if(argc >= 3 && std::strcmp(argv[2], "exit") == 0 && argc >= 4)
      {
          return std::atoi(argv[3]);
      }
      if(argc >= 3 && std::strcmp(argv[2], "sleep") == 0)
      {
          kira::time::sleepMs(10000);
          return 0;
      }
      std::printf("child says hi\n");
      return 0;
  }

  [[nodiscard]] kira::View<std::uint8_t> bytesOf(const char* s)
  {
      return kira::View<std::uint8_t>(reinterpret_cast<const std::uint8_t*>(s), std::strlen(s));
  }

  [[nodiscard]] Str textOf(const std::uint8_t* p, std::int64_t n)
  {
      return n <= 0 ? Str() : Str(reinterpret_cast<const char*>(p), static_cast<kira::Size>(n));
  }

  // The directory holding this program: the scripts run it by absolute path.
  [[nodiscard]] Str dirOf(const char* self)
  {
      const Str s(self);
      const kira::Size cut = s.find_last_of("/\\");
      return cut == Str::npos ? Str(".") : s.substr(0, cut);
  }

  // ---- the suite ----
  using Suite = kira::test::Suite;

  void timeChecks(Suite& t)
  {
      const std::int64_t a = kira::time::monoNowNs();
      const std::int64_t b = kira::time::monoNowNs();
      t.check(b >= a, "monoNowNs never goes backwards");
      const std::int64_t ms0 = kira::time::monoNowMs();
      kira::time::sleepMs(20);
      const std::int64_t ms1 = kira::time::monoNowMs();
      t.check(ms1 - ms0 >= 19, "sleepMs(20) advances monoNowMs by at least 19");
      const std::int64_t ns0 = kira::time::monoNowNs();
      kira::time::sleepMs(0);
      kira::time::sleepMs(-5);
      t.check(kira::time::monoNowNs() - ns0 < 50000000, "sleepMs of zero or less returns at once");
      // 2020-01-01T00:00:00Z in nanoseconds: the wall clock is set.
      t.check(kira::time::wallNowNs() > 1577836800LL * 1000000000LL, "wallNowNs is after 2020");
  }

  void syncChecks(Suite& t)
  {
      using kira::sync::Atomic;
      using kira::sync::BlockingQueue;
      using kira::sync::Mutex;
      using kira::sync::Thread;
      {
          const kira::Rc<Atomic<bool>> ran = std::make_shared<Atomic<bool>>(false);
          const kira::Rc<Thread> th = kira::sync::spawn("ran", [ran]() { ran->store(true); });
          th->join();
          t.check(ran->load(), "spawn runs the body and join waits for it");
          th->join();
          t.check(true, "a second join is a no-op");
      }
      {
          const kira::Rc<Mutex<Str>> seen = std::make_shared<Mutex<Str>>(Str());
          const kira::Rc<Thread> th = kira::sync::spawn("a-name-longer-than-15", [seen]()
          {
              Str name;
#if defined(__linux__)
              char buf[32] = {0};
              (void)pthread_getname_np(pthread_self(), buf, sizeof buf);
              name = buf;
#else
              name = "a-name-longer-t"; // no thread names here: the promise is Linux only
#endif
              seen->with([&name](Str& v) { v = name; });
          });
          th->join();
          Str got;
          seen->with([&got](Str& v) { got = v; });
          t.checkStr(got, "a-name-longer-t", "the thread name is kept, truncated to 15 bytes (Linux)");
      }
      {
          const kira::Rc<Atomic<bool>> go = std::make_shared<Atomic<bool>>(false);
          const kira::Rc<Thread> th = kira::sync::spawn("waits", [go]()
          {
              while(!go->loadAcquire())
              {
                  kira::time::sleepMs(1);
              }
          });
          t.check(!th->stopRequested(), "stopRequested is false until requestStop");
          th->requestStop();
          t.check(th->stopRequested(), "and true after it");
          go->storeRelease(true);
          th->join();
      }
      {
          // 4 threads, 25000 locked increments each: 100000 Mutex operations.
          const kira::Rc<Mutex<std::int64_t>> total = std::make_shared<Mutex<std::int64_t>>(0);
          {
              kira::List<kira::Rc<Thread>> workers;
              for(int w = 0; w < 4; ++w)
              {
                  workers.push_back(kira::sync::spawn("adder", [total]()
                  {
                      for(int i = 0; i < 25000; ++i)
                      {
                          total->with([](std::int64_t& v) { v += 1; });
                      }
                  }));
              }
          }
          std::int64_t got = -1;
          total->with([&got](std::int64_t& v) { got = v; });
          t.check(got == 100000, "100000 increments under Mutex.with from 4 threads count to 100000");
          const bool held = total->waitUntil([](const std::int64_t& v) { return v == 100000; }, 0);
          t.check(held, "waitUntil with timeout 0 checks once");
          const std::int64_t before = kira::time::monoNowMs();
          const bool never = total->waitUntil([](const std::int64_t& v) { return v == 99; }, 40);
          const std::int64_t waited = kira::time::monoNowMs() - before;
          t.check(!never && waited >= 35, "waitUntil times out false after its timeout");
          const kira::Rc<Thread> th = kira::sync::spawn("setter", [total]()
          {
              kira::time::sleepMs(30);
              total->with([](std::int64_t& v) { v = 7; });
          });
          const std::int64_t before7 = kira::time::monoNowMs();
          const bool woke = total->waitUntil([](const std::int64_t& v) { return v == 7; }, 5000);
          const std::int64_t waited7 = kira::time::monoNowMs() - before7;
          t.check(woke && waited7 < 4000, "waitUntil wakes when a with() makes the predicate true");
          th->join();
      }
      {
          // 4 threads, 25000 adds each: 100000 Atomic operations.
          const kira::Rc<Atomic<std::int64_t>> n = std::make_shared<Atomic<std::int64_t>>(0);
          {
              kira::List<kira::Rc<Thread>> workers;
              for(int w = 0; w < 4; ++w)
              {
                  workers.push_back(kira::sync::spawn("atomic", [n]()
                  {
                      for(int i = 0; i < 25000; ++i)
                      {
                          (void)n->add(1);
                      }
                  }));
              }
          }
          t.check(n->load() == 100000, "100000 Atomic.add(1) from 4 threads count to 100000");
          t.check(n->add(5) == 100005, "add returns the value after the add");
          t.check(n->swap(1) == 100005 && n->load() == 1, "swap returns the value before");
          t.check(n->compareSwap(1, 2) && n->load() == 2, "compareSwap stores when the value matches");
          t.check(!n->compareSwap(1, 3) && n->load() == 2, "and leaves it when it does not");
          n->storeRelease(9);
          t.check(n->loadAcquire() == 9, "storeRelease pairs with loadAcquire");
      }
      {
          // 100000 pushes on one thread, 100000 pops on this one.
          const kira::Rc<BlockingQueue<std::int32_t>> q = std::make_shared<BlockingQueue<std::int32_t>>();
          const kira::Rc<Thread> producer = kira::sync::spawn("producer", [q]()
          {
              for(std::int32_t i = 0; i < 100000; ++i)
              {
                  q->push(i);
              }
              q->close();
          });
          std::int64_t count = 0;
          std::int64_t sum = 0;
          for(;;)
          {
              const kira::Maybe<std::int32_t> v = q->pop(5000);
              if(!kira::isSome(v))
              {
                  break;
              }
              ++count;
              sum += kira::unwrap(v);
          }
          producer->join();
          t.check(count == 100000 && sum == 4999950000LL, "100000 values cross a BlockingQueue in order and in full");
          t.check(q->isClosed() && q->size() == 0, "pop returns none once the queue is closed and drained");
          q->push(1);
          t.check(q->size() == 0, "a push after close is dropped");
      }
      {
          const kira::Rc<BlockingQueue<Str>> q = std::make_shared<BlockingQueue<Str>>();
          const std::int64_t before = kira::time::monoNowMs();
          const kira::Maybe<Str> none = q->pop(40);
          const std::int64_t waited = kira::time::monoNowMs() - before;
          t.check(!kira::isSome(none) && waited >= 35, "pop on an empty queue returns none after its timeout");
          q->push("x");
          const kira::Maybe<Str> some = q->pop(0);
          t.check(kira::isSome(some) && kira::unwrap(some) == "x", "pop with timeout 0 takes what is there");
      }
  }

  void udpChecks(Suite& t)
  {
      using kira::os::Poller;
      using kira::os::UdpSocket;
      const kira::Rc<UdpSocket> a = std::make_shared<UdpSocket>();
      const kira::Rc<UdpSocket> b = std::make_shared<UdpSocket>();
      t.check(a->bind("127.0.0.1", 0) && b->bind("127.0.0.1", 0), "two UDP sockets bind to port 0 on loopback");
      const std::int32_t pa = a->localPort();
      const std::int32_t pb = b->localPort();
      t.check(pa > 0 && pb > 0 && pa != pb, "and each got its own port back");
      std::uint8_t buf[64];
      const kira::MutView<std::uint8_t> into(buf, sizeof buf);
      t.check(!kira::isSome(b->recvFrom(into)), "recvFrom on a quiet socket returns none, not a wait");
      const kira::Rc<Poller> poller = std::make_shared<Poller>();
      poller->add(b->handle(), kira::os::POLL_READ);
      t.check(poller->wait(0).empty(), "the poller sees nothing yet");
      t.check(a->sendTo(bytesOf("hello"), "127.0.0.1", pb) == 5, "sendTo reports the 5 bytes sent");
      const kira::List<kira::os::Ready> ready = poller->wait(2000);
      t.check(ready.size() == 1 && ready[0].handle == b->handle() && (ready[0].events & kira::os::POLL_READ) != 0,
              "the poller reports the receiving socket readable");
      const kira::Maybe<kira::os::Datagram> d = b->recvFrom(into);
      t.check(kira::isSome(d) && kira::unwrap(d).size == 5, "recvFrom returns the datagram's size");
      t.checkStr(kira::isSome(d) ? textOf(buf, 5) : Str(), "hello", "and its bytes");
      t.checkStr(kira::isSome(d) ? kira::unwrap(d).host : Str(), "127.0.0.1", "and the sender's host");
      t.check(kira::isSome(d) && kira::unwrap(d).port == pa, "and the sender's port");
      t.check(a->sendTo(bytesOf("x"), "not-a-host", pb) == -1, "sendTo a name is -1: hosts are numeric");
      t.check(kira::os::lastError().find("IPv4") != Str::npos, "and lastError says why");
      poller->remove(b->handle());
      t.check(poller->wait(0).empty(), "a removed handle is not polled");
      a->close();
      t.check(a->handle() == -1 && a->localPort() == -1, "a closed socket has no handle and no port");
  }

  void tcpChecks(Suite& t)
  {
      using kira::os::Poller;
      using kira::os::TcpListener;
      using kira::os::TcpStream;
      const kira::Rc<TcpListener> l = std::make_shared<TcpListener>();
      t.check(l->listen(0), "a TCP listener takes port 0");
      const std::int32_t port = l->localPort();
      t.check(port > 0, "and reports the port it got");
      t.check(l->accept() == nullptr, "accept with nobody waiting returns none, not a wait");
      const kira::Rc<TcpStream> c = std::make_shared<TcpStream>();
      t.check(c->connect("127.0.0.1", port), "a client connects to it");
      const kira::Rc<Poller> lp = std::make_shared<Poller>();
      lp->add(l->handle(), kira::os::POLL_READ);
      t.check(!lp->wait(2000).empty(), "the poller reports the listener readable");
      const kira::Rc<TcpStream> s = l->accept();
      t.check(s != nullptr && s->isOpen(), "and accept returns the connection");
      if(s == nullptr)
      {
          return;
      }
      t.check(c->write(bytesOf("ping")) == 4, "the client writes 4 bytes");
      const kira::Rc<Poller> sp = std::make_shared<Poller>();
      sp->add(s->handle(), kira::os::POLL_READ);
      t.check(!sp->wait(2000).empty(), "the poller reports the server stream readable");
      std::uint8_t buf[16];
      const kira::MutView<std::uint8_t> into(buf, sizeof buf);
      const std::int64_t got = s->read(into);
      t.checkStr(textOf(buf, got), "ping", "and the server reads them");
      t.check(s->write(bytesOf("pong")) == 4, "the server writes 4 bytes back");
      const std::int64_t back = c->read(into);
      t.checkStr(textOf(buf, back), "pong", "and the client reads them");
      c->close();
      t.check(!c->isOpen(), "a closed stream is not open");
      const kira::List<kira::os::Ready> hup = sp->wait(2000);
      t.check(!hup.empty(), "the poller reports the hang-up");
      t.check(s->read(into) == 0, "and read returns 0 for the closed peer");
  }

  void serialChecks(Suite& t)
  {
      using kira::os::Serial;
      const kira::Rc<Serial> missing = std::make_shared<Serial>();
#if defined(_WIN32)
      const char* nowhere = "COM255";
#else
      const char* nowhere = "/dev/kira-no-such-port";
#endif
      t.check(!missing->open(nowhere, 115200) && !missing->isOpen(), "opening a port that does not exist fails");
      t.check(!kira::os::lastError().empty(), "and lastError says so");
      std::uint8_t buf[32];
      const kira::MutView<std::uint8_t> into(buf, sizeof buf);
      t.check(missing->read(into, 10) == -1 && missing->write(bytesOf("x")) == -1, "read and write on a closed port are -1");
#if defined(__linux__)
      int master = -1;
      int slave = -1;
      char name[128] = {0};
      if(openpty(&master, &slave, name, nullptr, nullptr) != 0)
      {
          t.check(false, "openpty gives a pty pair");
          return;
      }
      t.check(true, "openpty gives a pty pair");
      const kira::Rc<Serial> port = std::make_shared<Serial>();
      t.check(!port->open(name, 12345), "a baud rate termios lacks is refused");
      t.check(port->open(name, 115200) && port->isOpen() && port->handle() >= 0, "the pty slave opens as a raw 8N1 port");
      const std::int64_t before = kira::time::monoNowMs();
      t.check(port->read(into, 40) == 0 && kira::time::monoNowMs() - before >= 35, "a read with nothing to read times out to 0");
      t.check(::write(master, "hello\n", 6) == 6, "the master writes 6 bytes");
      std::int64_t got = 0;
      const std::int64_t deadline = kira::time::monoNowMs() + 2000;
      while(got < 6 && kira::time::monoNowMs() < deadline)
      {
          const std::int64_t n = port->read(kira::MutView<std::uint8_t>(buf + got, sizeof buf - static_cast<kira::Size>(got)), 500);
          if(n < 0)
          {
              break;
          }
          got += n;
      }
      t.checkStr(textOf(buf, got), "hello\n", "and the port reads them, raw");
      t.check(port->write(bytesOf("ok")) == 2, "the port writes 2 bytes");
      pollfd p;
      std::memset(&p, 0, sizeof p);
      p.fd = master;
      p.events = POLLIN;
      char back[8] = {0};
      ssize_t n = 0;
      if(::poll(&p, 1, 2000) > 0)
      {
          n = ::read(master, back, sizeof back);
      }
      t.checkStr(n > 0 ? Str(back, static_cast<kira::Size>(n)) : Str(), "ok", "and the master reads them");
      port->close();
      t.check(!port->isOpen() && port->handle() == -1, "a closed port has no handle");
      ::close(slave);
      ::close(master);
#endif
  }

  void fileChecks(Suite& t, const Str& base)
  {
      const Str dir = base + "/sys_test_files";
      const Str deep = dir + "/a/b";
      const Str path = deep + "/data.bin";
      (void)std::remove(path.c_str());
      t.check(kira::os::makeDirs(deep), "makeDirs creates a nested directory");
      t.check(kira::os::exists(deep), "and it exists");
      t.check(kira::os::makeDirs(deep), "makeDirs on an existing directory is true");
      const std::uint8_t first[4] = {1, 2, 3, 250};
      t.check(kira::os::writeFileAtomic(path, kira::View<std::uint8_t>(first, 4)), "writeFileAtomic writes 4 bytes");
      const kira::Maybe<kira::List<std::uint8_t>> back = kira::os::readFile(path);
      t.check(kira::isSome(back) && kira::unwrap(back).size() == 4 && kira::unwrap(back)[3] == 250, "and readFile reads them back");
      t.check(kira::os::writeFileAtomic(path, bytesOf("second")), "writeFileAtomic replaces the file");
      const kira::Maybe<Str> text = kira::os::readText(path);
      t.checkStr(kira::isSome(text) ? kira::unwrap(text) : Str(), "second", "and readText sees the new content");
      const kira::List<Str> names = kira::os::listDir(deep);
      t.check(names.size() == 1 && names[0] == "data.bin", "listDir shows the file and no temporary");
      t.check(kira::os::writeFileAtomic(path, kira::View<std::uint8_t>()), "an empty write is a write");
      const kira::Maybe<Str> empty = kira::os::readText(path);
      t.check(kira::isSome(empty) && kira::unwrap(empty).empty(), "and reads back empty");
      t.check(!kira::os::exists(dir + "/nope"), "exists is false for a missing path");
      t.check(!kira::isSome(kira::os::readFile(dir + "/nope")), "readFile of a missing file is none");
      t.check(!kira::os::lastError().empty(), "and lastError says why");
      t.check(kira::os::listDir(dir + "/nope").empty(), "listDir of a missing directory is empty");
      t.check(!kira::os::makeDirs(path), "makeDirs over a file fails");
      t.check(kira::os::freeBytes(dir) > 0, "freeBytes of the test directory is positive");
      t.check(kira::os::freeBytes(dir + "/nope") == -1, "freeBytes of a missing path is -1");
  }

  void processChecks(Suite& t, const char* self)
  {
      using kira::os::Process;
      std::uint8_t buf[256];
      const kira::MutView<std::uint8_t> into(buf, sizeof buf);
      {
          const kira::Rc<Process> p = kira::os::spawnProcess(kira::List<Str>{self, "child"});
          t.check(p != nullptr, "spawnProcess starts this program as a child");
          if(p == nullptr)
          {
              return;
          }
          Str out;
          for(;;)
          {
              const std::int64_t n = p->readStdout(into);
              if(n <= 0)
              {
                  break;
              }
              out += textOf(buf, n);
          }
          // The child's stdout is a text-mode stream: on Windows it ends the line
          // with \r\n. That is the child's doing, not the pipe's.
          out.erase(std::remove(out.begin(), out.end(), '\r'), out.end());
          t.checkStr(out, "child says hi\n", "and reads its stdout to the end");
          t.check(p->wait() == 0, "wait returns its exit code 0");
          t.check(!p->isRunning(), "and it is not running");
          t.check(p->wait() == 0, "a second wait returns the same code");
      }
      {
          const kira::Rc<Process> p = kira::os::spawnProcess(kira::List<Str>{self, "child", "exit", "3"});
          t.check(p != nullptr && p->wait() == 3, "a child that exits 3 reports 3");
      }
      {
          const kira::Rc<Process> p = kira::os::spawnProcess(kira::List<Str>{self, "child", "sleep"});
          t.check(p != nullptr && p->isRunning(), "a sleeping child is running");
          const std::int64_t before = kira::time::monoNowMs();
          if(p != nullptr)
          {
              p->kill();
          }
          const std::int32_t code = p != nullptr ? p->wait() : -1;
          t.check(code == 137 && kira::time::monoNowMs() - before < 5000, "kill ends it and wait reports 137");
      }
      {
          const kira::Rc<Process> p = kira::os::spawnProcess(kira::List<Str>{"kira-no-such-program-xyz"});
          // POSIX learns of the missing program in the child (exit 127); Win32 at CreateProcess.
          t.check(p == nullptr || p->wait() == 127, "a program that does not exist does not start");
      }
      t.check(kira::os::spawnProcess(kira::List<Str>{}) == nullptr, "an empty argv is refused");
  }

  void signalChecks(Suite& t)
  {
      const kira::Rc<kira::sync::Atomic<bool>> flag = std::make_shared<kira::sync::Atomic<bool>>(false);
      t.check(kira::os::onSignal(kira::os::SIGNAL_INT, flag), "onSignal installs a handler for SIGINT");
      (void)std::raise(SIGINT);
      t.check(flag->load(), "raise(SIGINT) sets the flag");
      flag->store(false);
      (void)std::raise(SIGINT);
      t.check(flag->load(), "and a second raise sets it again");
      t.check(!kira::os::onSignal(0, flag), "signal 0 is refused");
      t.check(!kira::os::onSignal(kira::os::SIGNAL_TERM, nullptr), "a missing flag is refused");
  }

  void envChecks(Suite& t)
  {
      t.check(kira::isSome(kira::os::env("PATH")), "env finds PATH");
      t.check(!kira::isSome(kira::os::env("KIRA_SYS_TEST_NO_SUCH_VARIABLE")), "and none for a name that is not set");
      const kira::Rc<kira::sync::Mutex<Str>> fresh = std::make_shared<kira::sync::Mutex<Str>>(Str("unset"));
      const kira::Rc<kira::sync::Thread> th = kira::sync::spawn("err", [fresh]()
      {
          const Str e = kira::os::lastError();
          fresh->with([&e](Str& v) { v = e; });
      });
      th->join();
      Str got;
      fresh->with([&got](Str& v) { got = v; });
      t.checkStr(got, "", "lastError is per thread: a new thread starts with none");
  }

  int suite(int argc, char** argv)
  {
      (void)argc;
      const kira::Rc<Suite> t = std::make_shared<Suite>("sys - time, sync, os and the check format");
      timeChecks(*t);
      syncChecks(*t);
      udpChecks(*t);
      tcpChecks(*t);
      serialChecks(*t);
      fileChecks(*t, dirOf(argv[0]));
      processChecks(*t, argv[0]);
      signalChecks(*t);
      envChecks(*t);
      return t->finish();
  }
}

int main(int argc, char** argv)
{
    if(argc >= 2 && std::strcmp(argv[1], "testfmt-ref") == 0)
    {
        return testfmtRef();
    }
    if(argc >= 2 && std::strcmp(argv[1], "testfmt-suite") == 0)
    {
        return testfmtSuite();
    }
    if(argc >= 3 && std::strcmp(argv[1], "exit") == 0)
    {
        std::printf("exiting\n");
        kira::os::exit(std::atoi(argv[2]));
    }
    if(argc >= 2 && std::strcmp(argv[1], "child") == 0)
    {
        return child(argc, argv);
    }
    return suite(argc, argv);
}
