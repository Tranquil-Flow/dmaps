#include "drape_frontend/drape_api.hpp"
#include "drape_frontend/drape_engine.hpp"
#include "drape_frontend/message.hpp"
#include "drape_frontend/message_subclasses.hpp"
#include "drape_frontend/threads_commutator.hpp"

namespace df
{
void DrapeApi::SetDrapeEngine(ref_ptr<DrapeEngine> engine)
{
  m_engine.Set(engine);
}

void DrapeApi::AddLine(std::string const & id, DrapeApiLineData const & data)
{
  TryAddLine(id, data);
}

bool DrapeApi::TryAddLine(std::string const & id, DrapeApiLineData const & data)
{
  DrapeEngineLockGuard lock(m_engine);
  if (!lock)
    return false;

  auto & threadCommutator = lock.Get()->m_threadCommutator;
  auto const it = m_lines.find(id);
  if (it != m_lines.end())
  {
    threadCommutator->PostMessage(ThreadsCommutator::ResourceUploadThread, make_unique_dp<DrapeApiRemoveMessage>(id),
                                  MessagePriority::Normal);
  }

  m_lines[id] = data;

  TLines lines;
  lines.insert(std::make_pair(id, data));
  threadCommutator->PostMessage(ThreadsCommutator::ResourceUploadThread, make_unique_dp<DrapeApiAddLinesMessage>(lines),
                                MessagePriority::Normal);
  return true;
}

bool DrapeApi::HasLine(std::string const & id)
{
  DrapeEngineLockGuard lock(m_engine);
  return m_lines.count(id) != 0;
}

void DrapeApi::RemoveLine(std::string const & id)
{
  DrapeEngineLockGuard lock(m_engine);
  // Retire replay state even when the renderer has already been detached.
  m_lines.erase(id);
  if (!lock)
    return;

  auto & threadCommutator = lock.Get()->m_threadCommutator;
  threadCommutator->PostMessage(ThreadsCommutator::ResourceUploadThread, make_unique_dp<DrapeApiRemoveMessage>(id),
                                MessagePriority::Normal);
}

void DrapeApi::Clear()
{
  DrapeEngineLockGuard lock(m_engine);
  // A later renderer must not resurrect lines explicitly cleared while absent.
  m_lines.clear();
  if (!lock)
    return;

  auto & threadCommutator = lock.Get()->m_threadCommutator;
  threadCommutator->PostMessage(ThreadsCommutator::ResourceUploadThread,
                                make_unique_dp<DrapeApiRemoveMessage>("", true /* remove all */),
                                MessagePriority::Normal);
}

void DrapeApi::Invalidate()
{
  DrapeEngineLockGuard lock(m_engine);
  if (!lock)
    return;

  auto & threadCommutator = lock.Get()->m_threadCommutator;
  threadCommutator->PostMessage(ThreadsCommutator::ResourceUploadThread,
                                make_unique_dp<DrapeApiRemoveMessage>("", true /* remove all */),
                                MessagePriority::Normal);

  threadCommutator->PostMessage(ThreadsCommutator::ResourceUploadThread,
                                make_unique_dp<DrapeApiAddLinesMessage>(m_lines), MessagePriority::Normal);
}
}  // namespace df
