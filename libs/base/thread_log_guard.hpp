#pragma once
namespace base
{
// Stack-scoped, nested and current-thread-only. Used around privacy-sensitive local computation.
// Does not change global log level, sinks or another thread's diagnostics. Fatal assertions remain.
bool IsThreadLoggingEnabled();
class ScopedThreadLogSilencer
{
public:
  ScopedThreadLogSilencer();
  ~ScopedThreadLogSilencer();
  ScopedThreadLogSilencer(ScopedThreadLogSilencer const &)=delete;
  ScopedThreadLogSilencer & operator=(ScopedThreadLogSilencer const &)=delete;
};
}
