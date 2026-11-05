#include "base/thread_log_guard.hpp"
namespace base {
namespace { thread_local unsigned depth=0; }
bool IsThreadLoggingEnabled(){return depth==0;}
ScopedThreadLogSilencer::ScopedThreadLogSilencer(){++depth;}
ScopedThreadLogSilencer::~ScopedThreadLogSilencer(){--depth;}
}
