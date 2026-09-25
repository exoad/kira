// kira/sync.hxx - the hosted runtime of kira:sync (D24): Thread on
// std::jthread, Mutex<T> that owns its data, Atomic<T>, BlockingQueue<T>,
// and spawn.
//
// Every class is a Kira class: one object per kira::Rc, never copied, shared
// between threads by that reference. The Fx parameters of with and waitUntil
// are templates (design 5.1, the non-escaping row) so a lambda costs no heap.
#pragma once

#include "kira/rt.hxx"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <stop_token>
#include <thread>
#include <utility>

#if defined(__linux__)
#include <pthread.h>
#endif

namespace kira::sync
{
  namespace impl_
  {
    // Names the calling thread, on Linux only: the kernel keeps 15 bytes plus
    // the terminator, so a longer name is truncated, never rejected.
    inline void nameThisThread(const Str& name) noexcept
    {
#if defined(__linux__)
        char buf[16];
        const Size n = name.size() < 15 ? name.size() : 15;
        std::memcpy(buf, name.data(), n);
        buf[n] = '\0';
        (void)pthread_setname_np(pthread_self(), buf);
#else
        (void)name;
#endif
    }

    // A wait that never overflows: negative means no limit, zero means now.
    template<class Lock, class Pred>
    bool waitFor(std::condition_variable& cv, Lock& lock, std::int64_t timeoutMs, Pred&& pred)
    {
        if(timeoutMs < 0)
        {
            cv.wait(lock, std::forward<Pred>(pred));
            return true;
        }
        if(timeoutMs == 0)
        {
            return pred();
        }
        return cv.wait_for(lock, std::chrono::milliseconds(timeoutMs), std::forward<Pred>(pred));
    }
  }

  class Thread final
  {
  public:
      template<class F>
        requires Callable<F, void>
      Thread(const Str& name, F&& body)
          : thread_([name, body = std::forward<F>(body)](std::stop_token) mutable
            {
                impl_::nameThisThread(name);
                body();
            })
      {
      }
      Thread(const Thread&) = delete;
      Thread& operator=(const Thread&) = delete;
      // std::jthread joins here, so dropping the last reference waits.
      ~Thread() = default;

      [[nodiscard]] bool stopRequested() const noexcept { return thread_.get_stop_token().stop_requested(); }
      void requestStop() noexcept { (void)thread_.request_stop(); }
      void join()
      {
          if(thread_.joinable())
          {
              thread_.join();
          }
      }

  private:
      std::jthread thread_;
  };

  template<class F>
    requires Callable<F, void>
  [[nodiscard]] Rc<Thread> spawn(const Str& name, F&& body)
  {
      return std::make_shared<Thread>(name, std::forward<F>(body));
  }

  template<class T>
  class Mutex final
  {
  public:
      explicit Mutex(T value)
          : value_(std::move(value))
      {
      }
      Mutex(const Mutex&) = delete;
      Mutex& operator=(const Mutex&) = delete;

      template<class F>
        requires Callable<F, void, T&>
      void with(F&& body)
      {
          {
              const std::lock_guard<std::mutex> lock(mutex_);
              body(value_);
          }
          changed_.notify_all();
      }

      template<class F>
        requires Callable<F, bool, const T&>
      [[nodiscard]] bool waitUntil(F&& pred, std::int64_t timeoutMs)
      {
          std::unique_lock<std::mutex> lock(mutex_);
          return impl_::waitFor(changed_, lock, timeoutMs, [&]() -> bool { return pred(static_cast<const T&>(value_)); });
      }

  private:
      std::mutex mutex_;
      std::condition_variable changed_;
      T value_;
  };

  template<class T>
  class Atomic final
  {
  public:
      explicit Atomic(T value) noexcept
          : value_(value)
      {
      }
      Atomic(const Atomic&) = delete;
      Atomic& operator=(const Atomic&) = delete;

      [[nodiscard]] T load() const noexcept { return value_.load(std::memory_order_seq_cst); }
      void store(T value) noexcept { value_.store(value, std::memory_order_seq_cst); }
      // The value after the add.
      T add(T delta) noexcept { return static_cast<T>(value_.fetch_add(delta, std::memory_order_seq_cst) + delta); }
      // The value before the store.
      T swap(T value) noexcept { return value_.exchange(value, std::memory_order_seq_cst); }
      [[nodiscard]] bool compareSwap(T expected, T desired) noexcept
      {
          return value_.compare_exchange_strong(expected, desired, std::memory_order_seq_cst);
      }
      [[nodiscard]] T loadAcquire() const noexcept { return value_.load(std::memory_order_acquire); }
      void storeRelease(T value) noexcept { value_.store(value, std::memory_order_release); }

  private:
      std::atomic<T> value_;
  };

  template<class T>
  class BlockingQueue final
  {
  public:
      BlockingQueue() = default;
      BlockingQueue(const BlockingQueue&) = delete;
      BlockingQueue& operator=(const BlockingQueue&) = delete;

      void push(T value)
      {
          {
              const std::lock_guard<std::mutex> lock(mutex_);
              if(closed_)
              {
                  return;
              }
              items_.push_back(std::move(value));
          }
          changed_.notify_one();
      }

      // None on timeout, and none once closed and drained.
      [[nodiscard]] Maybe<T> pop(std::int64_t timeoutMs)
      {
          std::unique_lock<std::mutex> lock(mutex_);
          (void)impl_::waitFor(changed_, lock, timeoutMs, [this]() -> bool { return closed_ || !items_.empty(); });
          if(items_.empty())
          {
              return none;
          }
          Maybe<T> out(std::move(items_.front()));
          items_.pop_front();
          return out;
      }

      void close()
      {
          {
              const std::lock_guard<std::mutex> lock(mutex_);
              closed_ = true;
          }
          changed_.notify_all();
      }

      [[nodiscard]] bool isClosed() const
      {
          const std::lock_guard<std::mutex> lock(mutex_);
          return closed_;
      }

      [[nodiscard]] Size size() const
      {
          const std::lock_guard<std::mutex> lock(mutex_);
          return items_.size();
      }

  private:
      mutable std::mutex mutex_;
      std::condition_variable changed_;
      std::deque<T> items_;
      bool closed_ = false;
  };
}
