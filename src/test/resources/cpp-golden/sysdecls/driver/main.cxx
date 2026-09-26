// sysdecls: declarations that hold the system modules' classes. A `@_magic`
// class of kira:sync or kira:os is the runtime's class held as kira::Rc, so
// `BlockingQueue<Str> {}` is std::make_shared, never a null pointer; and
// kira:os is kira/os.hxx alone, which defines Datagram, Ready and POLL_READ
// by hand, so no kira/std/os.kira.hxx is emitted to define them twice.
// Nothing here calls into os.cxx, so the driver links without it.
#include "../expected/src/app/hold.kira.hxx"
#include "../expected/src/app/net.kira.hxx"

#include <cstdio>
#include <type_traits>

static_assert(std::is_same_v<decltype(hold::Holder::q), kira::Rc<kira::sync::BlockingQueue<kira::Str>>>, "a system class is the runtime's, held as kira::Rc");
static_assert(std::is_same_v<decltype(hold::inbox), kira::Rc<kira::sync::BlockingQueue<kira::Str>>>, "module state too");
static_assert(std::is_same_v<decltype(hold::OUTBOX), const kira::Rc<kira::sync::BlockingQueue<std::int32_t>>>, "and a constant");
static_assert(std::is_same_v<decltype(net::Link::last), kira::os::Datagram>, "a struct of kira:os is the one kira/os.hxx defines");
static_assert(std::is_same_v<decltype(net::Link::poll), kira::Rc<kira::os::Poller>>, "Poller is kira/os.hxx's class");

namespace hold
{
  void wait(const kira::Rc<kira::sync::Thread>& t, const kira::Rc<kira::sync::Mutex<std::int32_t>>&)
  {
      if(t != nullptr)
      {
          t->join();
      }
  }

  void go()
  {
  }
}

namespace net
{
  bool send(const kira::Rc<kira::os::UdpSocket>& s, const kira::os::Datagram& d)
  {
      return s == nullptr && d.size == 3;
  }

  bool ready(const kira::os::Ready& r)
  {
      return r.events == kira::os::POLL_READ;
  }
}

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
}

int main()
{
    std::printf("\nsysdecls - the system modules' classes in declarations\n\n");
    {
        hold::Holder h;
        check(h.q != nullptr, "BlockingQueue<Str> {} is a queue, not a null Rc");
        h.q->push("x");
        const bool one = static_cast<std::size_t>(h.q->size()) == 1;
        const kira::Maybe<kira::Str> got = h.q->pop(0);
        check(one && kira::isSome(got) && kira::unwrap(got) == "x" && static_cast<std::size_t>(h.q->size()) == 0, "and it is the runtime's: push and pop go through it");
        check(!kira::isSome(h.worker) && h.count == 0, "Maybe<Thread> = null is none");
        check(hold::inbox != nullptr && hold::OUTBOX != nullptr && hold::inbox != h.q, "module state and a constant of a system class are queues of their own");
    }
    {
        net::Link l;
        check(l.poll != nullptr, "Poller {} is a poller");
        check(!kira::isSome(l.sock) && l.events == kira::os::POLL_READ && l.port == 0, "Maybe<UdpSocket> = null is none, and POLL_READ is kira/os.hxx's");
        check(l.last.size == 0 && l.last.host.empty() && l.last.port == 0 && kira::os::Ready{}.handle == -1, "Datagram and Ready are the structs kira/os.hxx defines by hand");
        kira::os::Datagram d{};
        d.size = 3;
        kira::os::Ready r{};
        r.events = kira::os::POLL_READ;
        check(net::send(nullptr, d) && net::ready(r), "functions take the runtime's classes by const Rc& and its structs by const&");
    }
    std::printf("\n%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
